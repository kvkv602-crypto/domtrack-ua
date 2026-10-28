'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { kyivDate } = require('./fx.cjs');
const duplicateTools = require('./duplicates.cjs');

const CURRENCIES = new Set(['USD', 'UAH', 'EUR']);
const validId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : null;
const validTime = value => typeof value === 'string' && Number.isFinite(Date.parse(value));
function monitorError(message, statusCode = 400) { const error = new Error(message); error.code = 'MONITOR'; error.statusCode = statusCode; return error; }
function messageOf(error, fallback = 'Не удалось обновить цену из источника.') {
  if (typeof error?.code === 'string' && /^(?:RIA_|WEB_|MONITOR$)/.test(error.code) && typeof error.message === 'string') return error.message.slice(0, 500);
  return fallback;
}
function canonicalUrl(value) {
  try {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) return null;
    url.hash = '';
    for (const key of [...url.searchParams.keys()]) if (/^utm_/i.test(key) || /^(?:gclid|fbclid|msclkid|yclid|_ga)$/i.test(key)) url.searchParams.delete(key);
    url.searchParams.sort();
    return url.href;
  } catch { return null; }
}

function createPriceMonitor(options = {}) {
  const { store, preferences, fx, getRia, webScanner, ingest, notify = () => {}, intervalMinutes = () => 360, stateFile } = options;
  if (!store || typeof store.detail !== 'function' || !preferences || typeof preferences.get !== 'function' || typeof preferences.notify !== 'function' || typeof ingest !== 'function') throw monitorError('Монитору нужны хранилище, настройки, сохранение наблюдений и уведомления.');
  const now = options.now || Date.now;
  const setTimer = options.setTimeoutImpl || setTimeout;
  const clearTimer = options.clearTimeoutImpl || clearTimeout;
  const filename = stateFile ? path.resolve(stateFile) : null;
  let checks = Object.create(null);
  let cursor = 0;
  let lastRun = null;
  let lastError = null;
  let nextRun = null;
  let running = false;
  let closed = false;
  let timer = null;
  let pending = null;
  let evaluationTail = Promise.resolve();

  if (filename) {
    try {
      if (fs.statSync(filename).size <= 1024 * 1024) {
        const saved = JSON.parse(fs.readFileSync(filename, 'utf8'));
        if (saved && saved.version === 1) {
          cursor = Number.isSafeInteger(saved.cursor) && saved.cursor >= 0 ? saved.cursor : 0;
          lastRun = validTime(saved.lastRun) ? saved.lastRun : null;
          if (saved.checks && typeof saved.checks === 'object' && !Array.isArray(saved.checks)) {
            for (const [id, check] of Object.entries(saved.checks).slice(0, 100)) {
              if (!/^[1-9]\d*$/.test(id) || !check || typeof check !== 'object') continue;
              checks[id] = { lastChecked: validTime(check.lastChecked) ? check.lastChecked : null, lastError: typeof check.lastError === 'string' ? check.lastError.slice(0, 500) : null };
            }
          }
        }
      }
    } catch { /* Missing or damaged monitor state does not create observations. */ }
  }
  function persist() {
    if (!filename) return;
    fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
    const temporary = `${filename}.tmp-${process.pid}-${crypto.randomBytes(6).toString('hex')}`;
    let descriptor;
    try {
      descriptor = fs.openSync(temporary, 'wx', 0o600);
      fs.writeFileSync(descriptor, JSON.stringify({ version: 1, cursor, lastRun, checks }, null, 2), 'utf8');
      fs.fsyncSync(descriptor);
      fs.closeSync(descriptor); descriptor = undefined;
      fs.renameSync(temporary, filename);
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor);
      try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  }
  function activeWatches() {
    const value = preferences.get();
    return (Array.isArray(value?.watches) ? value.watches : []).filter(watch => watch?.active === true && validId(watch.id) && CURRENCIES.has(watch.currency) && validTime(watch.createdAt)).slice(0, 100);
  }
  function interval() {
    let value;
    try { value = typeof intervalMinutes === 'function' ? intervalMinutes() : intervalMinutes; } catch { value = 360; }
    return Number.isFinite(Number(value)) ? Math.min(10080, Math.max(120, Number(value))) : 360;
  }
  function status() { return { checks: JSON.parse(JSON.stringify(checks)), lastRun, running, lastError, nextRun, intervalMinutes: interval() }; }
  function listingFor(id) {
    const detail = store.detail(id);
    return detail?.listing || (detail?.id ? detail : null);
  }
  function setCheckError(id, message) { checks[String(id)] = { lastChecked: checks[String(id)]?.lastChecked || null, lastError: message }; }
  function candidatesFor(watched, listings, quote, duplicateRules) {
    const candidates = listings.filter(item => item && item.status !== 'inactive' && item.city === watched.city && item.region === watched.region && item.kind === watched.kind && item.deal === watched.deal && item.period === watched.period);
    if (!candidates.some(item => Number(item.id) === Number(watched.id))) candidates.push(watched);
    try {
      const result = duplicateTools.analyzeDuplicates(candidates, { sameGroups: duplicateRules.sameGroups || [], differentPairs: duplicateRules.differentPairs || [], ...(quote ? { rates: quote.rates, asOf: quote.asOf } : {}) });
      const group = result.groups.find(item => item.items.some(listing => Number(listing.id) === Number(watched.id)) && typeof duplicateTools.isConfirmedGroup === 'function' && duplicateTools.isConfirmedGroup(item));
      return group ? group.items : [watched];
    } catch { return [watched]; }
  }
  async function runEvaluation() {
    const result = { evaluated: 0, triggered: 0, notifications: [], errors: [] };
    if (closed) return result;
    const watches = activeWatches();
    const duplicateRules = preferences.get()?.duplicateRules || {};
    let all = [];
    try {
      const supplied = typeof options.allListings === 'function' ? options.allListings() : typeof store.allListings === 'function' ? store.allListings() : [];
      all = Array.isArray(supplied) ? supplied : [];
    } catch { /* Individual watched listings are still evaluable. */ }
    const quotes = new Map();
    async function quoteFor(date) {
      if (!quotes.has(date)) quotes.set(date, (async () => {
        if (!fx || typeof fx.getForDate !== 'function') throw monitorError('Нет официального курса НБУ на дату наблюдения.');
        const quote = await fx.getForDate(date);
        if (!quote || quote.usable === false || quote.asOf !== date || quote.rates?.UAH !== 1 || !['USD', 'EUR'].every(currency => Number.isFinite(quote.rates[currency]) && quote.rates[currency] > 0)) throw monitorError('Нет подтверждённого курса НБУ на дату наблюдения.');
        return quote;
      })());
      return quotes.get(date);
    }
    const events = [];
    for (const watch of watches) {
      if (closed) break;
      if (typeof watch.targetPrice !== 'number' || !Number.isFinite(watch.targetPrice) || watch.targetPrice < 0) continue;
      const listing = listingFor(watch.id);
      if (!listing || listing.status === 'inactive') continue;
      result.evaluated++;
      let groupingQuote = null;
      const relevant = all.filter(item => item && item.status !== 'inactive' && item.city === listing.city && item.region === listing.region && item.kind === listing.kind && item.deal === listing.deal && item.period === listing.period);
      const freshRelevant = relevant.filter(item => validTime(item.lastSeen || item.observedAt) && Date.parse(item.lastSeen || item.observedAt) > Date.parse(watch.createdAt));
      if (freshRelevant.length && new Set(relevant.map(item => item.currency)).size > 1) {
        const newest = freshRelevant.reduce((a, b) => Date.parse(a.lastSeen || a.observedAt) > Date.parse(b.lastSeen || b.observedAt) ? a : b);
        try { groupingQuote = await quoteFor(kyivDate(newest.lastSeen || newest.observedAt)); } catch { /* Do not invent conversion rates for duplicate grouping. */ }
      }
      const candidates = candidatesFor(listing, relevant, groupingQuote, duplicateRules);
      let chosen = null;
      let conversionFailed = false;
      for (const candidate of candidates) {
        const observedAt = candidate.lastSeen || candidate.observedAt;
        if (candidate.status === 'inactive' || !validTime(observedAt) || Date.parse(observedAt) <= Date.parse(watch.createdAt) || typeof candidate.price !== 'number' || !Number.isFinite(candidate.price) || candidate.price < 0 || !CURRENCIES.has(candidate.currency)) continue;
        let converted = candidate.price;
        let usedFXasOf = null;
        if (candidate.currency !== watch.currency) {
          try {
            const quote = await quoteFor(kyivDate(observedAt));
            converted = candidate.price * quote.rates[candidate.currency] / quote.rates[watch.currency];
            usedFXasOf = quote.asOf;
          } catch { conversionFailed = true; continue; }
        }
        if (!Number.isFinite(converted)) { conversionFailed = true; continue; }
        if (!chosen || converted < chosen.converted) chosen = { candidate, converted, usedFXasOf };
      }
      if (conversionFailed) {
        const message = 'Не удалось сравнить часть цен: нет официального курса НБУ на дату наблюдения. Порог не проверялся по этим ценам.';
        setCheckError(watch.id, message); result.errors.push({ id: watch.id, message });
      }
      else if (chosen && checks[String(watch.id)]?.lastError?.startsWith('Не удалось сравнить часть цен:')) setCheckError(watch.id, null);
      if (!chosen || chosen.converted > watch.targetPrice + Math.max(1e-8, Math.abs(watch.targetPrice) * Number.EPSILON * 4)) continue;
      const original = chosen.candidate;
      const duplicate = Number(original.id) !== Number(watch.id);
      const format = value => new Intl.NumberFormat('ru-UA', { maximumFractionDigits: 2 }).format(value);
      const convertedMessage = chosen.usedFXasOf ? ` ≈ ${format(chosen.converted)} ${watch.currency} по НБУ на ${chosen.usedFXasOf}` : '';
      events.push({ type: 'targetprice', listingId: Number(watch.id), eventKey: `target:${watch.id}:${watch.createdAt}:${watch.targetPrice}:${watch.currency}`, message: `${duplicate ? 'Связанное объявление' : 'Цена наблюдаемого объявления'}: ${format(original.price)} ${original.currency}${convertedMessage}. Порог ${format(watch.targetPrice)} ${watch.currency} достигнут.`, price: original.price, currency: original.currency, targetPrice: watch.targetPrice, targetCurrency: watch.currency, comparedPrice: chosen.converted, usedFXasOf: chosen.usedFXasOf, observedListingId: original.id, observedAt: original.lastSeen || original.observedAt });
    }
    if (events.length && !closed) {
      const saved = await preferences.notify(events);
      result.notifications = Array.isArray(saved?.notifications) ? saved.notifications : [];
      result.triggered = result.notifications.length;
      if (result.notifications.length) {
        const enriched = result.notifications.map(notification => ({ ...events.find(event => Number(event.listingId) === Number(notification.listingId) && event.message === notification.message), ...notification }));
        try { await notify(enriched); }
        catch { const message = 'Уведомление сохранено в приложении, но системное сообщение показать не удалось.'; result.errors.push({ id: null, message }); lastError = message; }
      }
    }
    if (result.errors.length) lastError = result.errors[0].message;
    else if (lastError?.startsWith('Не удалось сравнить часть цен:')) lastError = null;
    persist();
    return result;
  }
  function evaluate() {
    const operation = evaluationTail.then(runEvaluation);
    evaluationTail = operation.catch(() => {});
    return operation;
  }
  async function refreshListing(listing) {
    let observed;
    if (listing.source === 'domria') {
      if (typeof getRia !== 'function') throw monitorError('Не настроен источник DIM.RIA.');
      const ria = await getRia();
      if (!ria || typeof ria.info !== 'function') throw monitorError('Не настроен источник DIM.RIA.');
      observed = await ria.info(listing.externalId, { kind: listing.kind, cityName: listing.city, regionName: listing.region, observedAt: new Date(now()).toISOString() });
      if (observed?.externalId !== undefined && String(observed.externalId) !== String(listing.externalId)) throw monitorError('Источник вернул другое объявление.');
    } else {
      const expected = canonicalUrl(listing.url);
      if (!expected || !webScanner || typeof webScanner.scan !== 'function') throw monitorError('Для этого источника нет доступного публичного URL или сканера.');
      const response = await webScanner.scan({ urls: [listing.url], cityName: listing.city, regionName: listing.region, maxPages: 1, followLinks: false });
      const matches = (Array.isArray(response?.records) ? response.records : []).filter(record => canonicalUrl(record.url) === expected);
      if (typeof listing.source === 'string' && listing.source.startsWith('web:')) {
        if (listing.externalId === undefined || listing.externalId === null || listing.externalId === '') throw monitorError('У веб-объявления нет устойчивого исходного ID. Автоматически связать цену только по URL нельзя.');
        const identified = matches.filter(record => record.externalId !== undefined && record.externalId !== null && String(record.externalId) === String(listing.externalId));
        if (identified.length === 1) observed = identified[0];
        else throw monitorError('Исходный ID веб-объявления изменился или не подтверждён. Новая цена не записана в прежнюю историю.');
      }
      else if (matches.length === 1) observed = matches[0];
      else if (matches.length > 1 && listing.externalId !== undefined && listing.externalId !== null) {
        const identified = matches.filter(record => String(record.externalId) === String(listing.externalId));
        if (identified.length === 1) observed = identified[0];
      }
      if (!observed) throw monitorError(matches.length ? 'На странице несколько объектов; нужное объявление не удалось однозначно определить.' : 'Цена наблюдаемого объекта не найдена в поддерживаемой открытой разметке.');
    }
    if (!observed || typeof observed !== 'object' || typeof observed.price !== 'number' || !Number.isFinite(observed.price) || observed.price < 0 || !CURRENCIES.has(observed.currency) || observed.kind !== listing.kind || observed.deal !== listing.deal || (observed.period || 'total') !== listing.period) throw monitorError('Источник не подтвердил корректную цену наблюдаемого объекта.');
    const record = { ...observed, source: listing.source, externalId: listing.externalId, url: listing.url || observed.url, observedAt: new Date(now()).toISOString() };
    await ingest([record]);
  }
  function check({ id } = {}) {
    if (closed) return Promise.reject(monitorError('Монитор закрыт.', 503));
    if (running) return Promise.reject(monitorError('Проверка наблюдаемых объектов уже выполняется.', 409));
    const watches = activeWatches();
    let selected;
    if (id !== undefined) {
      const parsed = validId(id);
      if (!parsed) return Promise.reject(monitorError('Некорректный ID наблюдения.'));
      selected = watches.filter(watch => Number(watch.id) === parsed);
      if (!selected.length) return Promise.reject(monitorError('Активное наблюдение не найдено.', 404));
    } else {
      selected = Array.from({ length: Math.min(10, watches.length) }, (_, index) => watches[(cursor + index) % watches.length]);
      if (watches.length) cursor = (cursor + selected.length) % watches.length;
    }
    running = true;
    pending = Promise.resolve().then(async () => {
      const result = { checked: 0, updated: 0, errors: [], notifications: [] };
      lastRun = new Date(now()).toISOString(); lastError = null;
      for (const watch of selected) {
        if (closed) break;
        result.checked++;
        checks[String(watch.id)] = { lastChecked: new Date(now()).toISOString(), lastError: null };
        try {
          const listing = listingFor(watch.id);
          if (!listing) throw monitorError('Наблюдаемое объявление отсутствует в хранилище.');
          await refreshListing(listing);
          result.updated++;
        } catch (error) {
          const message = messageOf(error);
          setCheckError(watch.id, message); result.errors.push({ id: watch.id, message });
        }
      }
      const evaluation = await evaluate();
      result.notifications = evaluation.notifications;
      result.errors.push(...evaluation.errors);
      if (result.errors.length) lastError = result.errors[0].message;
      persist();
      return result;
    }).finally(() => { running = false; pending = null; if (!closed) schedule(); }).then(result => ({ ...result, status: status() }));
    return pending;
  }
  function schedule() {
    if (timer !== null) clearTimer(timer);
    timer = null; nextRun = null;
    if (closed || !activeWatches().length) return status();
    const wait = interval() * 60000;
    nextRun = new Date(now() + wait).toISOString();
    timer = setTimer(() => { timer = null; nextRun = null; if (!closed) check().catch(() => { lastError = 'Автоматическая проверка не завершилась.'; if (!closed) schedule(); }); }, wait);
    timer?.unref?.();
    return status();
  }
  async function close() {
    closed = true;
    if (timer !== null) clearTimer(timer);
    timer = null; nextRun = null;
    if (pending) await pending.catch(() => {});
    await evaluationTail;
    persist();
  }
  return { status, check, evaluate, schedule, close };
}

module.exports = { createPriceMonitor, canonicalUrl };

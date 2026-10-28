'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomUUID, randomBytes } = require('node:crypto');

const TYPES = new Set(['pricedrop', 'newmatch', 'cheaperduplicate', 'targetprice', 'watcherror', 'currencychange']);
const FORBIDDEN = new Set(['__proto__', 'prototype', 'constructor']);
const MAX_EVENT_KEYS = 2000;
const MAX_FILE_BYTES = 16 * 1024 * 1024;
const empty = () => ({ favorites: [], notes: {}, notifications: [], savedSearches: [], watches: [], duplicateRules: { sameGroups: [], differentPairs: [] } });
const clone = (value) => JSON.parse(JSON.stringify(value));
const plain = (value) => value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value));
function fail(message) { const error = new Error(message); error.statusCode = 400; throw error; }
function id(value) {
  if (typeof value === 'string' && /^[1-9]\d*$/u.test(value)) value = Number(value);
  if (!Number.isSafeInteger(value) || value <= 0) fail('Требуется положительный ID объявления.');
  return value;
}
function text(value, name, max, allowEmpty = false, preserveWhitespace = false) {
  if (typeof value !== 'string' || value.length > max || /\u0000/u.test(value)) fail(`${name}: требуется текст длиной до ${max} символов.`);
  const result = preserveWhitespace ? value : value.trim();
  if (!allowEmpty && !result.trim()) fail(`${name}: текст не должен быть пустым.`);
  return result;
}
function key(value, name = 'ID') {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/u.test(value)) fail(`${name}: недопустимый идентификатор.`);
  return value;
}
function target(value) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1e12) fail('Порог цены должен быть числом от 0 до 1000000000000.');
  return value;
}
function currency(value) {
  if (!['USD', 'UAH', 'EUR'].includes(value)) fail('Для наблюдения цены выберите валюту USD, UAH или EUR.');
  return value;
}
function objectIds(value, minimum, maximum) {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) fail(`Нужно от ${minimum} до ${maximum} ID объявлений.`);
  const ids = value.map(id);
  if (new Set(ids).size !== ids.length) fail('ID объявлений не должны повторяться.');
  return ids.sort((left, right) => left - right);
}
function duplicateConflict(rules) {
  return rules.differentPairs.some(([left, right]) => rules.sameGroups.some((group) => group.includes(left) && group.includes(right)));
}
function filters(value) {
  if (!plain(value) || Object.keys(value).length > 50) fail('Фильтры поиска должны быть обычным объектом до 50 полей.');
  const result = {};
  const scalar = (part) => {
    if (part === null || typeof part === 'boolean') return part;
    if (typeof part === 'number' && Number.isFinite(part) && Math.abs(part) <= 1e12) return part;
    if (typeof part === 'string' && part.length <= 1000 && !/\u0000/u.test(part)) return part;
    fail('Фильтры содержат недопустимое значение.');
  };
  for (const [name, valuePart] of Object.entries(value)) {
    if (FORBIDDEN.has(name) || name.length > 80) fail('Недопустимое имя поля фильтра.');
    if (Array.isArray(valuePart)) {
      if (valuePart.length > 100) fail('Слишком длинный список в фильтрах.');
      result[name] = valuePart.map(scalar);
    } else result[name] = scalar(valuePart);
  }
  if (Buffer.byteLength(JSON.stringify(result), 'utf8') > 12000) fail('Сохранённый поиск слишком большой.');
  return result;
}
function date(value, now) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/u.test(value)) fail('Недопустимое время уведомления.');
  const parts = value.slice(0, 10).split('-').map(Number);
  const milliseconds = Date.parse(value);
  if (parts[0] < 1900 || parts[1] < 1 || parts[1] > 12 || parts[2] < 1 || parts[2] > new Date(Date.UTC(parts[0], parts[1], 0)).getUTCDate() || !Number.isFinite(milliseconds) || milliseconds > now + 300000) fail('Недопустимое время уведомления.');
  return new Date(milliseconds).toISOString();
}
function requireListing(listingId, callback) {
  if (typeof callback !== 'function') fail('Для изменения избранного или заметки нужна проверка объявления.');
  const found = callback(listingId);
  if (found && typeof found.then === 'function') fail('Проверка объявления должна быть синхронной.');
  if (!found) fail('Объявление не найдено.');
}
function searchValidation(value, callback) {
  if (typeof callback !== 'function') fail('Для сохранения поиска нужна проверка фильтров.');
  const result = callback(clone(value));
  if (result === false || result && typeof result.then === 'function') fail('Фильтры поиска не прошли синхронную проверку.');
}

function createPreferences(file) {
  if (typeof file !== 'string' || !file) fail('Требуется путь к файлу настроек.');
  const inMemory = file === ':memory:';
  const filename = inMemory ? null : path.resolve(file);
  if (!inMemory) fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
  let state = empty();
  let eventKeys = [];

  function readLoaded() {
    if (inMemory) return;
    try {
      if (fs.statSync(filename).size > MAX_FILE_BYTES) return;
      const loaded = JSON.parse(fs.readFileSync(filename, 'utf8'));
      if (!plain(loaded) || loaded.format !== undefined && loaded.format !== 'domtrack-preferences' || loaded.version !== undefined && loaded.version !== 1) return;
      const source = plain(loaded.preferences) ? loaded.preferences : loaded;
      if (!plain(source)) return;
      const restored = empty();
      for (const value of Array.isArray(source.favorites) ? source.favorites : []) {
        try { const item = id(value); if (!restored.favorites.includes(item) && restored.favorites.length < 500) restored.favorites.push(item); } catch { /* Ignore corrupt individual entries. */ }
      }
      if (plain(source.notes)) for (const [name, value] of Object.entries(source.notes)) {
        if (Object.keys(restored.notes).length >= 500) break;
        try { const listingId = id(name); const note = text(value, 'Заметка', 4000, true, true); if (note.trim()) restored.notes[String(listingId)] = note; } catch { /* Ignore corrupt individual entries. */ }
      }
      for (const search of Array.isArray(source.savedSearches) ? source.savedSearches : []) {
        if (restored.savedSearches.length >= 20) break;
        try {
          if (!plain(search)) continue;
          const searchId = key(search.id);
          if (!restored.savedSearches.some((item) => item.id === searchId)) restored.savedSearches.push({ id: searchId, name: text(search.name, 'Название поиска', 120), filters: filters(search.filters) });
        } catch { /* Ignore unsafe or malformed searches. */ }
      }
      for (const watch of Array.isArray(source.watches) ? source.watches : []) {
        if (restored.watches.length >= 100) break;
        try {
          if (!plain(watch) || watch.active !== true) continue;
          const listingId = id(watch.id);
          if (!restored.watches.some((item) => item.id === listingId)) restored.watches.push({ id: listingId, active: true, targetPrice: target(watch.targetPrice), currency: currency(watch.currency), createdAt: date(watch.createdAt, Date.now()) });
        } catch { /* Ignore unsafe or malformed watches. */ }
      }
      if (plain(source.duplicateRules)) {
        for (const pair of Array.isArray(source.duplicateRules.differentPairs) ? source.duplicateRules.differentPairs : []) {
          if (restored.duplicateRules.differentPairs.length >= 1000) break;
          try {
            const validated = objectIds(pair, 2, 2);
            if (!restored.duplicateRules.differentPairs.some((item) => item[0] === validated[0] && item[1] === validated[1])) restored.duplicateRules.differentPairs.push(validated);
          } catch { /* Ignore malformed cannot-link pairs. */ }
        }
        for (const group of Array.isArray(source.duplicateRules.sameGroups) ? source.duplicateRules.sameGroups : []) {
          if (restored.duplicateRules.sameGroups.length >= 100) break;
          try {
            const validated = objectIds(group, 2, 20);
            if (restored.duplicateRules.differentPairs.some(([left, right]) => validated.includes(left) && validated.includes(right))) continue;
            if (!restored.duplicateRules.sameGroups.some((item) => JSON.stringify(item) === JSON.stringify(validated))) restored.duplicateRules.sameGroups.push(validated);
          } catch { /* Ignore unsafe or contradictory same-object groups. */ }
        }
      }
      for (const notification of Array.isArray(source.notifications) ? source.notifications : []) {
        if (restored.notifications.length >= 200) break;
        try {
          if (!plain(notification) || !TYPES.has(notification.type)) continue;
          const notificationId = key(notification.id);
          if (restored.notifications.some((item) => item.id === notificationId)) continue;
          restored.notifications.push({ id: notificationId, type: notification.type, message: text(notification.message, 'Уведомление', 1000), createdAt: date(notification.createdAt, Date.now()), ...(notification.listingId === undefined ? {} : { listingId: id(notification.listingId) }), read: notification.read === true });
        } catch { /* Ignore corrupt notifications. */ }
      }
      state = restored;
      eventKeys = [...new Set((Array.isArray(loaded.eventKeys) ? loaded.eventKeys : []).filter((item) => typeof item === 'string' && item.length > 0 && item.length <= 300 && !/\u0000/u.test(item)))].slice(-MAX_EVENT_KEYS);
    } catch { /* Missing or damaged files start with an empty safe state. */ }
  }
  readLoaded();

  function persist(next, nextKeys) {
    if (inMemory) return;
    const serialized = JSON.stringify({ format: 'domtrack-preferences', version: 1, preferences: next, eventKeys: nextKeys }, null, 2);
    if (Buffer.byteLength(serialized, 'utf8') > MAX_FILE_BYTES) throw new Error('Файл пользовательских настроек слишком большой.');
    const temporary = filename + '.tmp-' + process.pid + '-' + randomBytes(8).toString('hex');
    let descriptor;
    try {
      descriptor = fs.openSync(temporary, 'wx', 0o600);
      fs.writeFileSync(descriptor, serialized, 'utf8');
      fs.fsyncSync(descriptor);
      fs.closeSync(descriptor); descriptor = undefined;
      fs.renameSync(temporary, filename);
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor);
      try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  }

  function get() { return clone(state); }

  function update(payload, { hasListing, validateFilters } = {}) {
    if (!plain(payload)) fail('Ожидается объект изменений пользовательских настроек.');
    const allowed = new Set(['favorite', 'note', 'markAllRead', 'saveSearch', 'removeSearchId', 'watch', 'sameObjects', 'differentObjects', 'clearDuplicateRules']);
    if (Object.keys(payload).some((name) => !allowed.has(name))) fail('Неизвестное действие с пользовательскими настройками.');
    const next = clone(state);
    if (Object.hasOwn(payload, 'clearDuplicateRules')) {
      if (typeof payload.clearDuplicateRules !== 'boolean') fail('clearDuplicateRules должен быть true или false.');
      if (payload.clearDuplicateRules) next.duplicateRules = { sameGroups: [], differentPairs: [] };
    }
    if (Object.hasOwn(payload, 'sameObjects')) {
      const ids = objectIds(payload.sameObjects, 2, 20);
      for (const listingId of ids) requireListing(listingId, hasListing);
      if (!next.duplicateRules.sameGroups.some((group) => JSON.stringify(group) === JSON.stringify(ids))) {
        if (next.duplicateRules.sameGroups.length >= 100) fail('Можно сохранить не более 100 групп одного объекта.');
        next.duplicateRules.sameGroups.push(ids);
      }
    }
    if (Object.hasOwn(payload, 'differentObjects')) {
      const pair = objectIds(payload.differentObjects, 2, 2);
      for (const listingId of pair) requireListing(listingId, hasListing);
      if (!next.duplicateRules.differentPairs.some((item) => item[0] === pair[0] && item[1] === pair[1])) {
        if (next.duplicateRules.differentPairs.length >= 1000) fail('Можно сохранить не более 1000 пар разных объектов.');
        next.duplicateRules.differentPairs.push(pair);
      }
    }
    if (duplicateConflict(next.duplicateRules)) fail('Эти объявления уже отмечены и как один объект, и как разные. Сначала очистите противоречащее правило.');
    if (Object.hasOwn(payload, 'favorite')) {
      if (!plain(payload.favorite) || typeof payload.favorite.active !== 'boolean') fail('favorite должен содержать id и active:boolean.');
      const listingId = id(payload.favorite.id);
      requireListing(listingId, hasListing);
      if (payload.favorite.active && !next.favorites.includes(listingId)) {
        if (next.favorites.length >= 500) fail('Можно сохранить не более 500 избранных объявлений.');
        next.favorites.push(listingId);
      } else if (!payload.favorite.active) next.favorites = next.favorites.filter((item) => item !== listingId);
    }
    if (Object.hasOwn(payload, 'note')) {
      if (!plain(payload.note)) fail('note должен содержать id и text.');
      const listingId = id(payload.note.id);
      requireListing(listingId, hasListing);
      const note = text(payload.note.text, 'Заметка', 4000, true, true);
      if (note.trim()) {
        if (!Object.hasOwn(next.notes, String(listingId)) && Object.keys(next.notes).length >= 500) fail('Можно сохранить не более 500 заметок.');
        next.notes[String(listingId)] = note;
      } else delete next.notes[String(listingId)];
    }
    if (Object.hasOwn(payload, 'watch')) {
      if (!plain(payload.watch) || typeof payload.watch.active !== 'boolean') fail('watch должен содержать id и active:boolean.');
      const listingId = id(payload.watch.id);
      requireListing(listingId, hasListing);
      const index = next.watches.findIndex((item) => item.id === listingId);
      if (!payload.watch.active) next.watches = next.watches.filter((item) => item.id !== listingId);
      else {
        if (index < 0 && next.watches.length >= 100) fail('Можно наблюдать не более 100 объявлений.');
        const previous = index < 0 ? null : next.watches[index];
        const nextWatch = {
          id: listingId, active: true,
          targetPrice: target(payload.watch.targetPrice === undefined ? previous?.targetPrice : payload.watch.targetPrice),
          currency: currency(payload.watch.currency ?? previous?.currency),
          createdAt: previous?.createdAt ?? new Date().toISOString()
        };
        if (index < 0) next.watches.push(nextWatch);
        else next.watches[index] = nextWatch;
      }
    }
    if (Object.hasOwn(payload, 'markAllRead')) {
      if (typeof payload.markAllRead !== 'boolean') fail('markAllRead должен быть true или false.');
      if (payload.markAllRead) next.notifications = next.notifications.map((item) => ({ ...item, read: true }));
    }
    if (Object.hasOwn(payload, 'removeSearchId')) {
      const searchId = key(payload.removeSearchId, 'ID поиска');
      next.savedSearches = next.savedSearches.filter((item) => item.id !== searchId);
    }
    if (Object.hasOwn(payload, 'saveSearch')) {
      if (!plain(payload.saveSearch)) fail('saveSearch должен содержать name и filters.');
      const name = text(payload.saveSearch.name, 'Название поиска', 120);
      const searchFilters = filters(payload.saveSearch.filters);
      searchValidation(searchFilters, validateFilters);
      if (next.savedSearches.length >= 20) fail('Можно сохранить не более 20 поисков.');
      next.savedSearches.push({ id: 'search-' + randomUUID(), name, filters: searchFilters });
    }
    persist(next, eventKeys);
    state = next;
    return get();
  }

  function notify(events) {
    if (!Array.isArray(events) || events.length > 500) fail('Ожидается массив до 500 уведомлений.');
    const now = Date.now();
    const createdAt = new Date(now).toISOString();
    const validated = events.map((event) => {
      if (!plain(event) || !TYPES.has(event.type)) fail('Недопустимый тип уведомления.');
      const eventKey = event.eventKey === undefined ? null : text(event.eventKey, 'Ключ события', 300);
      return {
        eventKey,
        notification: { id: event.id === undefined ? randomUUID() : key(event.id, 'ID уведомления'), type: event.type, message: text(event.message, 'Уведомление', 1000), createdAt: event.createdAt === undefined ? createdAt : date(event.createdAt, now), ...(event.listingId === undefined ? {} : { listingId: id(event.listingId) }), read: false }
      };
    });
    const keys = new Set(eventKeys);
    const ids = new Set(state.notifications.map((item) => item.id));
    const added = [];
    let duplicates = 0;
    for (const event of validated) {
      if (ids.has(event.notification.id) || event.eventKey !== null && keys.has(event.eventKey)) { duplicates++; continue; }
      ids.add(event.notification.id);
      if (event.eventKey !== null) keys.add(event.eventKey);
      added.push(event.notification);
    }
    if (added.length) {
      const next = { ...state, notifications: [...added].reverse().concat(state.notifications).slice(0, 200) };
      const nextKeys = [...keys].slice(-MAX_EVENT_KEYS);
      persist(next, nextKeys);
      state = next; eventKeys = nextKeys;
    }
    return { added: added.length, duplicates, notifications: clone(added) };
  }

  return { get, update, notify, close() {} };
}

module.exports = { createPreferences };

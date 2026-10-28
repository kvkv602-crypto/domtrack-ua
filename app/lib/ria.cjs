'use strict';

const API_ORIGIN = 'https://developers.ria.com';
const HOUR_MS = 60 * 60 * 1000;
const MONTH_MS = 30 * 24 * HOUR_MS;
const MAX_BODY_BYTES = 5 * 1024 * 1024;
const sharedLimits = new Map();

class RiaError extends Error {
  constructor(message, code = 'RIA_ERROR', options = {}) {
    super(message);
    this.name = 'RiaError';
    this.code = code;
    this.statusCode = code === 'RIA_RATE_LIMIT' ? 429 : ['RIA_SETTINGS', 'RIA_KEY_REQUIRED'].includes(code) ? 400 : ['RIA_TIMEOUT', 'RIA_DEADLINE'].includes(code) ? 504 : 502;
    if (options.status) { this.status = options.status; this.statusCode = options.status; }
    if (options.retryAt) this.retryAt = options.retryAt;
  }
}
class RiaDataError extends RiaError {
  constructor(message) { super(message, 'RIA_DATA'); this.name = 'RiaDataError'; }
}
class RiaRateLimitError extends RiaError {
  constructor(retryAt) {
    super('Лимит запросов DIM.RIA исчерпан. Повторите обновление позднее.', 'RIA_RATE_LIMIT', { retryAt });
    this.name = 'RiaRateLimitError';
  }
}

function numeric(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value.trim())) return null;
  const result = Number(value.trim());
  return Number.isFinite(result) ? result : null;
}
function idOf(value) {
  const n = numeric(value);
  return Number.isSafeInteger(n) && n > 0 ? String(n) : null;
}
function textOf(value, limit = 160) {
  return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, limit) : '';
}
function isoDate(value) {
  const d = new Date(value === undefined ? Date.now() : value);
  if (!Number.isFinite(d.getTime())) throw new RiaDataError('Некорректное время наблюдения объявления.');
  return d.toISOString();
}
function listingUrl(value, id) {
  const fallback = `https://dom.ria.com/uk/realty-${id}.html`;
  if (typeof value !== 'string' || value.length > 2048 || /[\\\u0000-\u0020]/.test(value)) return fallback;
  try {
    const candidate = new URL(value.startsWith('/') || /^https?:/i.test(value) ? value : `/uk/${value}`, 'https://dom.ria.com');
    if (candidate.origin !== 'https://dom.ria.com' || candidate.username || candidate.password || candidate.search || candidate.hash) return fallback;
    if (!new RegExp(`^/(?:uk/)?realty-[a-zA-Z0-9_-]*${id}\\.html$`).test(candidate.pathname)) return fallback;
    return candidate.href;
  } catch { return fallback; }
}

/** Select only listing data needed by the price-history store. */
function normalizeRiaRecord(payload, context = {}) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new RiaDataError('DIM.RIA вернул некорректное объявление.');
  const operation = numeric(payload.advert_type_id);
  if (operation !== 1) return null;
  const type = numeric(payload.realty_type_id);
  const kind = type === 2 ? 'apartment' : type === 5 ? 'house' : null;
  if (!kind || (context.kind && context.kind !== 'both' && context.kind !== kind)) return null;
  if (numeric(payload.advert_publish_type) !== 1) throw new RiaDataError('Активность объявления не подтверждена. Его статус и цена не изменены.');
  const externalId = idOf(payload.realty_id);
  if (!externalId) throw new RiaDataError('В объявлении отсутствует корректный идентификатор.');
  const currencies = { '$': 'USD', '€': 'EUR', 'грн': 'UAH' };
  const originalCurrency = textOf(payload.currency_type, 8);
  const currency = Object.hasOwn(currencies, originalCurrency) ? currencies[originalCurrency] : null;
  if (!currency) throw new RiaDataError('Не указана исходная валюта цены объявления.');
  const price = numeric(payload.price_total);
  if (price === null || price <= 0) throw new RiaDataError('Нет цены за весь объект (price_total). Цена за м² не подменяет цену объекта.');
  const city = textOf(payload.city_name || context.cityName || context.city, 120);
  const region = textOf(payload.state_name || context.regionName || context.region, 120);
  if (!city) throw new RiaDataError('В объявлении не указан город.');
  const record = {
    source: 'domria', externalId,
    url: listingUrl(payload.beautiful_url, externalId),
    title: kind === 'apartment' ? 'Квартира' : 'Будинок',
    city, region, deal: 'sale', kind, price, currency,
    observedAt: isoDate(context.observedAt),
    status: context.status === 'inactive' ? 'inactive' : 'active',
    period: 'total'
  };
  const sellerCode = numeric(payload.characteristics_values?.['1437']);
  record.sellerType = sellerCode === 1436 ? 'owner' : sellerCode === 1434 ? 'agent' : sellerCode === 1473 || sellerCode === 1506 ? 'developer' : 'unknown';
  const sellerName = textOf(payload.user?.name, 600).replace(/<[^>]*>/g, ' ').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, 150);
  if (sellerName) record.sellerName = sellerName;
  const numericFields = { total_square_meters: 'area', rooms_count: 'rooms', floor: 'floor', floors_count: 'floorsTotal' };
  for (const [source, target] of Object.entries(numericFields)) {
    const value = numeric(payload[source]);
    if (value !== null && value >= 0 && (target === 'area' ? value > 0 : Number.isSafeInteger(value))) record[target] = value;
  }
  const street = textOf(payload.street_name, 160);
  const showBuilding = payload.is_show_building_no === true || numeric(payload.is_show_building_no) === 1;
  const building = showBuilding ? textOf(payload.building_number_str, 40) : '';
  if (street) record.address = [street, building].filter(Boolean).join(', ');
  const district=textOf(payload.admin_district_name_uk||payload.district_name,200);
  if(district)record.district=district;
  for(const [key,min,max] of [['latitude',-90,90],['longitude',-180,180]]){
    const v=numeric(payload[key]);if(v!==null&&v>=min&&v<=max)record[key]=v;
  }
  record.title = [record.title, city, record.rooms ? `${record.rooms} кімн.` : '', record.area ? `${record.area} м²` : ''].filter(Boolean).join(' · ');
  return record;
}

function normalizeSettings(settings = {}) {
  const stateId = idOf(settings.stateId);
  const cityId = settings.cityId === undefined || settings.cityId === '' || settings.cityId === null ? null : idOf(settings.cityId);
  if (!stateId || (settings.cityId !== undefined && settings.cityId !== '' && settings.cityId !== null && !cityId)) throw new RiaError('Выберите корректную область и город.', 'RIA_SETTINGS');
  const kind = settings.kind || 'apartment';
  if (!['apartment', 'house', 'both'].includes(kind)) throw new RiaError('Выберите квартиры, дома или оба типа.', 'RIA_SETTINGS');
  const requested = settings.maxListings === undefined ? 20 : numeric(settings.maxListings);
  if (!Number.isSafeInteger(requested) || requested < 1 || requested > 20) throw new RiaError('За одно обновление можно проверить от 1 до 20 объявлений.', 'RIA_SETTINGS');
  return { ...settings, stateId, cityId, kind, maxListings: requested };
}
function errorSummary(error, externalId) {
  return { code: error instanceof RiaError ? error.code : 'RIA_ERROR', message: error instanceof RiaError ? error.message : 'Не удалось обработать объявление DIM.RIA.', ...(error instanceof RiaError ? { statusCode: error.statusCode, ...(error.retryAt ? { retryAt: error.retryAt } : {}) } : {}), ...(externalId ? { externalId } : {}) };
}

function createRiaClient(options = {}) {
  const { apiKey, fetchImpl = globalThis.fetch } = options;
  if (typeof apiKey !== 'string' || !apiKey.trim() || apiKey.length > 512) throw new RiaError('Добавьте API-ключ DIM.RIA.', 'RIA_KEY_REQUIRED');
  if (typeof fetchImpl !== 'function') throw new RiaError('В этой среде недоступны сетевые запросы.', 'RIA_FETCH_UNAVAILABLE');
  const requestBudget = 28;
  const monthlyBudget = options.monthlyBudget === undefined ? 100 : options.monthlyBudget;
  if (monthlyBudget !== 100 && monthlyBudget !== 1000) throw new RiaError('Лимит DIM.RIA за 30 дней должен быть 100 или 1000 запросов. Повышенный лимит укажите явно после его подтверждения.', 'RIA_SETTINGS');
  const delayMs = Math.max(0, Math.min(60000, Number.isFinite(options.delayMs) ? options.delayMs : 2100));
  const timeoutMs = Math.max(1, Math.min(20000, Number.isFinite(options.timeoutMs) ? options.timeoutMs : 20000));
  const now = typeof options.now === 'function' ? options.now : Date.now;
  const sleep = typeof options.sleepImpl === 'function' ? options.sleepImpl : ms => new Promise(resolve => setTimeout(resolve, ms));
  let state = sharedLimits.get(apiKey);
  if (!state) {
    const saved = options.rateState || { timestamps: options.initialRequestTimestamps };
    state = { timestamps: Array.isArray(saved.timestamps) ? saved.timestamps.filter(t => Number.isFinite(t) && t > now() - MONTH_MS && t <= now()).sort((a, b) => a - b) : [], blockedUntil: Number.isFinite(saved.blockedUntil) ? saved.blockedUntil : 0, lastRequestAt: 0, queue: Promise.resolve() };
    sharedLimits.set(apiKey, state);
  }
  let requestCount = 0;
  function prune(current) { state.timestamps = state.timestamps.filter(t => t > current - MONTH_MS); }
  function snapshot() {
    const current = now();
    prune(current);
    const hourly = state.timestamps.filter(t => t > current - HOUR_MS);
    const retryAt = Math.max(state.blockedUntil, hourly.length >= requestBudget ? hourly[hourly.length - requestBudget] + HOUR_MS : 0);
    const monthlyRetryAt = state.timestamps.length >= monthlyBudget ? state.timestamps[state.timestamps.length - monthlyBudget] + MONTH_MS : 0;
    return { timestamps: [...state.timestamps], blockedUntil: state.blockedUntil, used: hourly.length, remaining: Math.max(0, requestBudget - hourly.length), limit: requestBudget, windowMs: HOUR_MS, nextAvailableAt: retryAt > current ? retryAt : null, monthly: { used: state.timestamps.length, remaining: Math.max(0, monthlyBudget - state.timestamps.length), limit: monthlyBudget, windowMs: MONTH_MS, nextAvailableAt: monthlyRetryAt > current ? monthlyRetryAt : null } };
  }
  function checkLimit() {
    const current = snapshot();
    const retryAt = Math.max(current.nextAvailableAt || 0, current.monthly.nextAvailableAt || 0);
    if (retryAt) throw new RiaRateLimitError(retryAt);
  }
  async function parseBody(response) {
    const length = Number(response.headers?.get?.('content-length'));
    if (Number.isFinite(length) && length > MAX_BODY_BYTES) throw new RiaDataError('Ответ DIM.RIA превышает допустимый размер.');
    if (!response.body || typeof response.body.getReader !== 'function') throw new RiaDataError('DIM.RIA вернул пустой или неподдерживаемый ответ.');
    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        size += part.value.byteLength;
        if (size > MAX_BODY_BYTES) {
          await reader.cancel().catch(() => {});
          throw new RiaDataError('Ответ DIM.RIA превышает допустимый размер.');
        }
        chunks.push(part.value);
      }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    try { return JSON.parse(new TextDecoder().decode(bytes)); }
    catch { throw new RiaDataError('DIM.RIA вернул некорректный JSON.'); }
  }
  async function perform(path, params, deadlineAt) {
    if (Number.isFinite(deadlineAt) && now() >= deadlineAt) throw new RiaError('Обновление DIM.RIA остановлено после пяти минут. Уже полученные данные сохранены.', 'RIA_DEADLINE');
    checkLimit();
    const waitMs = state.lastRequestAt ? Math.max(0, delayMs - (now() - state.lastRequestAt)) : 0;
    if (Number.isFinite(deadlineAt) && now() + waitMs >= deadlineAt) throw new RiaError('Время обновления DIM.RIA истекло.', 'RIA_DEADLINE');
    if (waitMs) await sleep(waitMs);
    checkLimit();
    state.lastRequestAt = now();
    state.timestamps.push(now());
    requestCount += 1;
    if (typeof options.onRequest === 'function') {
      try { await options.onRequest([...state.timestamps]); }
      catch { throw new RiaError('Не удалось сохранить состояние лимита DIM.RIA. Запрос отменён.', 'RIA_RATE_PERSISTENCE'); }
    }
    const url = new URL(path, API_ORIGIN);
    url.searchParams.set('api_key', apiKey);
    for (const [key, value] of Object.entries(params || {})) {
      if (value === null || value === undefined) continue;
      if (Array.isArray(value)) for (const part of value) url.searchParams.append(key, String(part));
      else url.searchParams.set(key, String(value));
    }
    const controller = new AbortController();
    let timer;
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => { controller.abort(); reject(new RiaError('Время ожидания DIM.RIA истекло.', 'RIA_TIMEOUT')); }, Number.isFinite(deadlineAt) ? Math.max(1, Math.min(timeoutMs, deadlineAt - now())) : timeoutMs);
    });
    try {
      return await Promise.race([ (async () => {
        const response = await fetchImpl(url.href, { method: 'GET', headers: { Accept: 'application/json' }, redirect: 'error', cache: 'no-store', signal: controller.signal });
        if (!response || typeof response.status !== 'number') throw new RiaDataError('DIM.RIA вернул некорректный HTTP-ответ.');
        if (response.status === 429) {
          const retryAfter = response.headers?.get?.('retry-after');
          const seconds = typeof retryAfter === 'string' && /^\d+$/.test(retryAfter) ? Number(retryAfter) : null;
          state.blockedUntil = now() + (seconds !== null && Number.isFinite(seconds) ? Math.max(1000, Math.min(HOUR_MS, seconds * 1000)) : HOUR_MS);
          throw new RiaRateLimitError(state.blockedUntil);
        }
        if (response.status === 401 || response.status === 403) throw new RiaError('DIM.RIA отклонил ключ или доступ к API. Проверьте настройки доступа.', 'RIA_AUTH', { status: response.status });
        if (response.status < 200 || response.status >= 300) throw new RiaError(`DIM.RIA вернул HTTP ${response.status}.`, 'RIA_HTTP', { status: response.status });
        return parseBody(response);
      })(), timeout ]);
    } catch (error) {
      if (error instanceof RiaError) throw error;
      throw new RiaError('Не удалось связаться с DIM.RIA. Проверьте интернет и доступность API.', 'RIA_NETWORK');
    } finally { clearTimeout(timer); }
  }
  function request(path, params, deadlineAt) {
    const result = state.queue.then(() => perform(path, params, deadlineAt));
    state.queue = result.catch(() => {});
    return result;
  }
  async function search(settings, page = 0) {
    const query = normalizeSettings(settings);
    if (query.kind === 'both') throw new RiaError('Для одного поискового запроса нужен один тип недвижимости.', 'RIA_SETTINGS');
    if (!Number.isInteger(page) || page < 0 || page > 99) throw new RiaError('Номер страницы DIM.RIA должен быть от 0 до 99.', 'RIA_SETTINGS');
    const sellerCodes = { owner: 1436, agent: 1434, developer: [1473, 1506] };
    const sellerFilter = Object.hasOwn(sellerCodes, query.sellerType) ? sellerCodes[query.sellerType] : undefined;
    const data = await request('/dom/search', { category: query.kind === 'house' ? 4 : 1, realty_type: query.kind === 'house' ? 5 : 2, operation_type: 1, state_id: query.stateId, city_id: query.cityId, sort: 'upd_d', page, 'characteristic[1437]': sellerFilter }, query.deadlineAt);
    if (!data || !Array.isArray(data.items) || !Number.isSafeInteger(numeric(data.count)) || numeric(data.count) < 0) throw new RiaDataError('DIM.RIA вернул неизвестный формат поисковой выдачи.');
    return { items: [...new Set(data.items.map(idOf).filter(Boolean))], count: numeric(data.count) };
  }
  async function info(id, context = {}) {
    const safeId = idOf(id);
    if (!safeId) throw new RiaError('Некорректный идентификатор объявления.', 'RIA_SETTINGS');
    return normalizeRiaRecord(await request(`/dom/info/${safeId}`, undefined, context.deadlineAt), context);
  }
  async function regions() {
    const data = await request('/dom/states', { lang_id: 4 });
    if (!Array.isArray(data)) throw new RiaDataError('DIM.RIA вернул неизвестный справочник областей.');
    return data.map(item => ({ id: idOf(item?.stateID), name: textOf(item?.name, 120) })).filter(item => item.id && item.name);
  }
  async function cities(stateId) {
    const id = idOf(stateId);
    if (!id) throw new RiaError('Выберите область.', 'RIA_SETTINGS');
    const data = await request(`/dom/cities/${id}`, { lang_id: 4 });
    if (!Array.isArray(data)) throw new RiaDataError('DIM.RIA вернул неизвестный справочник городов.');
    return data.map(item => ({ id: idOf(item?.cityID), name: textOf(item?.name, 120), stateId: idOf(item?.stateID) || id })).filter(item => item.id && item.name && item.stateId === id);
  }
  async function collect(settings) {
    const query = { ...normalizeSettings(settings), deadlineAt: now() + 5 * 60 * 1000 };
    const startCount = requestCount;
    const result = { records: [], matched: 0, processed: 0, truncated: false, requestCount: 0, errors: [], scope: 'first_page', maxListings: query.maxListings };
    const kinds = query.kind === 'both' ? ['apartment', 'house'] : [query.kind];
    const candidates = [];
    const seen = new Set();
    const refreshIds = Array.isArray(query.refreshIds) ? [...new Set(query.refreshIds.map(idOf).filter(Boolean))].slice(0, Math.max(1, Math.floor(query.maxListings / 2))) : [];
    let stopped = false;
    for (const id of refreshIds) {
      seen.add(id);
      try {
        result.processed += 1;
        const record = await info(id, { ...query, observedAt: new Date(now()).toISOString(), status: 'active' });
        if (record) result.records.push(record);
      } catch (error) {
        result.errors.push(errorSummary(error, id));
        result.truncated = true;
        if (['RIA_AUTH', 'RIA_RATE_LIMIT', 'RIA_RATE_PERSISTENCE', 'RIA_DEADLINE'].includes(error.code)) { stopped = true; break; }
      }
    }
    const remaining = query.maxListings - refreshIds.length;
    for (let i = 0; i < kinds.length; i += 1) {
      if (stopped) break;
      const limit = kinds.length === 1 ? remaining : i === 0 ? Math.ceil(remaining / 2) : Math.floor(remaining / 2);
      try {
        const found = await search({ ...query, kind: kinds[i] }, 0);
        result.matched += found.count;
        if (found.count > limit) result.truncated = true;
        candidates.push(...found.items.filter(id => !seen.has(id)).slice(0, limit).map(id => ({ id, kind: kinds[i] })));
      } catch (error) {
        result.errors.push(errorSummary(error));
        result.truncated = true;
        if (['RIA_AUTH', 'RIA_RATE_LIMIT', 'RIA_RATE_PERSISTENCE', 'RIA_DEADLINE'].includes(error.code)) { stopped = true; break; }
      }
    }
    for (const item of candidates) {
      if (stopped) break;
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      try {
        result.processed += 1;
        const record = await info(item.id, { ...query, kind: item.kind, observedAt: new Date(now()).toISOString(), status: 'active' });
        if (record) result.records.push(record);
      } catch (error) {
        result.errors.push(errorSummary(error, item.id));
        result.truncated = true;
        if (['RIA_AUTH', 'RIA_RATE_LIMIT', 'RIA_RATE_PERSISTENCE', 'RIA_DEADLINE'].includes(error.code)) break;
      }
    }
    result.requestCount = requestCount - startCount;
    return result;
  }
  return { search, info, regions, cities, collect, rateLimiter: { snapshot }, get requestCount() { return requestCount; } };
}

module.exports = { createRiaClient, normalizeRiaRecord, RiaError, RiaDataError, RiaRateLimitError };

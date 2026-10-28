'use strict';

const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');

class ValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ValidationError';
    this.statusCode = 400;
  }
}

const fail = (message) => { throw new ValidationError(message); };
const plainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const normalizeText = (value) => value.normalize('NFKC').replace(/[’‘ʼ`´]/gu, "'").replace(/[‐‑‒–—]/gu, '-').replace(/\s+/gu, ' ').trim().toLocaleLowerCase('uk');

const CITY_ALIASES = new Map([
  ['киев', 'Київ'], ['київ', 'Київ'], ['kiev', 'Київ'], ['kyiv', 'Київ'],
  ['харьков', 'Харків'], ['харків', 'Харків'], ['kharkiv', 'Харків'],
  ['львов', 'Львів'], ['львів', 'Львів'], ['lviv', 'Львів'],
  ['одесса', 'Одеса'], ['одеса', 'Одеса'], ['odesa', 'Одеса'],
  ['днепр', 'Дніпро'], ['днепропетровск', 'Дніпро'], ['дніпро', 'Дніпро'],
  ['запорожье', 'Запоріжжя'], ['запоріжжя', 'Запоріжжя'],
  ['николаев', 'Миколаїв'], ['миколаїв', 'Миколаїв'],
  ['ровно', 'Рівне'], ['рівне', 'Рівне'], ['винница', 'Вінниця'], ['вінниця', 'Вінниця'],
  ['чернигов', 'Чернігів'], ['чернігів', 'Чернігів'], ['черновцы', 'Чернівці'], ['чернівці', 'Чернівці'],
  ['кировоград', 'Кропивницький'], ['кропивницький', 'Кропивницький'],
  ['тернополь', 'Тернопіль'], ['тернопіль', 'Тернопіль'], ['луцк', 'Луцьк'], ['луцьк', 'Луцьк'],
  ['черкассы', 'Черкаси'], ['черкаси', 'Черкаси'], ['хмельницкий', 'Хмельницький'], ['хмельницький', 'Хмельницький'],
  ['сумы', 'Суми'], ['суми', 'Суми'], ['ивано-франковск', 'Івано-Франківськ'], ['івано-франківськ', 'Івано-Франківськ'],
  ['белая церковь', 'Біла Церква'], ['біла церква', 'Біла Церква'], ['кривой рог', 'Кривий Ріг'], ['кривий ріг', 'Кривий Ріг']
]);

const REGION_NAMES = [
  ['киевская', 'київська', 'Київська'], ['харьковская', 'харківська', 'Харківська'],
  ['львовская', 'львівська', 'Львівська'], ['одесская', 'одеська', 'Одеська'],
  ['днепропетровская', 'дніпропетровська', 'Дніпропетровська'], ['запорожская', 'запорізька', 'Запорізька'],
  ['николаевская', 'миколаївська', 'Миколаївська'], ['ровенская', 'рівненська', 'Рівненська'],
  ['винницкая', 'вінницька', 'Вінницька'], ['черниговская', 'чернігівська', 'Чернігівська'],
  ['черновицкая', 'чернівецька', 'Чернівецька'], ['кировоградская', 'кіровоградська', 'Кіровоградська'],
  ['тернопольская', 'тернопільська', 'Тернопільська'], ['волынская', 'волинська', 'Волинська'],
  ['черкасская', 'черкаська', 'Черкаська'], ['хмельницкая', 'хмельницька', 'Хмельницька'],
  ['сумская', 'сумська', 'Сумська'], ['ивано-франковская', 'івано-франківська', 'Івано-Франківська'],
  ['житомирская', 'житомирська', 'Житомирська'], ['полтавская', 'полтавська', 'Полтавська'],
  ['херсонская', 'херсонська', 'Херсонська'], ['закарпатская', 'закарпатська', 'Закарпатська'],
  ['донецкая', 'донецька', 'Донецька'], ['луганская', 'луганська', 'Луганська']
];
const REGION_ALIASES = new Map();
for (const [ru, uk, display] of REGION_NAMES) {
  for (const alias of [ru, uk]) {
    for (const suffix of ['', ' область', ' обл.', ' обл']) REGION_ALIASES.set(alias + suffix, display + ' область');
  }
}

function string(value, field, max, allowEmpty = false) {
  if (typeof value !== 'string') fail(`${field}: требуется строка.`);
  const result = value.normalize('NFKC').trim();
  if ((!allowEmpty && result.length === 0) || result.length > max || /[\u0000-\u001f\u007f]/u.test(result)) fail(`${field}: недопустимая длина или символы.`);
  return result;
}

function city(value) {
  const display = string(value, 'city', 160).replace(/^м\.\s*/iu, '');
  if (!display) fail('city: требуется название города.');
  return CITY_ALIASES.get(normalizeText(display)) || display;
}

function region(value) {
  const display = string(value ?? '', 'region', 160, true);
  return REGION_ALIASES.get(normalizeText(display)) || display;
}

function number(value, field, min, max, integer = false) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) fail(`${field}: недопустимое число.`);
  return Object.is(value, -0) ? 0 : value;
}

function optionalNumber(value, field, min, max, integer = false) {
  return value === undefined || value === null ? null : number(value, field, min, max, integer);
}

function enumeration(value, field, values) {
  if (!values.includes(value)) fail(`${field}: допустимые значения ${values.join(', ')}.`);
  return value;
}
function optionalText(value,field,max){return value===undefined||value===null||value===''?null:string(value,field,max);}
function contactUrl(value){const text=optionalText(value,'contactUrl',2048);if(text===null)return null;let u;try{u=new URL(text);}catch{fail('contactUrl: неверная ссылка.');}if(!['http:','https:'].includes(u.protocol)||u.username||u.password)fail('contactUrl: требуется безопасная HTTP(S)-ссылка.');return u.href;}

function timestamp(value, now) {
  const input = string(value, 'observedAt', 40);
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?(Z|[+-](\d{2}):(\d{2}))$/u.exec(input);
  if (!match) fail('observedAt: требуется ISO-дата со временем и часовым поясом.');
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  if (year < 1900 || month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(year, month, 0)).getUTCDate() || hour > 23 || minute > 59 || second > 59 || Number(match[9] || 0) > 23 || Number(match[10] || 0) > 59) fail('observedAt: недопустимая календарная дата.');
  const milliseconds = Date.parse(input);
  if (!Number.isFinite(milliseconds) || milliseconds < Date.UTC(1900, 0, 1) || milliseconds > now + 300_000) fail('observedAt: дата должна быть между 1900 годом и текущим временем + 5 минут.');
  return new Date(milliseconds).toISOString();
}

function normalizeRecord(value, now, defaultTimestamp) {
  if (!plainObject(value)) fail('Каждое наблюдение должно быть объектом.');
  const source = normalizeText(string(value.source, 'source', 120));
  const externalId = value.externalId === undefined || value.externalId === null || value.externalId === '' ? null : string(value.externalId, 'externalId', 250);
  let parsedUrl = null;
  const urlString = value.url === undefined || value.url === null ? '' : string(value.url, 'url', 4096, true);
  if (urlString) {
    try { parsedUrl = new URL(urlString); } catch { fail('url: недопустимый адрес.'); }
    if (!['https:', 'http:'].includes(parsedUrl.protocol) || parsedUrl.username || parsedUrl.password || !parsedUrl.hostname) fail('url: требуется HTTP(S)-адрес без логина и пароля.');
  } else if (externalId === null) fail('Укажите постоянный externalId или HTTP(S)-ссылку url.');
  const cityValue = city(value.city);
  const regionValue = region(value.region);
  const record = {
    source,
    externalId,
    url: parsedUrl?.href ?? null,
    title: string(value.title, 'title', 2000),
    city: cityValue,
    region: regionValue,
    deal: enumeration(value.deal, 'deal', ['sale', 'rent']),
    kind: enumeration(value.kind, 'kind', ['apartment', 'house']),
    price: number(value.price, 'price', 0, 1e12),
    currency: enumeration(value.currency, 'currency', ['UAH', 'USD', 'EUR']),
    area: optionalNumber(value.area, 'area', Number.MIN_VALUE, 1e7),
    rooms: optionalNumber(value.rooms, 'rooms', 0, 1000, true),
    address: value.address === undefined || value.address === null ? null : string(value.address, 'address', 2000, true),
    district: value.district === undefined || value.district === null ? null : string(value.district, 'district', 250, true),
    latitude: optionalNumber(value.latitude, 'latitude', -90, 90),
    longitude: optionalNumber(value.longitude, 'longitude', -180, 180),
    sellerName: optionalText(value.sellerName,'sellerName',150),
    contactPhone: optionalText(value.contactPhone,'contactPhone',40),
    contactEmail: optionalText(value.contactEmail,'contactEmail',254),
    contactUrl: contactUrl(value.contactUrl),
    floor: optionalNumber(value.floor, 'floor', -10, 1000, true),
    floorsTotal: optionalNumber(value.floorsTotal, 'floorsTotal', 1, 1000, true),
    observedAt: value.observedAt === undefined || value.observedAt === null ? defaultTimestamp : timestamp(value.observedAt, now),
    status: enumeration(value.status ?? 'active', 'status', ['active', 'inactive']),
    period: enumeration(value.period ?? (value.deal === 'rent' ? 'month' : 'total'), 'period', ['total', 'month', 'day']),
    sellerType: enumeration(value.sellerType ?? 'unknown', 'sellerType', ['owner', 'developer', 'agent', 'unknown'])
  };
  if (record.floor !== null && record.floorsTotal !== null && record.floor > record.floorsTotal) fail('floor: этаж не может быть выше этажности здания.');
  if(record.contactPhone&&(!/^\+?[\d\s()\-]+$/.test(record.contactPhone)||record.contactPhone.replace(/\D/g,'').length<5))fail('contactPhone: требуется открытый номер телефона без маски.');
  if(record.contactEmail&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(record.contactEmail))fail('contactEmail: неверный адрес email.');
  const canonical = parsedUrl === null ? null : new URL(parsedUrl.href);
  if (canonical) {
    for(const key of [...canonical.searchParams.keys()])if(/^utm(?:_|$)/i.test(key)||/^(gclid|fbclid|msclkid|yclid|_ga)$/i.test(key))canonical.searchParams.delete(key);
    canonical.searchParams.sort();
  }
  return {
    record,
    identity: JSON.stringify([source, externalId === null ? 'url' : 'id', externalId ?? canonical.href]),
    json: JSON.stringify(record)
  };
}

const tupleDiffers = (left, right) => left.price !== right.price || left.currency !== right.currency || left.period !== right.period;

function summarize(id, records) {
  const first = records[0];
  const last = records[records.length - 1];
  let previous = null;
  for (let index = records.length - 2; index >= 0; index--) {
    if (tupleDiffers(records[index], last)) { previous = records[index]; break; }
  }
  const comparable = previous && previous.currency === last.currency && previous.period === last.period;
  const comparableInitial = first.currency === last.currency && first.period === last.period;
  return {
    id,
    ...last,
    cityKey: JSON.stringify([normalizeText(last.city), normalizeText(last.region)]),
    firstSeen: first.observedAt,
    lastSeen: last.observedAt,
    observationCount: records.length,
    previousPrice: previous?.price ?? null,
    previousCurrency: previous?.currency ?? null,
    previousPeriod: previous?.period ?? null,
    changeAmount: comparable ? last.price - previous.price : null,
    changePercent: comparable && previous.price > 0 ? ((last.price - previous.price) / previous.price) * 100 : null,
    initialPrice: first.price,
    totalChangePercent: comparableInitial && first.price > 0 ? ((last.price - first.price) / first.price) * 100 : null
  };
}

function queryNumber(value, name, min, max, integer = false) {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'number' && typeof value !== 'string') fail(`${name}: недопустимое число.`);
  if (typeof value === 'string' && !/^-?(?:\d+(?:\.\d*)?|\.\d+)$/u.test(value)) fail(`${name}: недопустимое число.`);
  return number(Number(value), name, min, max, integer);
}

function queryBoolean(value, name) {
  if (value === undefined || value === null || value === '' || value === false || value === 'false' || value === '0') return false;
  if (value === true || value === 'true' || value === '1') return true;
  fail(`${name}: требуется true или false.`);
}

function createStore(dbPath) {
  if (typeof dbPath !== 'string' || !dbPath) fail('Требуется путь к базе данных.');
  if (dbPath !== ':memory:') fs.mkdirSync(path.dirname(path.resolve(dbPath)), { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec('PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;');
  db.exec(`
    CREATE TABLE IF NOT EXISTS listings (
      id INTEGER PRIMARY KEY,
      identity TEXT NOT NULL UNIQUE,
      summary_json TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS observations (
      listing_id INTEGER NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
      observed_at TEXT NOT NULL,
      record_json TEXT NOT NULL,
      PRIMARY KEY(listing_id, observed_at)
    );
    CREATE INDEX IF NOT EXISTS observations_date ON observations(observed_at);
    PRAGMA user_version=1;
  `);
  const findListing = db.prepare('SELECT id FROM listings WHERE identity=?');
  const findObservation = db.prepare('SELECT o.record_json FROM observations o JOIN listings l ON l.id=o.listing_id WHERE l.identity=? AND o.observed_at=?');
  const insertListing = db.prepare('INSERT INTO listings(identity,summary_json) VALUES(?,?)');
  const insertObservation = db.prepare('INSERT INTO observations(listing_id,observed_at,record_json) VALUES(?,?,?)');
  const historyQuery = db.prepare('SELECT record_json FROM observations WHERE listing_id=? ORDER BY observed_at ASC');
  const updateSummary = db.prepare('UPDATE listings SET summary_json=? WHERE id=?');
  const listingQuery = db.prepare('SELECT summary_json FROM listings ORDER BY id');
  let closed = false;
  function ensureOpen() { if (closed) throw new Error('Хранилище закрыто.'); }
  function allListings() { ensureOpen(); return listingQuery.all().map((row) => JSON.parse(row.summary_json)); }

  function ingest(records) {
    ensureOpen();
    if (!Array.isArray(records) || records.length > 50000) fail('Ожидается массив до 50000 наблюдений.');
    const now = Date.now();
    const defaultTimestamp = new Date(now).toISOString();
    const normalized = records.map((record) => normalizeRecord(record, now, defaultTimestamp));
    const unique = new Map();
    let duplicates = 0;
    for (const entry of normalized) {
      const observationKey = JSON.stringify([entry.identity, entry.record.observedAt]);
      const existing = unique.get(observationKey);
      if (existing && existing.json !== entry.json) fail(`Конфликт наблюдений ${entry.record.source}: одинаковое объявление и время, но разные данные.`);
      if (existing) duplicates++;
      else unique.set(observationKey, entry);
    }
    if (unique.size === 0) return { received: records.length, inserted: 0, duplicates: 0, updatedListings: 0 };
    const pending = [];
    const touched = new Set();
    let inserted = 0;
    db.exec('BEGIN IMMEDIATE');
    try {
      for (const entry of unique.values()) {
        const existing = findObservation.get(entry.identity, entry.record.observedAt);
        if (existing && existing.record_json !== entry.json) fail(`Конфликт наблюдений ${entry.record.source}: это время уже сохранено с другими данными.`);
        if (existing) duplicates++;
        else pending.push(entry);
      }
      for (const entry of pending) {
        let listing = findListing.get(entry.identity);
        if (!listing) listing = { id: Number(insertListing.run(entry.identity, '{}').lastInsertRowid) };
        insertObservation.run(listing.id, entry.record.observedAt, entry.json);
        touched.add(listing.id);
        inserted++;
      }
      for (const id of touched) {
        const history = historyQuery.all(id).map((row) => JSON.parse(row.record_json));
        updateSummary.run(JSON.stringify(summarize(id, history)), id);
      }
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
    return { received: records.length, inserted, duplicates, updatedListings: touched.size };
  }

  function list(filters = {}) {
    if (!plainObject(filters)) fail('Фильтры должны быть объектом.');
    const textFilter = (key, max = 300) => filters[key] === undefined || filters[key] === null || filters[key] === '' ? null : string(filters[key], key, max);
    const cityKey = textFilter('cityKey', 1000);
    const sourceFilter = textFilter('source', 120);
    const canonicalSource = sourceFilter === null ? null : normalizeText(sourceFilter);
    const cityFilter = textFilter('city', 160);
    const regionFilter = textFilter('region', 160);
    const districtFilter = textFilter('district', 250);
    const canonicalCity = cityFilter === null ? null : normalizeText(city(cityFilter));
    const canonicalRegion = regionFilter === null ? null : normalizeText(region(regionFilter));
    const q = textFilter('q');
    const normalizedQ = q === null ? null : normalizeText(q);
    const enumFilter = (key, values, fallback = null) => filters[key] === undefined || filters[key] === null || filters[key] === '' ? fallback : enumeration(filters[key], key, values);
    const deal = enumFilter('deal', ['sale', 'rent']);
    const kind = enumFilter('kind', ['apartment', 'house']);
    const currency = enumFilter('currency', ['UAH', 'USD', 'EUR']);
    const changes = enumFilter('changes', ['all', 'down', 'up', 'changed'], 'all');
    const sort = enumFilter('sort', ['updated', 'price-asc', 'price-desc', 'drop'], 'updated');
    const page = queryNumber(filters.page, 'page', 1, 1e9, true) ?? 1;
    const pageSize = queryNumber(filters.pageSize, 'pageSize', 1, 200, true) ?? 50;
    const priceMin = queryNumber(filters.priceMin, 'priceMin', 0, 1e12);
    const priceMax = queryNumber(filters.priceMax, 'priceMax', 0, 1e12);
    if ((priceMin !== null || priceMax !== null) && currency === null) fail('Для фильтра цены выберите валюту.');
    const areaMin = queryNumber(filters.areaMin, 'areaMin', 0, 1e7);
    const areaMax = queryNumber(filters.areaMax, 'areaMax', 0, 1e7);
    const floorMin = queryNumber(filters.floorMin, 'floorMin', -10, 1000, true);
    const floorMax = queryNumber(filters.floorMax, 'floorMax', -10, 1000, true);
    const floorsTotalMin = queryNumber(filters.floorsTotalMin, 'floorsTotalMin', 1, 1000, true);
    const floorsTotalMax = queryNumber(filters.floorsTotalMax, 'floorsTotalMax', 1, 1000, true);
    const excludeFirst = queryBoolean(filters.excludeFirst, 'excludeFirst');
    const excludeLast = queryBoolean(filters.excludeLast, 'excludeLast');
    for (const [min, max, label] of [[priceMin, priceMax, 'price'], [areaMin, areaMax, 'area'], [floorMin, floorMax, 'floor'], [floorsTotalMin, floorsTotalMax, 'floorsTotal']]) {
      if (min !== null && max !== null && min > max) fail(`${label}: минимум больше максимума.`);
    }
    const sellerTypes = new Set();
    if (filters.sellerType !== undefined && filters.sellerType !== null && filters.sellerType !== '') {
      const tokens = Array.isArray(filters.sellerType) ? filters.sellerType : typeof filters.sellerType === 'string' ? filters.sellerType.split(',') : fail('sellerType: требуется список типов продавца.');
      if (tokens.length > 4) fail('sellerType: слишком много значений.');
      for (const token of tokens) sellerTypes.add(enumeration(typeof token === 'string' ? token.trim() : token, 'sellerType', ['owner', 'developer', 'agent', 'unknown']));
    }
    const roomValues = new Set();
    let roomsMin = queryNumber(filters.roomsMin, 'roomsMin', 0, 1000, true);
    if (filters.rooms !== undefined && filters.rooms !== null && filters.rooms !== '') {
      const tokens = Array.isArray(filters.rooms) ? filters.rooms : typeof filters.rooms === 'string' ? filters.rooms.split(',') : fail('rooms: требуется список чисел через запятую.');
      if (tokens.length > 100) fail('rooms: слишком много значений.');
      for (const token of tokens) {
        const value = typeof token === 'number' ? token : typeof token === 'string' ? token.trim() : fail('rooms: недопустимое значение.');
        if (typeof value === 'string' && /^\d+\+$/u.test(value)) {
          const minimum = queryNumber(value.slice(0, -1), 'rooms', 0, 1000, true);
          roomsMin = roomsMin === null ? minimum : Math.min(roomsMin, minimum);
        } else {
          const exact = queryNumber(value, 'rooms', 0, 1000, true);
          if (exact === null) fail('rooms: пустое значение.');
          roomValues.add(exact);
        }
      }
    }
    const inRange = (value, min, max) => (min === null && max === null) || (value !== null && (min === null || value >= min) && (max === null || value <= max));
    const items = allListings().filter((item) => {
      if (canonicalSource !== null && item.source !== canonicalSource) return false;
      if (cityKey !== null && item.cityKey !== cityKey) return false;
      if (canonicalCity !== null && normalizeText(item.city) !== canonicalCity) return false;
      if (canonicalRegion !== null && normalizeText(item.region) !== canonicalRegion) return false;
      if (districtFilter !== null && !normalizeText(item.district || '').includes(normalizeText(districtFilter))) return false;
      if (deal !== null && item.deal !== deal || kind !== null && item.kind !== kind || currency !== null && item.currency !== currency) return false;
      if (sellerTypes.size > 0 && !sellerTypes.has(item.sellerType)) return false;
      if (normalizedQ !== null && !normalizeText([item.title, item.city, item.region, item.district || '', item.address || '', item.source].join(' ')).includes(normalizedQ)) return false;
      if (changes === 'down' && !(item.changeAmount < 0) || changes === 'up' && !(item.changeAmount > 0) || changes === 'changed' && item.previousPrice === null) return false;
      if (!inRange(item.price, priceMin, priceMax) || !inRange(item.area, areaMin, areaMax) || !inRange(item.floor, floorMin, floorMax) || !inRange(item.floorsTotal, floorsTotalMin, floorsTotalMax)) return false;
      if ((roomValues.size > 0 || roomsMin !== null) && (item.rooms === null || !(roomValues.has(item.rooms) || (roomsMin !== null && item.rooms >= roomsMin)))) return false;
      if (excludeFirst && (item.floor === null || item.floor === 1)) return false;
      if (excludeLast && (item.floor === null || item.floorsTotal === null || item.floor === item.floorsTotal)) return false;
      return true;
    });
    items.sort((a, b) => {
      let result;
      if (sort === 'price-asc') result = a.currency.localeCompare(b.currency) || a.price - b.price;
      else if (sort === 'price-desc') result = a.currency.localeCompare(b.currency) || b.price - a.price;
      else if (sort === 'drop') {
        const av = a.changePercent ?? Infinity;
        const bv = b.changePercent ?? Infinity;
        result = av === bv ? 0 : av < bv ? -1 : 1;
      } else result = b.lastSeen.localeCompare(a.lastSeen);
      return result || a.id - b.id;
    });
    return { items: items.slice((page - 1) * pageSize, page * pageSize), total: items.length, page, pageSize };
  }

  function detail(id) {
    ensureOpen();
    const listingId = queryNumber(id, 'id', 1, Number.MAX_SAFE_INTEGER, true);
    if (listingId === null) fail('id: требуется идентификатор объявления.');
    const row = db.prepare('SELECT summary_json FROM listings WHERE id=?').get(listingId);
    if (!row) return null;
    return {
      listing: JSON.parse(row.summary_json),
      history: historyQuery.all(listingId).map((entry) => {
        const record = JSON.parse(entry.record_json);
        return { observedAt: record.observedAt, price: record.price, currency: record.currency, status: record.status, period: record.period, sellerType: record.sellerType };
      })
    };
  }

  function cities() {
    const groups = new Map();
    for (const listing of allListings()) {
      const existing = groups.get(listing.cityKey);
      if (existing) existing.count++;
      else groups.set(listing.cityKey, { cityKey: listing.cityKey, city: listing.city, region: listing.region, count: 1 });
    }
    return [...groups.values()].sort((a, b) => a.city.localeCompare(b.city, 'uk') || a.region.localeCompare(b.region, 'uk'));
  }

  function stats() {
    const listings = allListings();
    return {
      listingCount: listings.length,
      observationCount: Number(db.prepare('SELECT COUNT(*) AS total FROM observations').get().total),
      changedCount: listings.filter((listing) => listing.previousPrice !== null).length,
      downCount: listings.filter((listing) => listing.changeAmount < 0).length,
      upCount: listings.filter((listing) => listing.changeAmount > 0).length,
      lastSeen: listings.reduce((latest, listing) => latest === null || listing.lastSeen > latest ? listing.lastSeen : latest, null)
    };
  }

  function exportData() {
    ensureOpen();
    return {
      format: 'domtrack-ua',
      version: 1,
      exportedAt: new Date().toISOString(),
      records: db.prepare('SELECT record_json FROM observations ORDER BY observed_at ASC,listing_id ASC').all().map((row) => JSON.parse(row.record_json))
    };
  }

  function close() { if (!closed) { db.close(); closed = true; } }
  return { ingest, list, detail, cities, stats, exportData, close };
}

module.exports = { createStore, ValidationError };

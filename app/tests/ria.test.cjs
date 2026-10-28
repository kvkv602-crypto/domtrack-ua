'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createRiaClient, normalizeRiaRecord, RiaDataError } = require('../lib/ria.cjs');
let sequence = 0;
const key = () => `fixture-only-${++sequence}`;
const fixture = (changes = {}) => ({ realty_id: '12345678', advert_type_id: '1', realty_type_id: '2', realty_type_parent_id: '1', price_total: '58000', price: '1160', price_type: 'm2', currency_type: '$', floor: '3', floors_count: '9', rooms_count: '2', total_square_meters: '50', city_name: 'Київ', state_name: 'Київська', street_name: 'Прикладна', building_number_str: '7', is_show_building_no: 1, beautiful_url: 'realty-prodaja-kvartira-kiev-12345678.html', advert_publish_type: 1, user: { phone: 'private' }, description: 'private', photos: ['private'], ip: 'private', priceArr: { USD: '1' }, ...changes });
const json = data => new Response(JSON.stringify(data), { status: 200, headers: { 'content-type': 'application/json' } });
const settings = { stateId: '10', cityId: '10', kind: 'apartment', maxListings: 2 };
const HOUR = 3_600_000;
const MONTH = 30 * 24 * HOUR;

test('maps original total price and numeric strings, retaining no seller data', () => {
  const record = normalizeRiaRecord(fixture(), { observedAt: '2026-10-28T12:00:00Z' });
  assert.equal(record.externalId, '12345678');
  assert.equal(record.price, 58000);
  assert.equal(record.currency, 'USD');
  assert.equal(record.period, 'total');
  assert.equal(record.city, 'Київ');
  assert.equal(record.area, 50);
  assert.equal(record.floorsTotal, 9);
  assert.equal(record.address, 'Прикладна, 7');
  assert.equal(record.observedAt, '2026-10-28T12:00:00.000Z');
  for (const name of ['user', 'description', 'photos', 'ip', 'priceArr']) assert.equal(Object.hasOwn(record, name), false);
});
test('requires original currency and a total price; converted prices never substitute', () => {
  assert.throws(() => normalizeRiaRecord(fixture({ currency_type: null })), RiaDataError);
  assert.throws(() => normalizeRiaRecord(fixture({ currency_type: 'toString' })), RiaDataError);
  assert.throws(() => normalizeRiaRecord(fixture({ price_total: undefined, price: 1160 })), RiaDataError);
  assert.throws(() => normalizeRiaRecord(fixture({ price_total: '58000oops' })), RiaDataError);
  assert.equal(normalizeRiaRecord(fixture({ currency_type: 'грн' })).currency, 'UAH');
  assert.equal(normalizeRiaRecord(fixture({ currency_type: '€' })).currency, 'EUR');
});
test('skips rent, unsupported properties and property mismatches', () => {
  assert.equal(normalizeRiaRecord(fixture({ advert_type_id: 3 })), null);
  assert.equal(normalizeRiaRecord(fixture({ realty_type_id: 9 })), null);
  assert.equal(normalizeRiaRecord(fixture(), { kind: 'house' }), null);
  assert.equal(normalizeRiaRecord(fixture({ realty_type_id: 5 })).kind, 'house');
});
test('ignores unsafe URLs and respects the building-number visibility flag', () => {
  for (const value of ['https://evil.example/12345678.html', '//evil.example/x', 'javascript:alert(1)', '/uk/realty-12345678.html?api_key=secret']) {
    const record = normalizeRiaRecord(fixture({ beautiful_url: value, is_show_building_no: 0 }));
    assert.equal(record.url, 'https://dom.ria.com/uk/realty-12345678.html');
    assert.equal(record.address, 'Прикладна');
  }
});
test('search uses fixed API host, documented filters and zero-based page', async () => {
  let request;
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async (url, options) => { request = { url: new URL(url), options }; return json({ items: [123, '123', 124], count: '200' }); } });
  assert.deepEqual(await client.search(settings), { items: ['123', '124'], count: 200 });
  assert.equal(request.url.origin, 'https://developers.ria.com');
  assert.equal(request.url.pathname, '/dom/search');
  assert.equal(request.url.searchParams.get('operation_type'), '1');
  assert.equal(request.url.searchParams.get('category'), '1');
  assert.equal(request.url.searchParams.get('realty_type'), '2');
  assert.equal(request.url.searchParams.get('page'), '0');
  assert.equal(request.options.redirect, 'error');
  await assert.rejects(client.search(settings, 100), { code: 'RIA_SETTINGS' });
});
test('normalizes region/city IDs and filters cities from a different region', async () => {
  const paths = [];
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async url => { const u = new URL(url); paths.push(u); return json(u.pathname === '/dom/states' ? [{ stateID: 5, name: 'Львівська' }] : [{ cityID: 287, stateID: 5, name: 'Борислав' }, { cityID: 10, stateID: 10, name: 'Київ' }]); } });
  assert.deepEqual(await client.regions(), [{ id: '5', name: 'Львівська' }]);
  assert.deepEqual(await client.cities(5), [{ id: '287', name: 'Борислав', stateId: '5' }]);
  assert.equal(paths[1].pathname, '/dom/cities/5');
  assert.equal(paths[1].searchParams.get('lang_id'), '4');
});
test('collects sequentially, reports first-page subset and caps details', async () => {
  let active = 0; let maxActive = 0; const paths = [];
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async url => { active++; maxActive = Math.max(maxActive, active); const u = new URL(url); paths.push(u.pathname); await Promise.resolve(); active--; return json(u.pathname === '/dom/search' ? { items: [12345678, 12345679, 12345680], count: 1000 } : fixture({ realty_id: u.pathname.split('/').at(-1) })); } });
  const result = await client.collect(settings);
  assert.equal(result.records.length, 2);
  assert.equal(result.matched, 1000);
  assert.equal(result.processed, 2);
  assert.equal(result.requestCount, 3);
  assert.equal(result.truncated, true);
  assert.equal(result.scope, 'first_page');
  assert.equal(maxActive, 1);
  assert.equal(paths.length, 3);
});
test('both searches apartments and houses with separate quotas', async () => {
  const types = [];
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async url => { const u = new URL(url); if (u.pathname === '/dom/search') { const type = u.searchParams.get('realty_type'); types.push(type); return json({ items: type === '2' ? [12345678, 12345679] : [22345678, 22345679], count: 2 }); } const id = u.pathname.split('/').at(-1); return json(fixture({ realty_id: id, realty_type_id: id.startsWith('2') ? 5 : 2 })); } });
  const result = await client.collect({ ...settings, kind: 'both' });
  assert.deepEqual(types, ['2', '5']);
  assert.deepEqual(result.records.map(record => record.kind), ['apartment', 'house']);
  assert.equal(result.requestCount, 4);
});
test('sliding-hour limit survives collect runs and clients using the same key', async () => {
  let time = 10_000_000; let calls = 0; const sharedKey = key();
  const options = { apiKey: sharedKey, delayMs: 0, now: () => time, fetchImpl: async () => { calls++; return json([]); } };
  const client = createRiaClient(options);
  for (let i = 0; i < 28; i++) await client.regions();
  await assert.rejects(createRiaClient(options).regions(), { code: 'RIA_RATE_LIMIT' });
  assert.equal(calls, 28);
  assert.equal(JSON.stringify(client.rateLimiter.snapshot()).includes(sharedKey), false);
  time += 3_600_000;
  await client.regions();
  assert.equal(calls, 29);
});
test('restores persisted rate state without storing the key in snapshots', async () => {
  const time = 20_000_000;
  const client = createRiaClient({ apiKey: key(), now: () => time, initialRequestTimestamps: Array.from({ length: 28 }, (_, i) => time - i), fetchImpl: async () => { throw new Error('must not fetch'); } });
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT' });
  assert.equal(client.rateLimiter.snapshot().remaining, 0);
});
test('monthly limit defaults to 100 and only explicit numeric 100 or 1000 is accepted', () => {
  const client = createRiaClient({ apiKey: key(), now: () => Date.UTC(2026, 9, 28) });
  assert.deepEqual(client.rateLimiter.snapshot().monthly, { used: 0, remaining: 100, limit: 100, windowMs: MONTH, nextAvailableAt: null });
  for (const monthlyBudget of [0, 99, 101, 1001, '100', '1000', null, true, NaN, Infinity, 100.5]) {
    assert.throws(() => createRiaClient({ apiKey: key(), monthlyBudget }), { code: 'RIA_SETTINGS' });
  }
  for (const monthlyBudget of [100, 1000]) assert.equal(createRiaClient({ apiKey: key(), monthlyBudget }).rateLimiter.snapshot().monthly.limit, monthlyBudget);
});
test('rolling 30-day guard rejects before HTTP and releases exactly as an old request expires', async () => {
  let time = Date.UTC(2026, 9, 28); let calls = 0; let persisted;
  const initial = Array.from({ length: 100 }, (_, i) => time - MONTH + (i + 1) * HOUR);
  const client = createRiaClient({ apiKey: key(), now: () => time, delayMs: 0, initialRequestTimestamps: initial, onRequest: timestamps => { persisted = timestamps; }, fetchImpl: async () => { calls++; return json([]); } });
  let state = client.rateLimiter.snapshot();
  assert.equal(state.used, 0); assert.equal(state.remaining, 28); assert.equal(state.nextAvailableAt, null);
  assert.equal(state.monthly.used, 100); assert.equal(state.monthly.remaining, 0); assert.equal(state.monthly.nextAvailableAt, time + HOUR);
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT', retryAt: time + HOUR });
  assert.equal(calls, 0); assert.equal(persisted, undefined);
  time += HOUR - 1;
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT', retryAt: time + 1 });
  assert.equal(calls, 0);
  time++;
  await client.regions();
  state = client.rateLimiter.snapshot();
  assert.equal(calls, 1); assert.equal(state.used, 1); assert.equal(state.monthly.used, 100);
  assert.equal(persisted.length, 100); assert.equal(persisted.includes(initial[0]), false); assert.equal(persisted.at(-1), time);
});
test('restored history retains 30 days while hourly counters only include the last hour', () => {
  const time = Date.UTC(2026, 9, 28);
  const retained = [time - MONTH + 1, time - 8 * 24 * HOUR, time - HOUR + 1];
  const client = createRiaClient({ apiKey: key(), now: () => time, rateState: { timestamps: [time - MONTH, time - MONTH - 1, ...retained, 'invalid', NaN, time + 1] } });
  const state = client.rateLimiter.snapshot();
  assert.deepEqual(state.timestamps, retained); assert.equal(state.monthly.used, 3); assert.equal(state.used, 1); assert.equal(state.remaining, 27);
});
test('persisted 30-day attempts enforce the same cap after a fresh module restart with the same key', async () => {
  const time = Date.UTC(2026, 9, 28); const secret = key(); let saved; let calls = 0;
  const initial = Array.from({ length: 99 }, (_, i) => time - (i + 2) * HOUR);
  const client = createRiaClient({ apiKey: secret, now: () => time, delayMs: 0, initialRequestTimestamps: initial, onRequest: timestamps => { saved = JSON.parse(JSON.stringify(timestamps)); }, fetchImpl: async () => { calls++; return json([]); } });
  await client.regions();
  assert.equal(saved.length, 100); assert.equal(calls, 1);
  const path = require.resolve('../lib/ria.cjs'); const originalModule = require.cache[path]; let freshCreate;
  try { delete require.cache[path]; freshCreate = require(path).createRiaClient; } finally { require.cache[path] = originalModule; }
  const restored = freshCreate({ apiKey: secret, now: () => time, delayMs: 0, rateState: { timestamps: saved }, fetchImpl: async () => { calls++; return json([]); } });
  await assert.rejects(restored.regions(), { code: 'RIA_RATE_LIMIT' });
  const state = restored.rateLimiter.snapshot();
  assert.equal(calls, 1); assert.equal(state.monthly.used, 100); assert.equal(state.used, 1); assert.equal(state.remaining, 27);
  assert.equal(JSON.stringify({ saved, state }).includes(secret), false);
});
test('1000 cap requires explicit selection and shared-key clients keep their own selected caps', async () => {
  const time = Date.UTC(2026, 9, 28); const sharedKey = key(); let calls = 0;
  const options = { apiKey: sharedKey, now: () => time, delayMs: 0, initialRequestTimestamps: Array.from({ length: 100 }, (_, i) => time - (i + 2) * HOUR), fetchImpl: async () => { calls++; return json([]); } };
  const upgraded = createRiaClient({ ...options, monthlyBudget: 1000 });
  await upgraded.regions();
  assert.equal(upgraded.rateLimiter.snapshot().monthly.remaining, 899);
  const defaultCap = createRiaClient(options);
  await assert.rejects(defaultCap.regions(), { code: 'RIA_RATE_LIMIT' });
  assert.equal(defaultCap.rateLimiter.snapshot().monthly.limit, 100); assert.equal(calls, 1);
  const exhausted = createRiaClient({ ...options, apiKey: key(), monthlyBudget: 1000, initialRequestTimestamps: Array.from({ length: 1000 }, (_, i) => time - HOUR - i * 1000) });
  await assert.rejects(exhausted.regions(), { code: 'RIA_RATE_LIMIT' });
  assert.equal(exhausted.rateLimiter.snapshot().monthly.used, 1000); assert.equal(calls, 1);
});
test('lowered monthly cap reports when enough attempts expire and keeps server 429 blocking', async () => {
  const time = Date.UTC(2026, 9, 28); let calls = 0;
  const timestamps = Array.from({ length: 150 }, (_, i) => time - MONTH + (i + 1) * HOUR);
  const client = createRiaClient({ apiKey: key(), now: () => time, rateState: { timestamps, blockedUntil: time + HOUR }, fetchImpl: async () => { calls++; return json([]); } });
  const state = client.rateLimiter.snapshot();
  assert.equal(state.nextAvailableAt, time + HOUR); assert.equal(state.monthly.nextAvailableAt, time + 51 * HOUR);
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT', retryAt: time + 51 * HOUR });
  assert.equal(calls, 0);
});
test('server 429 Retry-After still blocks shared requests without automatic retries', async () => {
  let time = Date.UTC(2026, 9, 28); let calls = 0;
  const client = createRiaClient({ apiKey: key(), now: () => time, delayMs: 0, fetchImpl: async () => { calls++; return calls === 1 ? new Response('', { status: 429, headers: { 'retry-after': '120' } }) : json([]); } });
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT', retryAt: time + 120000 });
  await assert.rejects(client.regions(), { code: 'RIA_RATE_LIMIT', retryAt: time + 120000 });
  assert.equal(calls, 1); assert.equal(client.rateLimiter.snapshot().monthly.used, 1);
  time += 120000;
  await client.regions(); assert.equal(calls, 2); assert.equal(client.rateLimiter.snapshot().monthly.used, 2);
});
test('auth and rate errors do not retry or leak URLs, response bodies or the key', async () => {
  for (const status of [401, 429]) {
    const secret = key(); let calls = 0;
    const client = createRiaClient({ apiKey: secret, delayMs: 0, fetchImpl: async () => { calls++; return new Response(`secret ${secret}`, { status }); } });
    const result = await client.collect(settings);
    assert.equal(calls, 1);
    assert.equal(result.errors[0].code, status === 401 ? 'RIA_AUTH' : 'RIA_RATE_LIMIT');
    assert.equal(JSON.stringify(result).includes(secret), false);
  }
});
test('network exceptions are sanitized', async () => {
  const secret = key();
  const client = createRiaClient({ apiKey: secret, fetchImpl: async () => { throw new Error(`https://developers.ria.com/dom/search?api_key=${secret}`); } });
  await assert.rejects(client.regions(), error => error.code === 'RIA_NETWORK' && !error.message.includes(secret));
});
test('rejects bodies above 5 MiB and invalid JSON', async () => {
  for (const response of [new Response('x', { headers: { 'content-length': String(5 * 1024 * 1024 + 1) } }), new Response('not JSON')]) {
    const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async () => response });
    await assert.rejects(client.regions(), { code: 'RIA_DATA' });
  }
});
test('timeouts are bounded even when a fetch implementation ignores abort', async () => {
  const client = createRiaClient({ apiKey: key(), timeoutMs: 5, fetchImpl: () => new Promise(() => {}) });
  await assert.rejects(client.regions(), { code: 'RIA_TIMEOUT' });
});
test('maps seller type only from documented characteristic codes', () => {
  for (const [code, expected] of [[1436, 'owner'], [1434, 'agent'], [1473, 'developer'], [1506, 'developer'], [1435, 'unknown']]) {
    assert.equal(normalizeRiaRecord(fixture({ characteristics_values: { 1437: String(code) }, is_commercial: true })).sellerType, expected);
  }
  assert.equal(normalizeRiaRecord(fixture({ is_commercial: true })).sellerType, 'unknown');
});
test('developer search repeats its documented seller characteristic values', async () => {
  let called;
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async url => { called = new URL(url); return json({ items: [], count: 0 }); } });
  await client.search({ ...settings, sellerType: 'developer' });
  assert.deepEqual(called.searchParams.getAll('characteristic[1437]'), ['1473', '1506']);
});
test('persists the attempted request before making the HTTP call', async () => {
  const order = [];
  const client = createRiaClient({ apiKey: key(), delayMs: 0, onRequest: async timestamps => { assert.equal(timestamps.length, 1); order.push('persist'); }, fetchImpl: async () => { order.push('fetch'); return json([]); } });
  await client.regions();
  assert.deepEqual(order, ['persist', 'fetch']);
});
test('refreshes watched IDs before searching; missing records do not become sold/inactive', async () => {
  const paths = [];
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async url => { const path = new URL(url).pathname; paths.push(path); if (path === '/dom/search') return json({ items: [12345678, 12345679], count: 2 }); if (path.endsWith('12345678')) return new Response('', { status: 404 }); return json(fixture({ realty_id: 12345679 })); } });
  const result = await client.collect({ ...settings, refreshIds: [12345678] });
  assert.equal(paths[0], '/dom/info/12345678');
  assert.equal(paths[1], '/dom/search');
  assert.equal(result.records.length, 1);
  assert.equal(result.records[0].status, 'active');
  assert.equal(result.errors[0].statusCode, 404);
  assert.equal(result.processed, 2);
});
test('collector stops before further calls after its five-minute deadline', async () => {
  let time = 50_000_000; let calls = 0;
  const client = createRiaClient({ apiKey: key(), delayMs: 0, now: () => time, fetchImpl: async url => { calls++; const u = new URL(url); if (u.pathname === '/dom/search') { time += 300001; return json({ items: [12345678, 12345679], count: 2 }); } return json(fixture()); } });
  const result = await client.collect(settings);
  assert.equal(calls, 1);
  assert.equal(result.records.length, 0);
  assert.equal(result.errors[0].code, 'RIA_DEADLINE');
});
test('unknown publication statuses never imply a sale or an active observation', () => {
  assert.throws(() => normalizeRiaRecord(fixture({ advert_publish_type: 99 })), RiaDataError);
  assert.throws(() => normalizeRiaRecord(fixture({ advert_publish_type: undefined })), RiaDataError);
});
test('enforces the streamed body limit without trusting Content-Length', async () => {
  const bytes = new Uint8Array(5 * 1024 * 1024 + 1);
  const client = createRiaClient({ apiKey: key(), delayMs: 0, fetchImpl: async () => new Response(bytes) });
  await assert.rejects(client.regions(), { code: 'RIA_DATA' });
});
test('copies only the public RIA seller name without guessing API phone/email fields', () => {
  const record = normalizeRiaRecord(fixture({ user: { name: 'Олена <img src=x onerror=alert(1)> Коваль', phone: '+380671234567', email: 'private@example.com' }, phone: '+380991234567' }));
  assert.equal(record.sellerName, 'Олена Коваль');
  assert.equal(Object.hasOwn(record, 'contactPhone'), false);
  assert.equal(Object.hasOwn(record, 'contactEmail'), false);
  assert.equal(Object.hasOwn(record, 'user'), false);
});

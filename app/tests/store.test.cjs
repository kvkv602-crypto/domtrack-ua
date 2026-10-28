'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createStore, ValidationError } = require('../lib/store.cjs');

const record = (overrides = {}) => ({
  source: 'example', externalId: '123', url: 'https://example.test/listing/123',
  title: 'Квартира на Подолі', city: 'Київ', region: 'Київська область',
  deal: 'sale', kind: 'apartment', price: 100000, currency: 'USD',
  area: 60, rooms: 2, floor: 3, floorsTotal: 9,
  observedAt: '2026-01-01T10:00:00Z', ...overrides
});

function memory(t) {
  const store = createStore(':memory:');
  t.after(() => store.close());
  return store;
}

test('persistent listings and full export survive close/reopen', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'domtrack-store-'));
  const filename = path.join(directory, 'nested', 'listings.sqlite');
  let store = createStore(filename);
  t.after(() => {
    store.close();
    assert.equal(path.dirname(directory), path.resolve(os.tmpdir()));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  store.ingest([record(), record({ price: 90000, observedAt: '2026-01-02T10:00:00Z' })]);
  const before = store.exportData();
  store.close();
  store = createStore(filename);
  assert.equal(store.stats().listingCount, 1);
  assert.equal(store.list().items[0].price, 90000);
  assert.deepEqual(store.exportData().records, before.records);
  assert.deepEqual(store.detail(1).history.map((item) => item.price), [100000, 90000]);
});

test('out-of-order backfill changes history and initial price, never latest metadata', (t) => {
  const store = memory(t);
  store.ingest([record({ title: 'Новое название', price: 80000, city: 'Львів', region: 'Львівська', observedAt: '2026-01-03T10:00:00Z' })]);
  store.ingest([record({ title: 'Старое название', price: 100000, observedAt: '2026-01-01T10:00:00Z' })]);
  store.ingest([record({ price: 90000, observedAt: '2026-01-02T10:00:00Z' })]);
  const item = store.list().items[0];
  assert.equal(item.title, 'Новое название');
  assert.equal(item.city, 'Львів');
  assert.equal(item.region, 'Львівська область');
  assert.equal(item.price, 80000);
  assert.equal(item.initialPrice, 100000);
  assert.equal(item.previousPrice, 90000);
  assert.equal(item.totalChangePercent, -20);
  assert.equal(item.firstSeen, '2026-01-01T10:00:00.000Z');
  assert.deepEqual(store.detail(item.id).history.map((entry) => entry.price), [100000, 90000, 80000]);
});

test('idempotent observations, including time zone equivalents and duplicates within batch', (t) => {
  const store = memory(t);
  assert.deepEqual(store.ingest([record(), record({ observedAt: '2026-01-01T12:00:00+02:00' })]), { received: 2, inserted: 1, duplicates: 1, updatedListings: 1 });
  assert.equal(store.ingest([record()]).duplicates, 1);
  assert.equal(store.stats().observationCount, 1);
});

test('stationary observations and status changes preserve the previous different price', (t) => {
  const store = memory(t);
  store.ingest([
    record(),
    record({ price: 90000, observedAt: '2026-01-02T10:00:00Z' }),
    record({ price: 90000, observedAt: '2026-01-03T10:00:00Z' }),
    record({ price: 90000, status: 'inactive', observedAt: '2026-01-04T10:00:00Z' })
  ]);
  const item = store.list({ changes: 'down' }).items[0];
  assert.equal(item.previousPrice, 100000);
  assert.equal(item.changePercent, -10);
  assert.equal(item.changeAmount, -10000);
  assert.equal(item.observationCount, 4);
  assert.equal(item.status, 'inactive');
  assert.equal(store.stats().downCount, 1);
});

test('currency and period changes have no monetary or percentage comparison', (t) => {
  const store = memory(t);
  store.ingest([record(), record({ price: 90000, currency: 'EUR', observedAt: '2026-01-02T10:00:00Z' }), record({ price: 90000, currency: 'EUR', observedAt: '2026-01-03T10:00:00Z' })]);
  let item = store.list().items[0];
  assert.equal(item.previousCurrency, 'USD');
  assert.equal(item.changeAmount, null);
  assert.equal(item.changePercent, null);
  assert.equal(item.totalChangePercent, null);
  assert.equal(store.list({ changes: 'changed' }).total, 1);
  assert.equal(store.list({ changes: 'down' }).total, 0);
  store.ingest([record({ price: 100, currency: 'EUR', period: 'day', observedAt: '2026-01-04T10:00:00Z' })]);
  item = store.list().items[0];
  assert.equal(item.previousPeriod, 'total');
  assert.equal(item.changePercent, null);
  assert.equal(item.changeAmount, null);
  assert.equal(item.totalChangePercent, null);
});

test('zero baseline has an absolute change but never Infinity or a percentage', (t) => {
  const store = memory(t);
  store.ingest([record({ price: 0 }), record({ price: 500, observedAt: '2026-01-02T10:00:00Z' })]);
  const item = store.list().items[0];
  assert.equal(item.changeAmount, 500);
  assert.equal(item.changePercent, null);
  assert.equal(item.totalChangePercent, null);
  assert.equal(store.stats().upCount, 1);
});

test('source + externalId identity prevents unrelated sites from merging', (t) => {
  const store = memory(t);
  store.ingest([record(), record({ source: 'another' })]);
  assert.equal(store.stats().listingCount, 2);
  assert.equal(store.list().items[0].externalId, store.list().items[1].externalId);
  assert.equal(store.list({ source: ' ANOTHER ' }).total, 1);
  assert.equal(store.list({ source: 'missing' }).total, 0);
});

test('without externalId canonical URL ignores known tracking but preserves semantic routes', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: null, url: 'https://EXAMPLE.test/listing/a?utm=x#section' }),
    record({ externalId: null, url: 'https://example.test/listing/a?utm=y#section', price: 90000, observedAt: '2026-01-02T10:00:00Z' }),
    record({ externalId: null, url: 'https://example.test/listing/b' })
  ]);
  assert.equal(store.stats().listingCount, 2);
  assert.equal(store.list({ changes: 'down' }).total, 1);
});

test('a stable external ID supports observations without a URL, with no invented links', (t) => {
  const store = memory(t);
  const first = record({ source: 'manual', externalId: 'my-flat', url: undefined });
  store.ingest([first, record({ source: 'manual', externalId: 'my-flat', url: '', price: 90000, observedAt: '2026-01-02T10:00:00Z' })]);
  assert.equal(store.stats().listingCount, 1);
  assert.equal(store.stats().observationCount, 2);
  assert.equal(store.list().items[0].url, null);
  assert.equal(store.exportData().records[0].url, null);
  assert.throws(() => store.ingest([record({ externalId: null, url: undefined })]), /externalId/);
  assert.throws(() => store.ingest([record({ externalId: '', url: '' })]), /externalId/);
  assert.throws(() => store.ingest([record({ url: 'javascript:alert(1)' })]), ValidationError);
});

test('city aliases, apostrophes and region form canonical filters without merging cities', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: 'a', city: 'Киев', region: 'Киевская обл.', currency: 'USD' }),
    record({ externalId: 'b', city: 'Київ', region: 'Київська область', currency: 'UAH' }),
    record({ externalId: 'c', city: 'Київ', region: 'Інша область', currency: 'USD' }),
    record({ externalId: 'd', city: 'Кам’янське', region: 'Дніпропетровська' })
  ]);
  assert.equal(store.cities().length, 3);
  assert.equal(store.list({ city: 'Киев', currency: 'USD' }).total, 2);
  assert.equal(store.list({ city: 'Київ', region: 'Киевская', currency: 'USD' }).total, 1);
  const key = store.cities().find((item) => item.city === 'Київ' && item.count === 2).cityKey;
  assert.equal(store.list({ cityKey: key, currency: 'UAH' }).total, 1);
  assert.equal(store.list({ city: "кам'янське" }).total, 1);
});

test('a bad record rejects the entire batch before anything is changed', (t) => {
  const store = memory(t);
  store.ingest([record()]);
  const before = store.exportData().records;
  assert.throws(() => store.ingest([record({ externalId: 'new' }), record({ price: -1 })]), ValidationError);
  assert.deepEqual(store.exportData().records, before);
  assert.equal(store.stats().listingCount, 1);
});

test('conflicting same-time observations reject the whole batch, including conflicts with storage', (t) => {
  const store = memory(t);
  assert.throws(() => store.ingest([record(), record({ price: 9 })]), /Конфликт/);
  assert.equal(store.stats().listingCount, 0);
  store.ingest([record()]);
  assert.throws(() => store.ingest([record({ externalId: 'new' }), record({ title: 'Другой заголовок' })]), /Конфликт/);
  assert.equal(store.stats().listingCount, 1);
  assert.equal(store.stats().observationCount, 1);
});

test('URL, types, calendar dates, bounds, and future observations are strictly validated', (t) => {
  const store = memory(t);
  for (const invalid of [
    { url: 'javascript:alert(1)' }, { url: 'https://user:secret@example.test/a' },
    { price: '500' }, { price: Infinity }, { price: NaN }, { price: 1e12 + 1 },
    { observedAt: '2026-02-30T10:00:00Z' }, { observedAt: '1899-12-31T23:59:59Z' },
    { observedAt: '2026-01-01' }, { observedAt: new Date(Date.now() + 600000).toISOString() },
    { area: 0 }, { rooms: 1.5 }, { floor: 10, floorsTotal: 9 }, { title: 'x'.repeat(2001) },
    { city: 123 }, { externalId: 3 }, { currency: 'GBP' }
  ]) assert.throws(() => store.ingest([record(invalid)]), ValidationError);
  assert.equal(store.stats().listingCount, 0);
});

test('missing observedAt uses one timestamp per batch and defaults sale period to total', (t) => {
  const store = memory(t);
  const first = record({ externalId: 'a' });
  const second = record({ externalId: 'b' });
  delete first.observedAt;
  delete second.observedAt;
  store.ingest([first, second]);
  const items = store.list().items;
  assert.equal(items[0].lastSeen, items[1].lastSeen);
  assert.equal(items[0].period, 'total');
});

test('area, room, floor and building-floor filters include boundaries and exclude unknowns', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: 'a', area: 50, rooms: 1, floor: 1, floorsTotal: 5 }),
    record({ externalId: 'b', area: 60, rooms: 2, floor: 3, floorsTotal: 5 }),
    record({ externalId: 'c', area: 70, rooms: 5, floor: 5, floorsTotal: 5 }),
    record({ externalId: 'd', area: null, rooms: null, floor: null, floorsTotal: null }),
    record({ externalId: 'e', area: 60, rooms: 6, floor: 3, floorsTotal: null }),
    record({ externalId: 'house', kind: 'house', area: 200, rooms: 7, floor: null, floorsTotal: 2 })
  ]);
  assert.deepEqual(store.list({ areaMin: '50', areaMax: '70' }).items.map((item) => item.externalId), ['a', 'b', 'c', 'e']);
  assert.equal(store.list({ areaMin: 60, areaMax: 60 }).total, 2);
  assert.deepEqual(store.list({ rooms: '1,2,5+' }).items.map((item) => item.externalId), ['a', 'b', 'c', 'e', 'house']);
  assert.equal(store.list({ rooms: '1', roomsMin: 5 }).total, 4);
  assert.deepEqual(store.list({ floorMin: '3', floorMax: '5' }).items.map((item) => item.externalId), ['b', 'c', 'e']);
  assert.deepEqual(store.list({ excludeFirst: true, excludeLast: true }).items.map((item) => item.externalId), ['b']);
  assert.deepEqual(store.list({ kind: 'house', floorsTotalMin: 2, floorsTotalMax: 2 }).items.map((item) => item.externalId), ['house']);
});

test('bounded query validation rejects NaN, ambiguous numbers, inversions and malformed room lists', (t) => {
  const store = memory(t);
  for (const filter of [
    { page: 'NaN' }, { page: 0 }, { pageSize: 201 }, { pageSize: 1.5 },
    { areaMin: 'Infinity' }, { areaMin: '0x10' }, { areaMin: [] }, { areaMin: 70, areaMax: 60 },
    { floorMin: 3.5 }, { floorsTotalMin: 0 }, { rooms: '2,,3' }, { rooms: '5+a' },
    { roomsMin: NaN }, { excludeFirst: 'yes' }, { sort: 'sql' }, { currency: 'BTC' },
    { priceMin: 0 }, { priceMax: 50 }, { currency: 'USD', priceMin: 100, priceMax: 50 },
    { currency: 'USD', priceMin: 'NaN' }, { sellerType: 'broker' }, { sellerType: 'owner,,agent' }
  ]) assert.throws(() => store.list(filter), ValidationError);
});

test('price bounds require a currency and never compare amounts across currencies', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: 'a', price: 50, currency: 'USD' }),
    record({ externalId: 'b', price: 100, currency: 'USD' }),
    record({ externalId: 'c', price: 150, currency: 'USD' }),
    record({ externalId: 'd', price: 100, currency: 'EUR' }),
    record({ externalId: 'e', price: 1, currency: 'UAH' })
  ]);
  assert.throws(() => store.list({ priceMin: 0 }), /Для фильтра цены выберите валюту/);
  assert.deepEqual(store.list({ currency: 'USD', priceMin: 50, priceMax: 100 }).items.map((item) => item.externalId), ['a', 'b']);
  assert.deepEqual(store.list({ currency: 'USD', priceMin: 100, priceMax: 100 }).items.map((item) => item.externalId), ['b']);
  assert.deepEqual(store.list({ sort: 'price-asc' }).items.map((item) => item.externalId), ['d', 'e', 'a', 'b', 'c']);
});

test('seller filters exclude unknown and latest seller metadata survives backfill and export', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: 'a', sellerType: 'owner' }),
    record({ externalId: 'b', sellerType: 'developer' }),
    record({ externalId: 'c', sellerType: 'agent' }),
    record({ externalId: 'd' }),
    record({ externalId: 'a', sellerType: 'agent', observedAt: '2026-01-03T10:00:00Z' })
  ]);
  store.ingest([record({ externalId: 'a', sellerType: 'owner', observedAt: '2026-01-02T10:00:00Z' })]);
  assert.equal(store.list({ sellerType: 'owner' }).total, 0);
  assert.equal(store.list({ sellerType: 'agent,developer' }).total, 3);
  assert.equal(store.list({ sellerType: 'unknown' }).items[0].externalId, 'd');
  const listing = store.list({ sellerType: 'agent', q: 'подолі' }).items.find((item) => item.externalId === 'a');
  assert.equal(listing.sellerType, 'agent');
  assert.equal(store.detail(listing.id).history.at(-1).sellerType, 'agent');
  assert.equal(store.exportData().records.filter((item) => item.externalId === 'a').at(-1).sellerType, 'agent');
});

test('pagination, sort, search and stats reflect current snapshot', (t) => {
  const store = memory(t);
  store.ingest([
    record({ externalId: 'a', price: 100 }),
    record({ externalId: 'b', price: 200, kind: 'house', title: 'Зелений будинок' }),
    record({ externalId: 'c', price: 300 }),
    record({ externalId: 'a', price: 50, observedAt: '2026-01-02T10:00:00Z' }),
    record({ externalId: 'b', price: 220, kind: 'house', title: 'Зелений будинок', observedAt: '2026-01-03T10:00:00Z' })
  ]);
  assert.equal(store.list({ q: 'зелений', kind: 'house', deal: 'sale' }).total, 1);
  assert.equal(store.list({ sort: 'price-desc', page: 2, pageSize: 1 }).items[0].externalId, 'b');
  assert.equal(store.list({ sort: 'drop' }).items[0].externalId, 'a');
  assert.equal(store.list({ sort: 'updated' }).items[0].externalId, 'b');
  assert.equal(store.detail(999), null);
  assert.deepEqual(store.stats(), { listingCount: 3, observationCount: 5, changedCount: 2, downCount: 1, upCount: 1, lastSeen: '2026-01-03T10:00:00.000Z' });
});

test('historical export is lossless and idempotently importable into a second store', (t) => {
  const store = memory(t);
  const copy = memory(t);
  store.ingest([record({ address: 'Контрактова площа, 1' }), record({ price: 90000, area: 61, floor: 4, observedAt: '2026-01-02T10:00:00Z' })]);
  const exported = store.exportData();
  assert.equal(exported.format, 'domtrack-ua');
  assert.equal(exported.version, 1);
  assert.equal(copy.ingest(exported.records).inserted, 2);
  assert.equal(copy.ingest(exported.records).duplicates, 2);
  assert.deepEqual(copy.exportData().records, exported.records);
  assert.deepEqual(copy.list().items, store.list().items);
});

test('large backups above ten thousand observations are accepted and fifty thousand is bounded', (t) => {
  const store = memory(t);
  const batch = Array.from({ length: 10001 }, () => record());
  const result = store.ingest(batch);
  assert.equal(result.inserted, 1);
  assert.equal(result.duplicates, 10000);
  assert.throws(() => store.ingest(new Array(50001)), /50000/);
  assert.equal(store.stats().observationCount, 1);
});

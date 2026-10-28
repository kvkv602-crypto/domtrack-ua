'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createPriceMonitor, canonicalUrl } = require('../lib/monitor.cjs');
const { createPreferences } = require('../lib/preferences.cjs');
const { kyivDate } = require('../lib/fx.cjs');

function setup(changes = {}) {
  let time = Date.now();
  const listing = { id: 1, source: 'domria', externalId: '12345678', url: 'https://dom.ria.com/uk/realty-12345678.html', title: 'Квартира', city: 'Київ', region: 'Київська', kind: 'apartment', deal: 'sale', period: 'total', status: 'active', price: 60000, currency: 'USD', lastSeen: new Date(time - 20000).toISOString(), ...changes.listing };
  const listings = new Map([[1, listing]]);
  const preferences = createPreferences(':memory:');
  preferences.update({ watch: { id: 1, active: true, targetPrice: changes.targetPrice === undefined ? 58000 : changes.targetPrice, currency: changes.currency || 'USD' } }, { hasListing: id => listings.has(id) });
  const watch = preferences.get().watches[0];
  time = Date.parse(watch.createdAt) + 10000;
  const ingested = []; const shown = []; const calls = []; const dates = [];
  let fetched = { ...listing, price: 57000, ...changes.fetched };
  const monitor = createPriceMonitor({
    store: { detail: id => listings.has(Number(id)) ? { listing: listings.get(Number(id)), history: [] } : null },
    preferences,
    fx: { getForDate: async date => { dates.push(date); return { asOf: date, usable: true, rates: { UAH: 1, USD: 40, EUR: 44 } }; } },
    getRia: () => ({ info: async (id, context) => { calls.push({ id, context }); if (changes.fetchError) throw changes.fetchError; return fetched; } }),
    webScanner: changes.webScanner,
    ingest: async records => { ingested.push(...records); for (const record of records) { const existing = listings.get(1); listings.set(1, { ...existing, ...record, id: 1, lastSeen: record.observedAt }); } },
    notify: async events => { shown.push(...events); },
    allListings: () => [...listings.values()],
    now: () => time,
    ...changes.options
  });
  return { monitor, preferences, listings, listing, ingested, shown, calls, dates, watch, setFetched: value => { fetched = value; }, setTime: value => { time = value; } };
}
test('does not alert above the threshold or when no threshold is set', async () => {
  for (const targetPrice of [56000, null]) {
    const s = setup({ targetPrice });
    const result = await s.monitor.check();
    assert.equal(result.updated, 1);
    assert.equal(s.shown.length, 0);
    await s.monitor.close();
  }
});
test('alerts at/below the threshold only once using preferences event-key deduplication', async () => {
  const s = setup();
  const first = await s.monitor.check();
  assert.equal(first.updated, 1);
  assert.equal(first.status.running, false);
  assert.equal(s.shown.length, 1);
  assert.equal(s.shown[0].type, 'targetprice');
  assert.equal(s.shown[0].listingId, 1);
  assert.match(s.shown[0].message, /57.*000.*USD/);
  await s.monitor.check(); await s.monitor.evaluate();
  assert.equal(s.shown.length, 1);
  assert.equal(s.preferences.get().notifications.length, 1);
  await s.monitor.close();
});
test('existing stale history does not trigger when a watch is created', async () => {
  const s = setup({ listing: { price: 50000 } });
  const result = await s.monitor.evaluate();
  assert.equal(result.triggered, 0);
  assert.equal(s.shown.length, 0);
  assert.equal(s.ingested.length, 0);
  await s.monitor.close();
});
test('new manually imported observations are eligible after watch creation', async () => {
  const s = setup();
  s.listings.set(1, { ...s.listing, price: 50000, lastSeen: new Date(Date.parse(s.watch.createdAt) + 1).toISOString() });
  assert.equal((await s.monitor.evaluate()).triggered, 1);
  await s.monitor.close();
});
test('converts using the NBU quote for the Kyiv date of the new observation', async () => {
  const s = setup({ targetPrice: 2300000, currency: 'UAH' });
  await s.monitor.check();
  assert.equal(s.shown.length, 1);
  assert.equal(s.shown[0].price, 57000);
  assert.equal(s.shown[0].currency, 'USD');
  assert.equal(s.shown[0].targetCurrency, 'UAH');
  assert.equal(s.shown[0].comparedPrice, 2280000);
  assert.equal(s.shown[0].usedFXasOf, kyivDate(s.ingested[0].observedAt));
  assert.deepEqual(s.dates, [kyivDate(s.ingested[0].observedAt)]);
  await s.monitor.close();
});
test('missing/stale FX never synthesizes conversion or a threshold notification', async () => {
  for (const fx of [{ getForDate: async () => { throw new Error('offline'); } }, { getForDate: async () => ({ asOf: '2000-01-01', rates: { UAH: 1, USD: 40, EUR: 44 } }) }]) {
    const s = setup({ currency: 'UAH', targetPrice: 3000000, options: { fx } });
    const result = await s.monitor.check();
    assert.equal(s.shown.length, 0);
    assert.equal(result.errors.length, 1);
    assert.match(s.monitor.status().checks['1'].lastError, /НБУ/);
    await s.monitor.close();
  }
});
test('unreachable and 404 listings do not ingest fake unchanged/sold observations', async () => {
  const s = setup({ fetchError: Object.assign(new Error('404'), { code: 'RIA_HTTP', statusCode: 404 }) });
  const result = await s.monitor.check();
  assert.equal(result.updated, 0);
  assert.equal(result.errors.length, 1);
  assert.equal(s.ingested.length, 0);
  assert.equal(s.listings.get(1).status, 'active');
  assert.ok(s.monitor.status().checks['1'].lastError);
  await s.monitor.close();
});
test('web matching requires the watched canonical URL, rather than the first result', async () => {
  const seen = [];
  const s = setup({ listing: { source: 'manual:agent', externalId: 'original-7', url: 'https://example.com/item?id=7&utm_source=mail' }, webScanner: { scan: async spec => { seen.push(spec); return { records: [{ ...s.listing, source: 'web:example.com', externalId: 'other', url: 'https://example.com/item?id=8', price: 100 }, { ...s.listing, source: 'web:example.com', externalId: 'new-parser-id', url: 'https://example.com/item?id=7', price: 57000 }] }; } } });
  const result = await s.monitor.check();
  assert.equal(result.updated, 1);
  assert.equal(s.ingested[0].price, 57000);
  assert.equal(s.ingested[0].source, 'manual:agent');
  assert.equal(s.ingested[0].externalId, 'original-7');
  assert.equal(seen[0].maxPages, 1);
  assert.equal(seen[0].followLinks, false);
  await s.monitor.close();
});
test('web pages without the watched listing do not append any price observation', async () => {
  const s = setup({ listing: { source: 'web:example.com', url: 'https://example.com/watched' }, webScanner: { scan: async () => ({ records: [{ price: 1, currency: 'USD', url: 'https://example.com/different' }] }) } });
  const result = await s.monitor.check();
  assert.equal(result.updated, 0);
  assert.equal(s.ingested.length, 0);
  await s.monitor.close();
});
test('scheduler clamps intervals, creates no timer without watches and cancels on close', async () => {
  const timers = []; const cleared = [];
  const s = setup({ options: { intervalMinutes: () => 1, setTimeoutImpl: (fn, ms) => { const token = { fn, ms, unref() {} }; timers.push(token); return token; }, clearTimeoutImpl: token => cleared.push(token) } });
  assert.equal(s.monitor.schedule().intervalMinutes, 120);
  assert.equal(timers[0].ms, 120 * 60000);
  s.preferences.update({ watch: { id: 1, active: false } }, { hasListing: () => true });
  assert.equal(s.monitor.schedule().nextRun, null);
  assert.equal(timers.length, 1);
  assert.equal(cleared.length, 1);
  await s.monitor.close();
});
test('canonical URLs drop known tracking, retaining IDs with semantic meaning', () => {
  assert.equal(canonicalUrl('https://example.com/item?id=7&utm_source=a#top'), 'https://example.com/item?id=7');
  assert.notEqual(canonicalUrl('https://example.com/item?id=7'), canonicalUrl('https://example.com/item?id=8'));
});
test('concurrent checks are rejected and close waits for pending work', async () => {
  let release;
  let began;
  const gate = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { began = resolve; });
  const s = setup({ options: { getRia: () => ({ info: async () => { began(); await gate; return { ...s.listing, price: 57000 }; } }) } });
  const pending = s.monitor.check();
  await assert.rejects(s.monitor.check(), { statusCode: 409 });
  await started;
  let finished = false;
  const closing = s.monitor.close();
  closing.then(() => { finished = true; });
  await Promise.resolve();
  assert.equal(finished, false);
  release();
  await pending; await closing;
  assert.equal(s.monitor.status().running, false);
  assert.equal(s.monitor.status().nextRun, null);
});
test('rotates no more than ten active watches per batch', async () => {
  const preferences = createPreferences(':memory:');
  const listing = { source: 'domria', title: 'Квартира', city: 'Київ', region: 'Київська', kind: 'apartment', deal: 'sale', period: 'total', status: 'active', price: 60000, currency: 'USD' };
  const listings = new Map(Array.from({ length: 12 }, (_, index) => [index + 1, { ...listing, id: index + 1, externalId: String(index + 101), lastSeen: new Date().toISOString() }]));
  for (const id of listings.keys()) preferences.update({ watch: { id, active: true, targetPrice: null, currency: 'USD' } }, { hasListing: value => listings.has(value) });
  const calls = [];
  const monitor = createPriceMonitor({ store: { detail: id => ({ listing: listings.get(Number(id)) }) }, preferences, getRia: () => ({ info: async externalId => { calls.push(Number(externalId) - 100); return { ...listing, externalId }; } }), ingest() {}, notify() {} });
  assert.equal((await monitor.check()).checked, 10);
  assert.deepEqual(calls, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  await monitor.check();
  assert.deepEqual(calls.slice(10), [11, 12, 1, 2, 3, 4, 5, 6, 7, 8]);
  await monitor.close();
});
test('includes equality and permits one new notification when the target changes', async () => {
  const s = setup({ fetched: { price: 58000 } });
  await s.monitor.check();
  assert.equal(s.shown.length, 1);
  s.preferences.update({ watch: { id: 1, active: true, targetPrice: 59000, currency: 'USD' } }, { hasListing: () => true });
  await s.monitor.evaluate();
  assert.equal(s.shown.length, 2);
  assert.notEqual(s.shown[0].eventKey, s.shown[1].eventKey);
  await s.monitor.close();
});
test('a fresh cheaper user-confirmed duplicate can meet the watched object threshold', async () => {
  const s = setup({ listing: { address: 'вул. Шевченка, 10, кв. 15', area: 50, rooms: 2, floor: 3, floorsTotal: 9 } });
  s.listings.set(2, { ...s.listing, id: 2, source: 'web:example.com', externalId: 'duplicate-2', url: 'https://example.com/duplicate', price: 57000, lastSeen: new Date(Date.parse(s.watch.createdAt) + 1).toISOString() });
  s.preferences.update({ sameObjects: [1, 2] }, { hasListing: id => s.listings.has(id) });
  const result = await s.monitor.evaluate();
  assert.equal(result.triggered, 1);
  assert.equal(s.shown[0].listingId, 1);
  assert.equal(s.shown[0].observedListingId, 2);
  assert.equal(s.shown[0].price, 57000);
  assert.match(s.shown[0].message, /Связанное/);
  await s.monitor.close();
});
test('a cheaper duplicate observed before the watch does not meet a new threshold', async () => {
  const s = setup({ listing: { address: 'вул. Шевченка, 10, кв. 15', area: 50, rooms: 2, floor: 3, floorsTotal: 9 } });
  s.listings.set(1, { ...s.listing, lastSeen: new Date(Date.parse(s.watch.createdAt) + 1).toISOString() });
  s.listings.set(2, { ...s.listing, id: 2, source: 'web:example.com', externalId: 'old-duplicate', url: 'https://example.com/old', price: 100, lastSeen: new Date(Date.parse(s.watch.createdAt) - 1000).toISOString() });
  s.preferences.update({ sameObjects: [1, 2] }, { hasListing: id => s.listings.has(id) });
  assert.equal((await s.monitor.evaluate()).triggered, 0);
  await s.monitor.close();
});
test('a fresh cheaper high-similarity heuristic never triggers another listing watch', async () => {
  const s = setup({ listing: { address: 'вул. Шевченка, 10, кв. 15', area: 50, rooms: 2, floor: 3, floorsTotal: 9 } });
  s.listings.set(2, { ...s.listing, id: 2, source: 'web:example.com', externalId: 'possible-2', url: 'https://example.com/possible', price: 100, lastSeen: new Date(Date.parse(s.watch.createdAt) + 1).toISOString() });
  const analysis = require('../lib/duplicates.cjs').analyzeDuplicates([...s.listings.values()]);
  assert.equal(analysis.groups.length, 1);
  assert.equal((await s.monitor.evaluate()).triggered, 0);
  assert.equal(s.shown.length, 0);
  await s.monitor.close();
});
test('an explicit different-object rule prevents a cheaper match from triggering', async () => {
  const s = setup({ listing: { address: 'вул. Шевченка, 10, кв. 15', area: 50, rooms: 2, floor: 3, floorsTotal: 9 } });
  s.listings.set(2, { ...s.listing, id: 2, source: 'web:example.com', externalId: 'different-2', url: 'https://example.com/different', price: 100, lastSeen: new Date(Date.parse(s.watch.createdAt) + 1).toISOString() });
  s.preferences.update({ differentObjects: [1, 2] }, { hasListing: id => s.listings.has(id) });
  assert.equal((await s.monitor.evaluate()).triggered, 0);
  await s.monitor.close();
});
test('the watched source can trigger on its own price even beside an unconfirmed cheaper match', async () => {
  const s = setup({ listing: { address: 'вул. Шевченка, 10, кв. 15', area: 50, rooms: 2, floor: 3, floorsTotal: 9 } });
  const fresh = new Date(Date.parse(s.watch.createdAt) + 1).toISOString();
  s.listings.set(1, { ...s.listing, price: 57000, lastSeen: fresh });
  s.listings.set(2, { ...s.listing, id: 2, source: 'web:example.com', externalId: 'possible-2', url: 'https://example.com/possible', price: 100, lastSeen: fresh });
  assert.equal((await s.monitor.evaluate()).triggered, 1);
  assert.equal(s.shown[0].price, 57000);
  assert.equal(s.shown[0].observedListingId, 1);
  await s.monitor.close();
});
test('a reused web URL with a different publisher ID never rewrites the old object history', async () => {
  const s = setup({ listing: { source: 'web:example.com', externalId: 'apt-A', url: 'https://example.com/offer' }, webScanner: { scan: async () => ({ records: [{ ...s.listing, externalId: 'apt-B', price: 100 }] }) } });
  const result = await s.monitor.check();
  assert.equal(result.updated, 0);
  assert.equal(s.ingested.length, 0);
  assert.equal(s.shown.length, 0);
  assert.equal(s.listings.get(1).externalId, 'apt-A');
  assert.match(result.errors[0].message, /ID/);
  await s.monitor.close();
});
test('web refresh accepts only the exact original ID and URL', async () => {
  const s = setup({ listing: { source: 'web:example.com', externalId: 'apt-A', url: 'https://example.com/offer?utm_source=mail' }, webScanner: { scan: async () => ({ records: [{ ...s.listing, url: 'https://example.com/offer', price: 57000 }] }) } });
  const result = await s.monitor.check();
  assert.equal(result.updated, 1);
  assert.equal(s.ingested[0].externalId, 'apt-A');
  assert.equal(s.ingested[0].price, 57000);
  await s.monitor.close();
});
test('web refresh without a known original ID does not fabricate one from the URL', async () => {
  const s = setup({ listing: { source: 'web:example.com', externalId: null, url: 'https://example.com/offer' }, webScanner: { scan: async () => ({ records: [{ ...s.listing, externalId: 'apt-B', price: 100 }] }) } });
  assert.equal((await s.monitor.check()).updated, 0);
  assert.equal(s.ingested.length, 0);
  await s.monitor.close();
});

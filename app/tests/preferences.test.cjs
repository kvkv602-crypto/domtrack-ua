'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createPreferences } = require('../lib/preferences.cjs');
const { createStore } = require('../lib/store.cjs');

const hooks = { hasListing: (id) => id > 0 && id <= 1000, validateFilters: (filters) => { if (filters.currency && !['USD', 'UAH', 'EUR'].includes(filters.currency)) throw new Error('bad currency'); } };
function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'domtrack-preferences-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(os.tmpdir()));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return path.join(directory, 'preferences.json');
}

test('favorites, multilingual notes, searches, notifications and dedup survive restart', (t) => {
  const file = temporary(t);
  let preferences = createPreferences(file);
  const note = 'Київ: перевірити документи.\nПозвонить завтра 🙂 — 価格を確認';
  preferences.update({ favorite: { id: 12, active: true }, note: { id: 12, text: note }, saveSearch: { name: 'Київ · будинок', filters: { city: 'Київ', currency: 'USD', rooms: '3,4,5+' } } }, hooks);
  preferences.notify([{ type: 'pricedrop', message: 'Цена снизилась на 10%', listingId: 12, eventKey: 'drop-12-90000' }]);
  const before = preferences.get();
  preferences.close();
  preferences = createPreferences(file);
  assert.deepEqual(preferences.get(), before);
  assert.equal(preferences.get().notes['12'], note);
  assert.equal(preferences.notify([{ type: 'pricedrop', message: 'Повтор', eventKey: 'drop-12-90000' }]).duplicates, 1);
  assert.equal(fs.readdirSync(path.dirname(file)).filter((name) => name.includes('.tmp-')).length, 0);
});

test('invalid combined update is atomic in memory and on disk', (t) => {
  const file = temporary(t);
  const preferences = createPreferences(file);
  preferences.update({ favorite: { id: 1, active: true } }, hooks);
  const before = preferences.get();
  const bytes = fs.readFileSync(file, 'utf8');
  assert.throws(() => preferences.update({ favorite: { id: 2, active: true }, note: { id: 2, text: 'x'.repeat(4001) } }, hooks));
  assert.deepEqual(preferences.get(), before);
  assert.equal(fs.readFileSync(file, 'utf8'), bytes);
  assert.throws(() => preferences.update({ favorite: { id: 3, active: true }, saveSearch: { name: 'bad', filters: { currency: 'BTC' } } }, hooks));
  assert.deepEqual(preferences.get(), before);
});

test('listing existence and filter callbacks are required and invoked before saving', () => {
  const preferences = createPreferences(':memory:');
  assert.throws(() => preferences.update({ favorite: { id: -1, active: true } }, hooks));
  assert.throws(() => preferences.update({ favorite: { id: 1001, active: true } }, hooks), /не найдено/);
  assert.throws(() => preferences.update({ note: { id: 1, text: 'note' } }), /проверка/);
  assert.throws(() => preferences.update({ saveSearch: { name: 'Search', filters: {} } }), /проверка/);
  assert.deepEqual(preferences.get(), { favorites: [], notes: {}, notifications: [], savedSearches: [], watches: [], duplicateRules: { sameGroups: [], differentPairs: [] } });
});

test('saved search filters are checked against the real store query API', () => {
  const preferences = createPreferences(':memory:');
  const store = createStore(':memory:');
  try {
    const callbacks = { hasListing: () => true, validateFilters: (filters) => store.list(filters) };
    assert.throws(() => preferences.update({ saveSearch: { name: 'Broken', filters: { priceMin: 100 } } }, callbacks), /валюту/);
    preferences.update({ saveSearch: { name: 'Valid', filters: { currency: 'USD', priceMin: 100, rooms: '2,5+' } } }, callbacks);
    assert.equal(preferences.get().savedSearches.length, 1);
  } finally { store.close(); }
});

test('mark all read, favorite removal, note clearing and saved-search deletion work', () => {
  const preferences = createPreferences(':memory:');
  preferences.update({ favorite: { id: 1, active: true }, note: { id: 1, text: 'Проверить' }, saveSearch: { name: 'Search', filters: {} } }, hooks);
  preferences.notify([{ type: 'newmatch', message: 'Новое объявление', listingId: 1 }]);
  const searchId = preferences.get().savedSearches[0].id;
  const result = preferences.update({ favorite: { id: 1, active: false }, note: { id: 1, text: '' }, removeSearchId: searchId, markAllRead: true }, hooks);
  assert.deepEqual(result.favorites, []);
  assert.deepEqual(result.notes, {});
  assert.deepEqual(result.savedSearches, []);
  assert.equal(result.notifications[0].read, true);
});

test('notification batches validate atomically, deduplicate keys/IDs and bound the newest entries', () => {
  const preferences = createPreferences(':memory:');
  assert.throws(() => preferences.notify([{ type: 'newmatch', message: 'valid' }, { type: 'email', message: 'invalid' }]));
  assert.equal(preferences.get().notifications.length, 0);
  const result = preferences.notify([{ id: 'same-id', type: 'newmatch', message: 'one', eventKey: 'same-key' }, { type: 'newmatch', message: 'two', eventKey: 'same-key' }, { id: 'same-id', type: 'pricedrop', message: 'three' }]);
  assert.equal(result.added, 1);
  assert.equal(result.duplicates, 2);
  preferences.notify(Array.from({ length: 250 }, (_, index) => ({ type: 'newmatch', message: `Объявление ${index}`, eventKey: `new-${index}` })));
  const notifications = preferences.get().notifications;
  assert.equal(notifications.length, 200);
  assert.equal(notifications[0].message, 'Объявление 249');
  assert.equal(notifications.at(-1).message, 'Объявление 50');
  assert.equal(preferences.notify([{ type: 'newmatch', message: 'old', eventKey: 'same-key' }]).duplicates, 1);
});

test('favorites, notes and searches enforce their independent caps', () => {
  const preferences = createPreferences(':memory:');
  for (let index = 1; index <= 500; index++) preferences.update({ favorite: { id: index, active: true }, note: { id: index, text: 'Заметка' } }, hooks);
  assert.throws(() => preferences.update({ favorite: { id: 501, active: true } }, hooks), /500/);
  assert.throws(() => preferences.update({ note: { id: 501, text: 'new' } }, hooks), /500/);
  preferences.update({ favorite: { id: 1, active: true }, note: { id: 1, text: 'Updated' } }, hooks);
  for (let index = 0; index < 20; index++) preferences.update({ saveSearch: { name: `Поиск ${index}`, filters: {} } }, hooks);
  assert.throws(() => preferences.update({ saveSearch: { name: 'Too many', filters: {} } }, hooks), /20/);
});

test('malformed files fall back safely and prototype keys cannot pollute state', (t) => {
  const file = temporary(t);
  fs.writeFileSync(file, '{not-json');
  assert.deepEqual(createPreferences(file).get(), { favorites: [], notes: {}, notifications: [], savedSearches: [], watches: [], duplicateRules: { sameGroups: [], differentPairs: [] } });
  fs.writeFileSync(file, '{"favorites":[1,-1,"2"],"notes":{"__proto__":"bad","3":"Привет"},"savedSearches":[{"id":"bad","name":"bad","filters":{"__proto__":{"polluted":true}}}],"notifications":[]}');
  const preferences = createPreferences(file);
  assert.deepEqual(preferences.get().favorites, [1, 2]);
  assert.deepEqual(preferences.get().notes, { 3: 'Привет' });
  assert.equal(preferences.get().savedSearches.length, 0);
  assert.equal({}.polluted, undefined);
  assert.throws(() => preferences.update({ saveSearch: { name: 'bad', filters: JSON.parse('{"__proto__":"bad"}') } }, hooks));
  assert.throws(() => preferences.update(JSON.parse('{"__proto__":{"polluted":true}}'), hooks));
});

test('get returns independent snapshots and invalid timestamps/messages never persist', () => {
  const preferences = createPreferences(':memory:');
  preferences.update({ favorite: { id: 1, active: true }, note: { id: 1, text: 'original' } }, hooks);
  const snapshot = preferences.get(); snapshot.favorites.push(999); snapshot.notes['1'] = 'changed';
  assert.deepEqual(preferences.get().favorites, [1]);
  assert.equal(preferences.get().notes['1'], 'original');
  for (const event of [{ type: 'newmatch', message: 'x'.repeat(1001) }, { type: 'pricedrop', message: 'test', createdAt: '2026-02-30T10:00:00Z' }, { type: 'pricedrop', message: 'test', createdAt: new Date(Date.now() + 600000).toISOString() }, { type: 'cheaperduplicate', message: 'test', listingId: 0 }]) assert.throws(() => preferences.notify([event]));
  assert.equal(preferences.get().notifications.length, 0);
});

test('a failed atomic file replacement never commits the in-memory update or leaves temporary files', (t) => {
  const file = temporary(t);
  fs.mkdirSync(file);
  const preferences = createPreferences(file);
  assert.throws(() => preferences.update({ favorite: { id: 1, active: true } }, hooks));
  assert.deepEqual(preferences.get().favorites, []);
  assert.equal(fs.readdirSync(path.dirname(file)).filter((name) => name.includes('.tmp-')).length, 0);
});

test('price watches survive restart, update their threshold, and can be removed', (t) => {
  const file = temporary(t);
  let preferences = createPreferences(file);
  preferences.update({ watch: { id: 12, active: true, targetPrice: 80000, currency: 'USD' } }, hooks);
  const original = preferences.get().watches[0];
  assert.equal(original.targetPrice, 80000);
  assert.equal(original.active, true);
  preferences = createPreferences(file);
  assert.deepEqual(preferences.get().watches, [original]);
  preferences.update({ watch: { id: 12, active: true, targetPrice: 75000 } }, hooks);
  assert.equal(preferences.get().watches[0].targetPrice, 75000);
  assert.equal(preferences.get().watches[0].currency, 'USD');
  assert.equal(preferences.get().watches[0].createdAt, original.createdAt);
  preferences.update({ watch: { id: 12, active: true, targetPrice: null } }, hooks);
  assert.equal(preferences.get().watches[0].targetPrice, null);
  preferences.update({ watch: { id: 12, active: false } }, hooks);
  assert.deepEqual(preferences.get().watches, []);
});

test('watch validation requires existing IDs, a supported currency and bounded numeric thresholds', () => {
  const preferences = createPreferences(':memory:');
  for (const watch of [
    { id: 1001, active: true, currency: 'USD' },
    { id: 1, active: true, targetPrice: 50000 },
    { id: 1, active: true, currency: 'BTC' },
    { id: 1, active: true, currency: 'USD', targetPrice: '50000' },
    { id: 1, active: true, currency: 'USD', targetPrice: -1 },
    { id: 1, active: true, currency: 'USD', targetPrice: Infinity },
    { id: 1, active: true, currency: 'USD', targetPrice: 1e12 + 1 }
  ]) assert.throws(() => preferences.update({ favorite: { id: 2, active: true }, watch }, hooks));
  assert.deepEqual(preferences.get().favorites, []);
  assert.deepEqual(preferences.get().watches, []);
});

test('watches have an independent cap of one hundred and support all watch event types', () => {
  const preferences = createPreferences(':memory:');
  for (let index = 1; index <= 100; index++) preferences.update({ watch: { id: index, active: true, currency: 'UAH' } }, hooks);
  assert.throws(() => preferences.update({ watch: { id: 101, active: true, currency: 'UAH' } }, hooks), /100/);
  preferences.update({ watch: { id: 1, active: true, targetPrice: 0 } }, hooks);
  assert.equal(preferences.get().watches[0].targetPrice, 0);
  const result = preferences.notify(['targetprice', 'watcherror', 'currencychange'].map((type) => ({ type, listingId: 1, message: 'Проверка цены' })));
  assert.equal(result.added, 3);
});

test('manual duplicate rules persist, validate existing IDs, reject conflicts atomically and clear independently', (t) => {
  const file = temporary(t);
  let preferences = createPreferences(file);
  preferences.update({ sameObjects: [3, 1, 2], differentObjects: [4, 1], favorite: { id: 1, active: true } }, hooks);
  assert.deepEqual(preferences.get().duplicateRules, { sameGroups: [[1, 2, 3]], differentPairs: [[1, 4]] });
  preferences = createPreferences(file);
  assert.deepEqual(preferences.get().duplicateRules.sameGroups, [[1, 2, 3]]);
  const before = preferences.get();
  assert.throws(() => preferences.update({ differentObjects: [2, 1], note: { id: 1, text: 'must not save' } }, hooks), /разные/);
  assert.deepEqual(preferences.get(), before);
  assert.throws(() => preferences.update({ sameObjects: [1, 1001] }, hooks), /не найдено/);
  assert.throws(() => preferences.update({ sameObjects: [1, 1] }, hooks), /повторяться/);
  assert.throws(() => preferences.update({ sameObjects: Array.from({ length: 21 }, (_, index) => index + 1) }, hooks));
  preferences.update({ clearDuplicateRules: true }, hooks);
  assert.deepEqual(preferences.get().duplicateRules, { sameGroups: [], differentPairs: [] });
  assert.deepEqual(preferences.get().favorites, [1]);
});

test('loaded contradictory duplicate overrides retain cannot-link rules instead of unsafe merges', (t) => {
  const file = temporary(t);
  fs.writeFileSync(file, JSON.stringify({ duplicateRules: { sameGroups: [[1, 2], [3, 4], [0, 5]], differentPairs: [[2, 1], [4, 4]] } }));
  const preferences = createPreferences(file);
  assert.deepEqual(preferences.get().duplicateRules, { sameGroups: [[3, 4]], differentPairs: [[1, 2]] });
});

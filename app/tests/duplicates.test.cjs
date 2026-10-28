'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { analyzeDuplicates, isConfirmedGroup } = require('../lib/duplicates.cjs');

const listing = (changes = {}) => ({
  id: 1, source: 'source-a', externalId: 'first', url: 'https://a.test/first',
  title: 'Авторський лофт із терасою та бібліотекою на даху',
  city: 'Київ', region: 'Київська область', kind: 'apartment', deal: 'sale', status: 'active', period: 'total',
  address: 'вул. Шевченка, 10', rooms: 3, floor: 5, floorsTotal: 9, area: 100,
  price: 150000, currency: 'USD', lastSeen: '2026-10-20T10:00:00Z', ...changes
});
const pair = (changes = {}) => [listing(), listing({ id: 2, source: 'source-b', externalId: 'second', url: 'https://b.test/second', price: 140000, ...changes })];

test('high-confidence cross-source duplicate keeps the lowest original price and alternatives', () => {
  const result = analyzeDuplicates(pair());
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, 2);
  assert.equal(result.items[0].price, 140000);
  assert.equal(result.items[0].currency, 'USD');
  assert.equal(result.items[0].duplicateCount, 2);
  assert.equal(result.items[0].duplicateAlternatives[0].price, 150000);
  assert.equal(result.groups[0].representativeId, 2);
  assert.equal(result.groups[0].confidence, 'high');
  assert.equal(result.groups[0].label, 'Вероятный дубль');
  assert.equal(result.candidateCount, 2);
  assert.equal(result.collapsedCount, 1);
});

test('different apartments in the same building are never collapsed', () => {
  const records = pair();
  records[0].address += ', кв. 15';
  records[1].address += ', квартира 16';
  assert.equal(analyzeDuplicates(records).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair({ rooms: 2 })).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair({ floor: 6 })).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair({ floorsTotal: 12 })).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair({ address: 'вул. Шевченка, 12' })).collapsedCount, 0);
});

test('matching apartment numbers supply evidence even without a distinctive title', () => {
  const records = pair({ title: 'Квартира', address: 'вулиця Шевченка, буд. 10, квартира 15' });
  records[0].title = 'Продається квартира';
  records[0].address += ', кв. 15';
  const result = analyzeDuplicates(records);
  assert.equal(result.collapsedCount, 1);
  assert.ok(result.groups[0].reasons.includes('Совпадает номер квартиры'));
});

test('generic generated titles, missing address/house number, area, rooms or apartment floor are insufficient', () => {
  const generic = pair();
  generic[0].title = generic[1].title = 'Квартира · Київ · 3 кімн. · 100 м²';
  assert.equal(analyzeDuplicates(generic).groups.length, 0);
  for (const change of [{ address: null }, { address: 'вул. Шевченка' }, { area: null }, { rooms: null }, { floor: null }]) assert.equal(analyzeDuplicates(pair(change)).groups.length, 0);
});

test('same canonical URL is strong evidence but never overrides inconsistent required fields', () => {
  const records = pair({ url: 'https://a.test/first?utm_source=x#photo', title: 'Квартира' });
  records[0].title = 'Будь-який заголовок';
  assert.equal(analyzeDuplicates(records).collapsedCount, 1);
  records[1].floor = 8;
  assert.equal(analyzeDuplicates(records).collapsedCount, 0);
});

test('cities, regions, kinds, deal types, status and periods remain separate', () => {
  for (const change of [{ city: 'Одеса' }, { region: 'Інша область' }, { kind: 'house' }, { deal: 'rent' }, { status: 'inactive' }, { period: 'month' }]) assert.equal(analyzeDuplicates(pair(change)).collapsedCount, 0);
});

test('different currencies never hide each other without FX; dated FX selects the actual lower equivalent', () => {
  const records = pair({ price: 6000000, currency: 'UAH' });
  let result = analyzeDuplicates(records);
  assert.equal(result.items.length, 2);
  assert.equal(result.collapsedCount, 0);
  result = analyzeDuplicates(records, { rates: { UAH: 1, USD: 41, EUR: 45 }, asOf: '2026-10-28' });
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, 2);
  assert.equal(result.items[0].price, 6000000);
  assert.equal(result.items[0].currency, 'UAH');
  assert.equal(result.items[0].comparisonPrice, 6000000);
  assert.equal(result.items[0].comparisonCurrency, 'UAH');
  assert.equal(result.items[0].comparisonAsOf, '2026-10-28');
  assert.equal(result.items[0].duplicateAlternatives[0].price, 150000);
});

test('missing FX for a currency keeps that currency separate; malformed or undated rates are rejected', () => {
  assert.equal(analyzeDuplicates(pair({ currency: 'EUR' }), { rates: { USD: 41 }, asOf: '2026-10-28' }).items.length, 2);
  for (const options of [{ rates: { USD: 41 } }, { rates: { USD: 0 }, asOf: '2026-10-28' }, { rates: { USD: NaN }, asOf: '2026-10-28' }, { rates: { UAH: 2 }, asOf: '2026-10-28' }, { rates: { USD: 41 }, asOf: '2026-02-30' }]) assert.throws(() => analyzeDuplicates(pair(), options), (error) => error.statusCode === 400);
});

test('complete-link blocks the A~B and B~C but A!~C transitive chain', () => {
  const records = [...pair({ area: 100.4 }), listing({ id: 3, externalId: 'third', url: 'https://c.test/third', area: 100.8, price: 130000 })];
  assert.equal(analyzeDuplicates(records.slice(0, 2)).collapsedCount, 1);
  assert.equal(analyzeDuplicates(records.slice(1)).collapsedCount, 1);
  const result = analyzeDuplicates(records);
  assert.equal(result.groups.length, 1);
  assert.equal(result.groups[0].items.length, 2);
  assert.equal(result.items.length, 2);
  assert.equal(result.collapsedCount, 1);
});

test('equal prices prefer the more recent observation while retaining the first member position', () => {
  const records = [listing({ id: 8, externalId: 'unrelated', address: 'вул. Інша, 3' }), ...pair({ price: 150000, lastSeen: '2026-10-25T10:00:00Z' }), listing({ id: 9, externalId: 'other', address: 'вул. Третя, 8' })];
  const result = analyzeDuplicates(records);
  assert.deepEqual(result.items.map((item) => item.id), [8, 2, 9]);
});

test('houses do not need an apartment floor but conflicting known building floors are rejected', () => {
  const records = pair({ kind: 'house', floor: null, floorsTotal: 2 });
  records[0].kind = 'house'; records[0].floor = null; records[0].floorsTotal = 2;
  assert.equal(analyzeDuplicates(records).collapsedCount, 1);
  records[1].floorsTotal = 3;
  assert.equal(analyzeDuplicates(records).collapsedCount, 0);
});

test('analysis never mutates its input or its original prices and has stable group IDs', () => {
  const records = pair();
  const before = structuredClone(records);
  Object.freeze(records[0]); Object.freeze(records[1]); Object.freeze(records);
  const first = analyzeDuplicates(records);
  assert.deepEqual(records, before);
  const reordered = analyzeDuplicates([...records].reverse());
  assert.equal(first.groups[0].id, reordered.groups[0].id);
  assert.equal(first.items[0].price, 140000);
});

test('adversarial same-building batches have bounded analysis and preserve unexamined listings', () => {
  const records = Array.from({ length: 1000 }, (_, index) => listing({ id: index + 1, externalId: String(index), url: `https://example.test/${index}` }));
  const result = analyzeDuplicates(records);
  assert.equal(result.analysisTruncated, true);
  assert.ok(result.items.length > 1);
  assert.equal(result.collapsedCount + result.items.length, records.length);
  assert.equal(result.groups[0].items.length + result.items.length - 1, records.length);
});

test('a manual cannot-link splits an otherwise strong automatic group', () => {
  const records = pair();
  assert.equal(analyzeDuplicates(records).collapsedCount, 1);
  const result = analyzeDuplicates(records, { differentPairs: [[1, 2]] });
  assert.equal(result.collapsedCount, 0);
  assert.equal(result.items.length, 2);
});

test('user-confirmed sameness works without address or distinctive title, retaining the lowest price', () => {
  const records = pair({ title: 'Объявление', address: null, area: null, rooms: null, floor: null });
  records[0].title = 'Квартира'; records[0].address = null;
  const result = analyzeDuplicates(records, { sameGroups: [[1, 2]] });
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, 2);
  assert.equal(result.groups[0].confidence, 'user-confirmed');
  assert.equal(result.groups[0].label, 'Подтверждено пользователем');
  assert.deepEqual(result.groups[0].reasons, ['Пользователь подтвердил один объект']);
});

test('manual sameness retains city/kind/period/currency guards and cannot-link always wins', () => {
  for (const change of [{ city: 'Львів' }, { region: 'Інша область' }, { kind: 'house' }, { period: 'month' }, { currency: 'EUR' }]) assert.equal(analyzeDuplicates(pair(change), { sameGroups: [[1, 2]] }).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair(), { sameGroups: [[1, 2]], differentPairs: [[2, 1]] }).collapsedCount, 0);
  assert.equal(analyzeDuplicates(pair({ currency: 'UAH', price: 5000000 }), { sameGroups: [[1, 2]], rates: { USD: 41, UAH: 1 }, asOf: '2026-10-28' }).items[0].id, 2);
});

test('manual pair chains never infer unconfirmed missing links or override a cannot-link', () => {
  const records = [
    listing({ title: 'A', address: null }),
    listing({ id: 2, externalId: 'second', title: 'B', address: null }),
    listing({ id: 3, externalId: 'third', title: 'C', address: null })
  ];
  assert.equal(analyzeDuplicates(records, { sameGroups: [[1, 2], [2, 3]] }).groups[0].items.length, 2);
  const result = analyzeDuplicates(records, { sameGroups: [[1, 2, 3]], differentPairs: [[1, 2]] });
  assert.ok(result.groups.every((group) => !group.items.some((item) => item.id === 1) || !group.items.some((item) => item.id === 2)));
});

test('heuristic matches are not confirmed provenance, while explicit user confirmation is', () => {
  const probable = analyzeDuplicates(pair()).groups[0];
  assert.equal(isConfirmedGroup(probable), false);
  const confirmed = analyzeDuplicates(pair(), { sameGroups: [[1, 2]] }).groups[0];
  assert.equal(isConfirmedGroup(confirmed), true);
});

test('confirmed original URLs retain query IDs and hash routes, removing only known tracking', () => {
  const base = listing({ externalId: 'same-id', url: 'https://example.test/listing?id=7&utm_source=x' });
  const other = listing({ id: 2, externalId: 'same-id', url: 'https://example.test/listing?fbclid=tracking&id=7' });
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [base, other] }), true);
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [base, { ...other, url: 'https://example.test/listing?id=8' }] }), false);
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [base, { ...other, externalId: 'different-id' }] }), false);
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [{ ...base, externalId: null }, { ...other, externalId: null }] }), true);
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [{ ...base, externalId: null }, other] }), false);
  assert.equal(isConfirmedGroup({ confidence: 'high', items: [{ ...base, url: 'https://example.test/#/listing/1' }, { ...other, url: 'https://example.test/#/listing/2' }] }), false);
});

'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { getCatalog, resolveSearch, resolvePage, CatalogError } = require('../lib/catalog.cjs');

test('catalog exposes supported cities and aliases without fabricated all-market coverage', () => {
  const catalog = getCatalog();
  assert.equal(catalog.source.id, 'rieltor');
  assert.equal(catalog.cities.length, 24);
  assert.equal(new Set(catalog.cities.map(c => c.id)).size, catalog.cities.length);
  assert.match(catalog.coverage, /выборка одного источника/);
  assert.ok(catalog.cities.find(c => c.cityName === 'Запоріжжя').aliases.includes('Запорожье'));
  for (const city of catalog.cities) {
    assert.ok(city.cityName && city.regionName);
    for (const url of resolveSearch(city.id).urls) {
      const parsed = new URL(url);
      assert.equal(parsed.origin, 'https://rieltor.ua');
      assert.match(parsed.pathname, /^\/(?:[a-z0-9-]+\/)?(?:flats|houses)-sale\/$/);
      assert.equal(parsed.search, '');
    }
  }
  assert.equal(resolveSearch('Буча', 'apartment').urls[0], 'https://rieltor.ua/bucha-239/flats-sale/');
});

test('Russian and Ukrainian city aliases select the same canonical city without guessed URLs', () => {
  assert.deepEqual(resolveSearch('  КИЕВ  ', 'apartment'), resolveSearch('Київ', 'apartment'));
  assert.deepEqual(resolveSearch('Львов', 'house'), resolveSearch('Львів', 'house'));
  assert.deepEqual(resolveSearch('Днепропетровск', 'all'), resolveSearch('dnipro', 'both'));
  assert.equal(resolveSearch('Ирпень', 'house').urls[0], 'https://rieltor.ua/irpen/houses-sale/');
  assert.equal(resolveSearch('Одесса', 'apartment').regionName, 'Одеська');
  assert.equal(resolveSearch('Запорожье', 'house').urls[0], 'https://rieltor.ua/zaporozhje/houses-sale/');
});

test('the resolved seed set contains only first pages and stays within scanner seed limits', () => {
  const result = resolveSearch('Запоріжжя');
  assert.deepEqual(result.urls, ['https://rieltor.ua/zaporozhje/flats-sale/', 'https://rieltor.ua/zaporozhje/houses-sale/']);
  assert.equal(result.maxPages, 2);
  assert.equal(result.followLinks, false);
  assert.equal(resolveSearch('Харьков', 'apartment').urls.length, 1);
  assert.deepEqual(resolveSearch('Київ', 'all').urls, ['https://rieltor.ua/flats-sale/', 'https://rieltor.ua/houses-sale/']);
});

test('unsupported cities, rental kinds and URL-like inputs fail clearly instead of silently searching Kyiv', () => {
  for (const input of ['', 'Невідоме місто', 'https://127.0.0.1/', '../odessa', 'city?url=http://localhost', null, {}]) assert.throws(() => resolveSearch(input), CatalogError);
  assert.throws(() => resolveSearch('Київ', 'rent'), { code: 'CATALOG_KIND' });
});

test('callers cannot mutate catalog aliases used by future requests', () => {
  const first = getCatalog(); first.cities[0].aliases.push('evil'); first.cities.splice(0);
  assert.equal(getCatalog().cities.length, 24);
  assert.equal(resolveSearch('Київ', 'apartment').urls[0], 'https://rieltor.ua/flats-sale/');
  assert.throws(() => resolveSearch('evil'), { code: 'CATALOG_CITY_UNSUPPORTED' });
});

test('pagination derives later pages only from a supported city and property type', () => {
  assert.equal(resolvePage('Запорожье','apartment',1).url,'https://rieltor.ua/zaporozhje/flats-sale/');
  assert.equal(resolvePage('Запорожье','apartment',3).url,'https://rieltor.ua/zaporozhje/flats-sale/?page=3');
  assert.equal(resolvePage('Киев','house',4).url,'https://rieltor.ua/houses-sale/?page=4');
  for(const page of [0,-1,1.5,10001,'3',true,null])assert.throws(()=>resolvePage('Киев','house',page),{code:'CATALOG_PAGE'});
  assert.throws(()=>resolvePage('Киев','both',3),{code:'CATALOG_KIND'});
  assert.throws(()=>resolvePage('https://example.com/','house',3),{code:'CATALOG_CITY_UNSUPPORTED'});
});

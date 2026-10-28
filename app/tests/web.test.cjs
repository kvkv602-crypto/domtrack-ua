'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createWebScanner, isPublicAddress, safeUrl, parseRobots, robotsAllows, extractJsonLdRecords } = require('../lib/web.cjs');
const property = changes => ({ '@context': 'https://schema.org', '@type': 'Apartment', identifier: 'apt-101', name: 'Продаж квартири', address: { '@type': 'PostalAddress', addressLocality: 'Київ', addressRegion: 'Київська', addressCountry: 'UA', streetAddress: 'Прикладна 1' }, offers: { '@type': 'Offer', price: '58000', priceCurrency: 'USD', businessFunction: 'https://purl.org/goodrelations/v1#Sell' }, floorSize: { '@type': 'QuantitativeValue', value: '50', unitCode: 'MTK' }, numberOfRooms: '2', floorLevel: '3', numberOfFloors: '9', ...changes });
const html = node => `<html><head><title>Продаж житла</title><script type="application/ld+json">${JSON.stringify(node)}</script></head><body></body></html>`;
const response = (body, status = 200, contentType = 'text/html') => ({ status, headers: { 'content-type': contentType }, body });
const lookup = async () => [{ address: '93.184.216.34', family: 4 }];
function fixtureScanner(pages, additional = {}) {
  const calls = []; let time = 10_000_000;
  const scanner = createWebScanner({ lookupImpl: lookup, now: () => time, sleepImpl: async ms => { time += ms; }, requestImpl: async (url, target) => { calls.push({ url: url.href, target, at: time }); const value = pages[url.href]; if (!value) throw new Error('Unexpected fixture URL'); return typeof value === 'function' ? value() : value; }, ...additional });
  return { scanner, calls };
}

test('blocks private, loopback, link-local, mapped and reserved IP addresses', () => {
  for (const ip of ['0.0.0.0', '10.2.3.4', '100.64.0.1', '127.0.0.1', '169.254.169.254', '172.31.1.1', '192.168.1.2', '192.0.2.1', '198.18.0.1', '198.51.100.2', '203.0.113.1', '224.0.0.1', '255.255.255.255', '::', '::1', '::ffff:127.0.0.1', '::ffff:8.8.8.8', '64:ff9b::7f00:1', 'fc00::1', 'fe80::1', '2001:db8::1', '2002:7f00:1::1', '3fff::1']) assert.equal(isPublicAddress(ip), false, ip);
  for (const ip of ['8.8.8.8', '1.1.1.1', '93.184.216.34', '2606:4700:4700::1111', '2001:4860:4860::8888']) assert.equal(isPublicAddress(ip), true, ip);
});
test('URL parsing rejects disguised local hosts, credentials, unsafe ports and protocols', () => {
  for (const url of ['http://localhost/', 'http://localhost./', 'http://service.local/', 'http://2130706433/', 'http://0x7f000001/', 'http://0177.0.0.1/', 'http://[::ffff:127.0.0.1]/', 'https://user:secret@example.com/', 'file:///etc/passwd', 'http://example.com:3000/', 'https://example.com\\@localhost/']) assert.throws(() => safeUrl(url), { name: 'WebScannerError' }, url);
  assert.equal(safeUrl('https://example.com/listing#photos').href, 'https://example.com/listing');
});
test('all DNS addresses must be public, including mixed public/private answers', async () => {
  const { scanner, calls } = fixtureScanner({}, { lookupImpl: async () => [{ address: '93.184.216.34', family: 4 }, { address: '127.0.0.1', family: 4 }] });
  const result = await scanner.scan({ urls: ['https://example.com/'] });
  assert.equal(calls.length, 0);
  assert.equal(result.errors.length, 1);
  assert.match(result.errors[0].message, /непубличный/);
});
test('passes the validated DNS address to transport, and rechecks DNS on later requests', async () => {
  let dnsCalls = 0;
  const { scanner, calls } = fixtureScanner({ 'https://example.com/robots.txt': response('', 404, 'text/plain') }, { lookupImpl: async () => ++dnsCalls === 1 ? [{ address: '93.184.216.34', family: 4 }] : [{ address: '127.0.0.1', family: 4 }] });
  const result = await scanner.scan({ urls: ['https://example.com/'] });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].target.address, '93.184.216.34');
  assert.equal(calls[0].target.userAgent, 'DomTrackUA/1.0');
  assert.equal(result.records.length, 0);
  assert.equal(result.errors.length, 1);
});
test('robots matching honours specific agents, wildcards, longest matches and Allow ties', () => {
  const policy = parseRobots('User-agent: *\nDisallow: /\n\nUser-agent: DomTrackUA\nDisallow: /private\nAllow: /private/public\nDisallow: /*?secret=*\n');
  assert.equal(robotsAllows(policy, new URL('https://example.com/')), true);
  assert.equal(robotsAllows(policy, new URL('https://example.com/private')), false);
  assert.equal(robotsAllows(policy, new URL('https://example.com/%70rivate')), false);
  assert.equal(robotsAllows(policy, new URL('https://example.com/private/public')), true);
  assert.equal(robotsAllows(policy, new URL('https://example.com/a?secret=x')), false);
  const tied = parseRobots('User-agent: *\nDisallow: /a\nAllow: /a');
  assert.equal(robotsAllows(tied, new URL('https://example.com/a')), true);
});
test('robots 403 and errors fail closed without requesting HTML', async () => {
  for (const status of [403, 500]) {
    const { scanner, calls } = fixtureScanner({ 'https://example.com/robots.txt': response('', status, 'text/plain') });
    const result = await scanner.scan({ urls: ['https://example.com/'] });
    assert.equal(calls.length, 1);
    assert.equal(result.records.length, 0);
    assert.equal(result.pagesVisited, 0);
  }
});
test('robots denies protected pages and permits explicitly allowed pages', async () => {
  const { scanner, calls } = fixtureScanner({ 'https://example.com/robots.txt': response('User-agent: *\nDisallow: /private', 200, 'text/plain') });
  const result = await scanner.scan({ urls: ['https://example.com/private'] });
  assert.equal(calls.length, 1);
  assert.equal(result.errors.length, 1);
});

test('page results report each requested page independently, including HTTP failure', async () => {
  const {scanner}=fixtureScanner({
    'https://example.com/robots.txt':response('',404,'text/plain'),
    'https://example.com/list?page=3':response(html(property())),
    'https://example.com/list?page=4':response('Unavailable',503)
  });
  const result=await scanner.scan({urls:['https://example.com/list?page=3','https://example.com/list?page=4'],maxPages:2,followLinks:false});
  assert.equal(result.records.length,1);
  assert.deepEqual(result.pageResults.map(row=>({url:row.url,seedIndex:row.seedIndex,status:row.status,recordsFound:row.recordsFound,httpStatus:row.httpStatus})),[
    {url:'https://example.com/list',seedIndex:0,status:'ok',recordsFound:1,httpStatus:200},
    {url:'https://example.com/list',seedIndex:1,status:'error',recordsFound:0,httpStatus:503}
  ]);
});

test('unsupported markup and robots refusal remain retryable per-page outcomes', async () => {
  const unsupported=fixtureScanner({'https://example.com/robots.txt':response('',404,'text/plain'),'https://example.com/list':response('<html>JS app only</html>')});
  let result=await unsupported.scanner.scan({urls:['https://example.com/list'],maxPages:1});
  assert.equal(result.pageResults[0].status,'unsupported');assert.equal(result.pageResults[0].recordsFound,0);assert.equal(result.unsupportedPages.length,1);
  const blocked=fixtureScanner({'https://example.com/robots.txt':response('Forbidden',403,'text/plain')});
  result=await blocked.scanner.scan({urls:['https://example.com/list'],maxPages:1});
  assert.equal(result.pageResults[0].status,'error');assert.equal(result.pageResults[0].httpStatus,null);assert.equal(result.pagesVisited,0);assert.equal(blocked.calls.length,1);
});

function rieltorCardHtml(jsonLd=''){
 return `<html><head><script type="application/ld+json">${jsonLd||JSON.stringify({'@type':'BreadcrumbList',itemListElement:[]})}</script></head><body><div class="catalog-items-container"><div class="catalog-card" data-catalog-item-id="90000001"><a class="catalog-card-media" href="https://rieltor.ua/zaporozhje/flats-sale/view/90000001/"></a><strong class="catalog-card-price-title">35 000 $</strong><div class="catalog-card-address">Тестова вул., 1</div><div class="catalog-card-region"><a href="https://rieltor.ua/zaporozhje/flats-sale/">Запоріжжя</a></div><div class="catalog-card-details-row">2 кімнати</div><div class="catalog-card-details-row">54 / 32 / 8 м²</div><div class="catalog-card-details-row">поверх 3 з 9</div></div></div></body></html>`;
}

test('Rieltor HTML fallback supports later catalog pages that expose only breadcrumb JSON-LD',async()=>{
 const page='https://rieltor.ua/zaporozhje/flats-sale/?page=3';const {scanner}=fixtureScanner({'https://rieltor.ua/robots.txt':response('',404,'text/plain'),[page]:response(rieltorCardHtml())});
 const result=await scanner.scan({urls:[page],cityName:'Київ',regionName:'Київська',maxPages:1});
 assert.equal(result.records.length,1);assert.equal(result.records[0].city,'Запоріжжя');assert.equal(result.records[0].price,35000);assert.equal(result.records[0].area,54);assert.equal(result.pageResults[0].status,'ok');assert.equal(result.unsupportedPages.length,0);assert.ok(result.notices.some(text=>/видимого HTML/.test(text)));
});

test('HTML fallback neither activates on another host nor rescues contradictory rental JSON-LD',async()=>{
 const other=fixtureScanner({'https://example.com/robots.txt':response('',404,'text/plain'),'https://example.com/list':response(rieltorCardHtml())});
 assert.equal((await other.scanner.scan({urls:['https://example.com/list'],maxPages:1})).records.length,0);
 const page='https://rieltor.ua/zaporozhje/flats-sale/?page=3';const rental=property({offers:{price:20000,priceCurrency:'UAH',businessFunction:'https://purl.org/goodrelations/v1#Lease'}});
 const own=fixtureScanner({'https://rieltor.ua/robots.txt':response('',404,'text/plain'),[page]:response(rieltorCardHtml(JSON.stringify(rental)))});
 assert.equal((await own.scanner.scan({urls:[page],maxPages:1})).records.length,0);
});
test('extracts genuine housing offers with original price, units and no seller PII', () => {
  const node = property({ seller: { '@type': 'Person', name: 'Private owner', telephone: 'secret' }, description: 'private description', image: 'private image' });
  const { records } = extractJsonLdRecords(html(node), 'https://example.com/item');
  assert.equal(records.length, 1);
  const r = records[0];
  assert.equal(r.source, 'web:example.com');
  assert.equal(r.price, 58000);
  assert.equal(r.currency, 'USD');
  assert.equal(r.city, 'Київ');
  assert.equal(r.area, 50);
  assert.equal(r.rooms, 2);
  assert.equal(r.floorsTotal, 9);
  assert.equal(r.sellerType, 'unknown');
  assert.equal(JSON.stringify(r).includes('secret'), false);
  assert.equal(Object.hasOwn(r, 'description'), false);
});
test('accepts a typed RealEstateListing graph and explicit agent classification', () => {
  const building = property({ '@id': '#house', '@type': 'SingleFamilyResidence', offers: undefined });
  const listing = { '@type': 'RealEstateListing', mainEntity: { '@id': '#house' }, offers: { price: 100000, priceCurrency: 'EUR', businessFunction: 'https://purl.org/goodrelations/v1#Sell', seller: { '@type': 'RealEstateAgent' } } };
  const result = extractJsonLdRecords(html({ '@graph': [listing, building] }), 'https://example.com/house');
  assert.equal(result.records.length, 1);
  assert.equal(result.records[0].kind, 'house');
  assert.equal(result.records[0].sellerType, 'agent');
});
test('Product/Article offers and ambiguous property types do not become apartments', () => {
  for (const node of [property({ '@type': 'Product' }), property({ '@type': 'Article' }), property({ '@type': 'RealEstateListing' })]) assert.equal(extractJsonLdRecords(html(node), 'https://example.com/item').records.length, 0);
});
test('does not relabel rentals, per-metre prices, unknown currencies or foreign addresses', () => {
  for (const node of [property({ name: 'Оренда квартири' }), property({ offers: { price: 100, priceCurrency: 'USD', businessFunction: 'LeaseOut' } }), property({ offers: { price: 1000, priceCurrency: 'USD', priceSpecification: { '@type': 'UnitPriceSpecification', unitCode: 'MTK' } } }), property({ offers: { price: 58000, priceCurrency: 'BTC' } }), property({ address: { addressLocality: 'London', addressCountry: 'GB' } })]) assert.equal(extractJsonLdRecords(html(node), 'https://example.com/item', { cityName: 'Київ' }).records.length, 0);
});
test('uses context location with a notice and never overwrites the original locality', async () => {
  const { scanner } = fixtureScanner({ 'https://example.com/robots.txt': response('', 404, 'text/plain'), 'https://example.com/item': response(html(property({ address: undefined }))) });
  const result = await scanner.scan({ urls: ['https://example.com/item'], cityName: 'Львів', regionName: 'Львівська' });
  assert.equal(result.records[0].city, 'Львів');
  assert.equal(result.notices.some(text => /ваших настроек/.test(text)), true);
  const original = extractJsonLdRecords(html(property({ address: { addressLocality: 'Одеса' } })), 'https://example.com/item', { cityName: 'Київ' });
  assert.equal(original.records[0].city, 'Одеса');
});
test('follows same-origin HTML links only and limits depth to two', async () => {
  const pages = {
    'https://example.com/robots.txt': response('', 404, 'text/plain'),
    'https://example.com/': response('<a href="/one?x=1&amp;y=2">One</a><a href="https://elsewhere.example/">Other</a><a href="http://127.0.0.1/">Local</a>'),
    'https://example.com/one?x=1&y=2': response('<a href="/two">Two</a>'),
    'https://example.com/two': response(html(property()) + '<a href="/three">Three</a>')
  };
  const { scanner, calls } = fixtureScanner(pages);
  const result = await scanner.scan({ urls: ['https://example.com/'], followLinks: true, maxPages: 20 });
  assert.equal(result.pagesVisited, 3);
  assert.equal(result.urlsDiscovered, 2);
  assert.equal(result.records.length, 1);
  assert.equal(calls.some(call => call.url.includes('/three')), false);
  assert.equal(calls.every(call => call.url.startsWith('https://example.com/')), true);
  for (let i = 1; i < calls.length; i++) assert.ok(calls[i].at - calls[i - 1].at >= 2000);
});
test('declines redirects without contacting their targets', async () => {
  const { scanner, calls } = fixtureScanner({ 'https://example.com/robots.txt': response('', 404, 'text/plain'), 'https://example.com/': { status: 302, headers: { location: 'http://169.254.169.254/latest' }, body: '' } });
  const result = await scanner.scan({ urls: ['https://example.com/'] });
  assert.equal(calls.length, 2);
  assert.equal(result.errors.length, 1);
  assert.match(result.errors[0].message, /Перенаправление/);
});
test('caps discovered links and HTML visits; reports an incomplete scope', async () => {
  const links = Array.from({ length: 150 }, (_, i) => `<a href="/item-${i}">${i}</a>`).join('');
  const { scanner } = fixtureScanner({ 'https://example.com/robots.txt': response('', 404, 'text/plain'), 'https://example.com/': response(links) });
  const result = await scanner.scan({ urls: ['https://example.com/'], followLinks: true, maxPages: 1 });
  assert.equal(result.urlsDiscovered, 100);
  assert.equal(result.pagesVisited, 1);
  assert.equal(result.scope, 'configured-seeds');
  assert.equal(result.notices.some(text => /лимит/.test(text)), true);
});
test('diagnoses unsupported markup and rejects oversized responses', async () => {
  const { scanner } = fixtureScanner({ 'https://example.com/robots.txt': response('', 404, 'text/plain'), 'https://example.com/': response('<html>JavaScript required</html>') });
  const result = await scanner.scan({ urls: ['https://example.com/'] });
  assert.equal(result.unsupportedPages.length, 1);
  assert.equal(result.records.length, 0);
  const big = fixtureScanner({ 'https://example.com/robots.txt': response('x'.repeat(2 * 1024 * 1024 + 1), 200, 'text/plain') });
  assert.equal((await big.scanner.scan({ urls: ['https://example.com/'] })).errors.length, 1);
});
test('fallback identifiers remain stable when only the asking price changes', () => {
  const first = extractJsonLdRecords(html(property({ identifier: undefined })), 'https://example.com/item').records[0];
  const second = extractJsonLdRecords(html(property({ identifier: undefined, name: 'Продаж квартири — нова ціна 57000', offers: { price: 57000, priceCurrency: 'USD', businessFunction: 'Sell' } })), 'https://example.com/item').records[0];
  assert.equal(first.externalId, second.externalId);
});
test('robot wildcard matching handles literal punctuation and anchored endings without regex backtracking', () => {
  const policy = parseRobots('User-agent: *\nDisallow: /a*b$\nDisallow: /literal[1]');
  assert.equal(robotsAllows(policy, new URL('https://example.com/axxb')), false);
  assert.equal(robotsAllows(policy, new URL('https://example.com/axxbc')), true);
  assert.equal(robotsAllows(policy, new URL('https://example.com/literal[1]')), false);
  const emptyAgent = parseRobots('User-agent: *\nDisallow: /\n\nUser-agent:\nAllow: /');
  assert.equal(robotsAllows(emptyAgent, new URL('https://example.com/')), false);
});
test('graph references appearing after their definitions do not erase rich nodes', () => {
  const building = property({ '@id': '#building', '@type': 'House', offers: undefined });
  const listing = { '@type': 'RealEstateListing', mainEntity: { '@id': '#building' }, offers: { price: 90000, priceCurrency: 'USD', businessFunction: 'Sell' } };
  assert.equal(extractJsonLdRecords(html({ '@graph': [building, listing] }), 'https://example.com/house').records.length, 1);
});
test('caps attempts even when every discovered HTML page redirects', async () => {
  const pages = { 'https://example.com/robots.txt': response('', 404, 'text/plain'), 'https://example.com/': response('<a href="/a">A</a><a href="/b">B</a>'), 'https://example.com/a': response('', 302) };
  const { scanner, calls } = fixtureScanner(pages);
  const result = await scanner.scan({ urls: ['https://example.com/'], followLinks: true, maxPages: 2 });
  assert.equal(result.pagesAttempted, 2);
  assert.equal(calls.length, 3);
  assert.equal(calls.some(call => call.url.endsWith('/b')), false);
});
test('native transport supplies a pinned lookup and no cookies or authorization', async () => {
  const https = require('node:https');
  const { EventEmitter } = require('node:events');
  const original = https.request;
  const seen = [];
  let time = 10_000_000;
  https.request = (url, options, callback) => {
    const request = new EventEmitter();
    request.destroy = () => {};
    request.end = () => queueMicrotask(() => {
      options.lookup('example.com', {}, (error, address, family) => { assert.equal(error, null); seen.push({ address, family, headers: options.headers, origin: url.origin }); });
      const robots = url.pathname === '/robots.txt';
      const responseEvent = new EventEmitter();
      responseEvent.statusCode = robots ? 404 : 200;
      responseEvent.headers = { 'content-type': robots ? 'text/plain' : 'text/html' };
      responseEvent.destroy = () => {};
      callback(responseEvent);
      responseEvent.emit('data', Buffer.from(robots ? '' : html(property())));
      responseEvent.emit('end');
    });
    return request;
  };
  try {
    const scanner = createWebScanner({ lookupImpl: lookup, now: () => time, sleepImpl: async ms => { time += ms; } });
    const result = await scanner.scan({ urls: ['https://example.com/item'] });
    assert.equal(result.records.length, 1);
    assert.equal(seen.length, 2);
    for (const call of seen) {
      assert.equal(call.address, '93.184.216.34');
      assert.equal(call.family, 4);
      assert.equal(call.origin, 'https://example.com');
      assert.equal(Object.hasOwn(call.headers, 'Cookie'), false);
      assert.equal(Object.hasOwn(call.headers, 'Authorization'), false);
    }
  } finally { https.request = original; }
});
test('copies validated explicitly public seller contacts from the housing Offer', () => {
  const node = property({ offers: { price: 58000, priceCurrency: 'USD', businessFunction: 'Sell', seller: { '@type': 'RealEstateAgent', name: 'Олена Коваль', telephone: '+38 (067) 123-45-67', email: 'olena+sale@example.com', url: 'https://example.com/contact' } } });
  const record = extractJsonLdRecords(html(node), 'https://example.com/item').records[0];
  assert.equal(record.sellerName, 'Олена Коваль');
  assert.equal(record.contactPhone, '+38 (067) 123-45-67');
  assert.equal(record.contactEmail, 'olena+sale@example.com');
  assert.equal(record.contactUrl, 'https://example.com/contact');
});
test('resolves public contactPoint aliases and refuses masked phone entries', () => {
  const node = property({ seller: { '@id': '#seller' } });
  const seller = { '@id': '#seller', '@type': 'Person', givenName: 'Іван', familyName: 'Петренко', contactPoint: [{ telephone: '+380 67 XXX-XX-XX' }, { telephone: 'tel:+380-67-123-45-67', email: 'mailto:ivan@example.com', url: '/contacts' }] };
  const record = extractJsonLdRecords(html({ '@graph': [node, seller] }), 'https://example.com/item').records[0];
  assert.equal(record.sellerName, 'Іван Петренко');
  assert.equal(record.contactPhone, '+380-67-123-45-67');
  assert.equal(record.contactEmail, 'ivan@example.com');
  assert.equal(record.contactUrl, 'https://example.com/contacts');
});
test('public contact fields do not expose unsafe URLs, HTML, masked phones or multiline email', () => {
  const node = property({ seller: { name: 'Олена <img src=x onerror=alert(1)> Коваль', telephone: '+38067***4567', email: 'person@example.com\r\nBcc:attacker@example.com', url: 'javascript:alert(1)' } });
  const record = extractJsonLdRecords(html(node), 'https://example.com/item').records[0];
  assert.equal(record.sellerName, 'Олена Коваль');
  assert.equal(Object.hasOwn(record, 'contactPhone'), false);
  assert.equal(Object.hasOwn(record, 'contactEmail'), false);
  assert.equal(Object.hasOwn(record, 'contactUrl'), false);
  for (const url of ['https://user:password@example.com/', 'http://127.0.0.1/contact', 'data:text/html,x']) {
    const unsafe = extractJsonLdRecords(html(property({ seller: { url } })), 'https://example.com/item').records[0];
    assert.equal(Object.hasOwn(unsafe, 'contactUrl'), false);
  }
});
test('unrelated JSON-LD people and page publishers are not copied as listing contacts', () => {
  const graph = { '@graph': [property(), { '@type': 'Person', name: 'Unrelated person', telephone: '+380671234567', email: 'unrelated@example.com' }] };
  const record = extractJsonLdRecords(html(graph), 'https://example.com/item').records[0];
  for (const field of ['sellerName', 'contactPhone', 'contactEmail', 'contactUrl']) assert.equal(Object.hasOwn(record, field), false);
});
test('does not merge different catalogue apartments lacking unique URLs or published IDs', () => {
  const first = property({ identifier: undefined, name: 'Продаж квартири 50 м²', numberOfRooms: 2 });
  const second = property({ identifier: undefined, name: 'Продаж квартири 65 м²', numberOfRooms: 3, floorSize: { value: 65, unitCode: 'MTK' }, offers: { price: 75000, priceCurrency: 'USD', businessFunction: 'Sell' } });
  const result = extractJsonLdRecords(html({ '@graph': [first, second] }), 'https://example.com/catalogue');
  assert.equal(result.records.length, 0);
  assert.equal(result.reasons.some(reason => /несколько объектов/.test(reason)), true);
});
test('retains twenty catalogue apartments with distinct individual URLs', () => {
  const entries = Array.from({ length: 20 }, (_, index) => property({ identifier: undefined, url: `https://example.com/apartment-${index + 1}`, name: `Продаж квартири ${index + 1}`, offers: { price: 50000 + index * 1000, priceCurrency: 'USD', businessFunction: 'Sell' } }));
  const result = extractJsonLdRecords(html({ '@type': 'ItemList', itemListElement: entries }), 'https://example.com/catalogue');
  assert.equal(result.records.length, 20);
  assert.equal(new Set(result.records.map(record => record.externalId)).size, 20);
  assert.equal(new Set(result.records.map(record => record.url)).size, 20);
});
test('shared catalogue URL is not an individual URL, but explicit unique IDs remain supported', () => {
  const ambiguous = [property({ identifier: undefined, url: 'https://example.com/catalogue' }), property({ identifier: undefined, url: 'https://example.com/catalogue', numberOfRooms: 3 })];
  assert.equal(extractJsonLdRecords(html(ambiguous), 'https://example.com/catalogue').records.length, 0);
  const identified = [property({ identifier: 'apt-A' }), property({ identifier: 'apt-B', numberOfRooms: 3 })];
  assert.deepEqual(extractJsonLdRecords(html(identified), 'https://example.com/catalogue').records.map(record => record.externalId), ['apt-A', 'apt-B']);
});
test('retains a single detail page without an explicit ID and resolves stable graph IDs', () => {
  assert.equal(extractJsonLdRecords(html(property({ identifier: undefined })), 'https://example.com/detail').records.length, 1);
  const entries = [property({ identifier: undefined, '@id': '#apt-A' }), property({ identifier: undefined, '@id': '#apt-B', numberOfRooms: 3 })];
  const result = extractJsonLdRecords(html(entries), 'https://example.com/catalogue');
  assert.equal(result.records.length, 2);
  assert.equal(new Set(result.records.map(record => record.externalId)).size, 2);
});
test('blank-node graph IDs do not prove stable identity in a multi-object catalogue', () => {
  const entries = [property({ identifier: undefined, '@id': '_:b1' }), property({ identifier: undefined, '@id': '_:b2', numberOfRooms: 3 })];
  assert.equal(extractJsonLdRecords(html(entries), 'https://example.com/catalogue').records.length, 0);
});

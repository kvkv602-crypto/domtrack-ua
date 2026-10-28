'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {extractRieltorHtml,catalogLocation}=require('../lib/rieltor-html.cjs');
const PAGE='https://rieltor.ua/zaporozhje/flats-sale/?page=3';
function card(options={}){
 const o={id:'90000001',kind:'apartment',city:'Запоріжжя',citySlug:'zaporozhje',address:'Тестова вул., 12',price:'29 999 $',details:['4 кімнати','86 / 54 / 10 м²','поверх 5 з 9'],badge:'Без комісії',...options};
 const category=o.kind==='house'?'houses':'flats',href=o.href||`https://rieltor.ua/${o.citySlug}/${category}-sale/view/${o.id}/`;
 return `<div class="catalog-card ${o.hidden?'hide':''}" data-catalog-item-id="${o.declaredId||o.id}"><a class="catalog-card-media" href="${href}"></a><div class="catalog-card-content"><strong class="catalog-card-price-title" title="По курсу НБУ 1 375 469 грн">${o.price}</strong><div class="catalog-card-price-details">349 $/м²</div><div class="catalog-card-address">${o.address}</div>${o.noCity?'':`<div class="catalog-card-region"><a href="https://rieltor.ua/${o.citySlug}/${category}-sale/">${o.city}</a><a href="https://rieltor.ua/${o.citySlug}/${category}-sale/district/">Дніпровський р-н</a></div>`}<div class="catalog-card-details">${o.details.map(value=>`<div class="catalog-card-details-row">${value}</div>`).join('')}</div><div class="catalog-card-additional">${o.badge}</div><div class="catalog-card-author-phones hide">+380000000000</div><button data-mobile="DO-NOT-EXTRACT" onclick="throw Error('must not run')">Показати телефон</button></div></div>`;
}
function html(cards){return`<!doctype html><html><body><script>globalThis.__rieltorExecuted=true</script><div class="catalog-items-container">${cards}</div></body></html>`;}
function extract(markup,url=PAGE,context={}){return extractRieltorHtml(markup,url,{observedAt:'2026-10-28T00:00:00Z',...context});}

test('only official supported sale catalog pages and numeric pagination activate HTML fallback',()=>{
 assert.ok(catalogLocation(PAGE));assert.ok(catalogLocation('https://rieltor.ua/flats-sale/'));
 for(const url of ['https://rieltor.ua.evil/zaporozhje/flats-sale/','http://rieltor.ua/zaporozhje/flats-sale/','https://user:password@rieltor.ua/zaporozhje/flats-sale/','https://rieltor.ua/unknown/flats-sale/','https://rieltor.ua/zaporozhje/flats-rent/','https://rieltor.ua/zaporozhje/flats-sale/view/90000001/','https://rieltor.ua/zaporozhje/flats-sale/?redirect=http://localhost','https://rieltor.ua/zaporozhje/flats-sale/?page=2&page=3'])assert.equal(extract(html(card()),url).supported,false,url);
});

test('visible full original price wins over per-square-metre and NBU converted amounts',()=>{
 const result=extract(html(card()));assert.equal(result.records.length,1);const record=result.records[0];
 assert.equal(record.price,29999);assert.equal(record.currency,'USD');assert.equal(record.area,86);assert.equal(record.rooms,4);assert.equal(record.floor,5);assert.equal(record.floorsTotal,9);assert.equal(record.period,'total');assert.equal(record.deal,'sale');assert.equal(record.region,'Запорізька');assert.equal(record.district,'Дніпровський р-н');
 assert.equal(record.sellerType,'unknown');assert.equal(Object.hasOwn(record,'contactPhone'),false);assert.equal(Object.hasOwn(record,'sellerName'),false);assert.ok(!JSON.stringify(record).includes('DO-NOT-EXTRACT'));assert.ok(!JSON.stringify(record).includes('380000000000'));assert.equal(globalThis.__rieltorExecuted,undefined);
});

test('ambiguous, rental, per-metre, nonpositive and unknown-currency prices are never invented',()=>{
 for(const price of ['349 $/м²','29 999 $ / місяць','Договірна','0 $','-5 $','29 999 GBP','29 999 $ 1 375 469 грн'])assert.equal(extract(html(card({price}))).records.length,0,price);
 const result=extract(html(card({price:'29 999,50 €'})));assert.equal(result.records[0].price,29999.5);assert.equal(result.records[0].currency,'EUR');
});

test('city and suburb come from visible locality links, not the selected search city',()=>{
 const result=extract(html(card({city:'Бабурка',citySlug:'baburka'})),PAGE,{cityName:'Запоріжжя',regionName:'Запорізька'});assert.equal(result.records[0].city,'Бабурка');assert.equal(result.records[0].region,'');
 assert.equal(extract(html(card({noCity:true})),PAGE,{cityName:'Запоріжжя',regionName:'Запорізька'}).records.length,0);
});

test('HTML fallback keeps the existing JSON-LD ID formula and ignores a city prefix and building suffix',()=>{
 const a=extract(html(card())).records[0],b=extract(html(card({address:'Запоріжжя, Тестова вул., 12'}))).records[0];
 const expected=crypto.createHash('sha256').update(JSON.stringify([a.url,'','Запоріжжя','Тестова вул.','apartment'])).digest('hex').slice(0,24);
 assert.equal(a.externalId,expected);assert.equal(b.externalId,expected);
 assert.equal(extract(html(card({declaredId:'90000002'}))).records.length,0);
 assert.equal(extract(html(card({href:'https://other.example/zaporozhje/flats-sale/view/90000001/'}))).records.length,0);
});

test('exact duplicate cards are idempotent but conflicting prices for one portal ID are skipped',()=>{
 assert.equal(extract(html(card()+card())).records.length,1);
 const result=extract(html(card()+card({price:'31 000 $'})));assert.equal(result.records.length,0);assert.match(result.reasons.join(' '),/Противоречащие/);
});

test('hidden cards, hidden prices and unrelated footer cards are not imported',()=>{
 assert.equal(extract(html(card({hidden:true}))).records.length,0);
 const hiddenPrice=card().replace('class="catalog-card-price-title"','class="catalog-card-price-title hide"');assert.equal(extract(html(hiddenPrice)).records.length,0);
 assert.equal(extract(`<html><body>${card()}</body></html>`).records.length,0);
});

test('a Rieltor -hidden price or a hidden ancestor cannot replace the visible original price',()=>{
 const hidden='<div class="catalog-card-price-title -hidden">1 000 грн</div><div class="-hidden"><strong class="catalog-card-price-title">2 000 грн</strong></div>';
 const markup=card().replace('<strong class="catalog-card-price-title"',hidden+'<strong class="catalog-card-price-title"');
 const record=extract(html(markup)).records[0];assert.equal(record.price,29999);assert.equal(record.currency,'USD');
});

test('conflicting visible primary prices are rejected instead of choosing the first or cheapest',()=>{
 const conflict='<div class="catalog-card-price-title">1 000 грн</div>';
 const result=extract(html(card().replace('<strong class="catalog-card-price-title"',conflict+'<strong class="catalog-card-price-title"')));
 assert.equal(result.records.length,0);assert.match(result.reasons.join(' '),/Противоречащие видимые/);
 const identical='<div class="catalog-card-price-title">29 999 $</div>';
 assert.equal(extract(html(card().replace('<strong class="catalog-card-price-title"',identical+'<strong class="catalog-card-price-title"'))).records[0].price,29999);
});

test('only explicit visible seller badges classify an owner or developer',()=>{
 assert.equal(extract(html(card({badge:'Власник'}))).records[0].sellerType,'owner');
 assert.equal(extract(html(card({badge:'Забудовник'}))).records[0].sellerType,'developer');
 assert.equal(extract(html(card({badge:'Не власник'}))).records[0].sellerType,'unknown');
 assert.equal(extract(html(card({badge:'Без комісії'}))).records[0].sellerType,'unknown');
 const hiddenOwner=card({badge:'Власник'}).replace('class="catalog-card-additional"','class="catalog-card-additional hide"');assert.equal(extract(html(hiddenOwner)).records[0].sellerType,'unknown');
});

test('house fields are taken only from explicit rows and land area is not house area',()=>{
 const url='https://rieltor.ua/zaporozhje/houses-sale/?page=3';
 let record=extract(html(card({kind:'house',details:['5 кімнат','165 м²','2 поверхи','Ділянка 6 сот']})),url).records[0];assert.equal(record.area,165);assert.equal(record.rooms,5);assert.equal(record.floorsTotal,2);assert.equal(Object.hasOwn(record,'floor'),false);
 record=extract(html(card({kind:'house',details:['Ділянка 600 м²','Студія']})),url).records[0];assert.equal(Object.hasOwn(record,'area'),false);assert.equal(Object.hasOwn(record,'rooms'),false);assert.equal(Object.hasOwn(record,'floorsTotal'),false);
});

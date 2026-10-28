'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const registry=require('../lib/source-registry.cjs');
const {extractDomriaHtml}=require('../lib/domria-html.cjs');
const {extractObyavaHtml}=require('../lib/obyava-html.cjs');
const {createWebScanner}=require('../lib/web.cjs');
const DOM='https://dom.ria.com/uk/prodazha-kvartir/zaporozhye/',OBY='https://obyava.ua/ru/nedvizhimost/prodazha-kvartir/zaporozhe';
const observe={observedAt:'2026-10-28T00:00:00.000Z'};
function domCard(o={}){o={id:'900001',subtype:'kvartira',city:'Запоріжжя',price:'28 000 $',...o};return`<section class="realty-item"><b class="size22" data-tm="realty-full-price">${o.price}</b><span data-tm="realty-item-price">560 $ за м²</span><div class="tit"><a href="${o.url||`/uk/realty-prodaja-${o.subtype}-zaporoje-test-${o.id}.html`}">Тестова вулиця, 1</a></div><span data-level="area" class="mb-5 i-block p-rel u-line">Тестовий район</span>${o.noCity?'':`<span class="mb-5 i-block">${o.city}</span>`}<a class="mb-5 i-block p-rel u-line" href="/uk/prodazha-kvartir/zaporozhye-zhk-test/">ЖК Тестовий</a><div class="realty-char">2 кімнати</div><div class="realty-char">50.5 м²</div><div class="realty-char">4 поверх з 9</div><div class="label-item">${o.badge||'Без комісії'}</div><div hidden><b data-tm="realty-full-price">1 грн</b>+380000000000</div><button data-phone="DO-NOT-EXTRACT">Телефон</button></section>`;}
function domHtml(cards){return`<html><body><div class="search-result-list">${cards}</div><script>globalThis.__publicSourceScript=true</script></body></html>`;}
function obyCard(o={}){o={id:'900001',title:'Продам квартиру',slug:'prodam-kvartiru',city:'Запорожье',price:'1 100 000 ГРН',params:'2 комнатная | Вторичное жилье | 50.5 м2 | 4 эт.',...o};return`<div class="single-item"><h2><a class="single-item__title" href="${o.url||`https://obyava.ua/ru/${o.slug}-${o.id}.html`}">${o.title}</a></h2><div class="single-item__price">${o.price}</div><div class="single-item__params">${o.params}</div>${o.noCity?'':`<div class="single-item__location">${o.city}</div>`}<div class="hidden">+380000000000</div><button data-phone="DO-NOT-EXTRACT">Телефон</button></div>`;}
function obyHtml(cards){return`<html><body><div class="category-page__items js-classifieds-list">${cards}</div></body></html>`;}
const dom=(html,url=DOM,context={})=>extractDomriaHtml(html,url,{...observe,...context});
const oby=(html,url=OBY,context={})=>extractObyavaHtml(html,url,{...observe,...context});

test('registry lists only verified providers, canonicalises city aliases and safely describes unknown cities',()=>{
 assert.deepEqual(registry.getSources('Запорожье').filter(x=>x.available).map(x=>x.id),['rieltor','domria-web','obyava']);
 assert.deepEqual(registry.getSources('Киев').filter(x=>x.available).map(x=>x.id),['rieltor']);
 assert.ok(registry.getSources('Неизвестный город').every(x=>!x.available&&x.reason));
 for(const source of ['domria-web','obyava']){const first=registry.resolvePage(source,'Запорожье','apartment',1),second=registry.resolvePage(source,'Запоріжжя','apartment',2);assert.equal(new URL(first.url).search,'');assert.equal(new URL(second.url).search,'?page=2');assert.equal(second.sourceId,source);assert.equal(second.cityName,'Запоріжжя');assert.equal(second.followLinks,false);assert.throws(()=>registry.resolvePage(source,'Киев','house',2));}
 for(const args of [['https://127.0.0.1','Запорожье','house',1],['domria-web','Запорожье','both',1],['obyava','Запорожье','house',0],['obyava','Запорожье','house',10001]])assert.throws(()=>registry.resolvePage(...args));
});

test('provider fallback guards exact origins, known sale routes and numeric pagination',()=>{
 for(const [extract,valid,card]of [[dom,DOM,domHtml(domCard())],[oby,OBY,obyHtml(obyCard())]]){assert.equal(extract(card,valid+'?page=2').records.length,1);for(const url of [valid.replace('https:','http:'),valid.replace('.com/','.com.evil/').replace('obyava.ua/','obyava.ua.evil/'),valid+'?page=2&page=3',valid+'?redirect=http://localhost',valid+'?page=0',valid.replace('/prodazha-','/arenda-'),valid.replace('https://','https://user:secret@')])assert.equal(extract(card,url).supported,false,url);}
});

test('DOMRIA full visible primary price, fields and real ID survive nearby per-metre prices and complex links',()=>{
 const r=dom(domHtml(domCard())).records[0];assert.equal(r.price,28000);assert.equal(r.currency,'USD');assert.equal(r.source,'web:dom.ria.com');assert.equal(r.externalId,'900001');assert.equal(r.city,'Запоріжжя');assert.equal(r.region,'Запорізька');assert.equal(r.rooms,2);assert.equal(r.area,50.5);assert.equal(r.floor,4);assert.equal(r.floorsTotal,9);assert.equal(r.sellerType,'unknown');assert.equal(r.district,'Тестовий район');assert.equal(r.observedAt,observe.observedAt);
 assert.ok(!JSON.stringify(r).includes('380000000000'));assert.ok(!JSON.stringify(r).includes('DO-NOT-EXTRACT'));assert.equal(globalThis.__publicSourceScript,undefined);
 const linkCity=domCard().replace('<span class="mb-5 i-block">Запоріжжя</span>','<a data-level="city" class="mb-5 i-block p-rel u-line" href="/uk/prodazha-domov/zaporozhye-townhouse/">Запоріжжя</a>');assert.equal(dom(domHtml(linkCity)).records[0].city,'Запоріжжя');
});

test('hidden or conflicting DOMRIA primary prices cannot replace the full visible price',()=>{
 const hidden=domCard().replace('<b class="size22"','<div class="-hidden"><b data-tm="realty-full-price">1 грн</b></div><b class="size22"');assert.equal(dom(domHtml(hidden)).records[0].price,28000);
 assert.equal(dom(domHtml(domCard().replace('<b class="size22"','<b data-tm="realty-full-price">1 грн</b><b class="size22"'))).records.length,0);
 for(const price of ['500 $ за м²','28 000 $ / місяць','Договірна','0 $'])assert.equal(dom(domHtml(domCard({price}))).records.length,0,price);
});

test('DOMRIA does not invent selected-city locality or import shares and wrong kinds',()=>{
 for(const o of [{noCity:true},{city:'Тестове село'},{subtype:'dom'},{url:'https://evil.example/uk/realty-prodaja-kvartira-zaporoje-test-900001.html'},{url:'/uk/realty-prodaja-kvartira-rastuschee-test-900001.html'}])assert.equal(dom(domHtml(domCard(o)),DOM,{cityName:'Запоріжжя'}).records.length,0);
 const houses='https://dom.ria.com/uk/prodazha-domov/zaporozhye/';assert.equal(dom(domHtml(domCard({subtype:'chast-doma'})),houses).records.length,0);
 const townhouse=dom(domHtml(domCard({subtype:'taunhaus'})),houses).records[0];assert.equal(townhouse.kind,'house');assert.match(townhouse.title,/Таунхаус/);
});

test('same DOMRIA link may repeat but conflicting cards for one ID are excluded',()=>{
 const twice=domCard().replace('</div><span data-level','<a href="/uk/realty-prodaja-kvartira-zaporoje-test-900001.html">Тестова вулиця, 1</a></div><span data-level');assert.equal(dom(domHtml(twice)).records.length,1);
 assert.equal(dom(domHtml(domCard()+domCard())).records.length,1);assert.equal(dom(domHtml(domCard()+domCard({price:'29 000 $'}))).records.length,0);
});

test('OBYAVA uses its displayed currency without reverse-engineering original USD, with an explicit notice',()=>{
 const result=oby(obyHtml(obyCard()));const r=result.records[0];assert.equal(r.source,'web:obyava.ua');assert.equal(r.externalId,'900001');assert.equal(r.price,1100000);assert.equal(r.currency,'UAH');assert.equal(r.rooms,2);assert.equal(r.area,50.5);assert.equal(r.floor,4);assert.equal(Object.hasOwn(r,'floorsTotal'),false);assert.equal(r.sellerType,'unknown');assert.match(result.notices.join(' '),/исходная валюта продавца.*не подтверждены/);assert.ok(!JSON.stringify(r).includes('DO-NOT-EXTRACT'));
 assert.equal(oby(obyHtml(obyCard({price:'1 100 000 ГРН торг'}))).records[0].price,1100000);
});

test('OBYAVA category contamination cannot make a room, share or rent into a whole sale',()=>{
 for(const o of [{title:'Продам 1/2 части квартиры'},{slug:'prodam-1-2-chasti-kvartiry-centr'},{title:'Продам кімнату'},{slug:'prodam-komnatu-v-obshchezhitii-kosmos'},{title:'Продается комната в квартире',slug:'prodaetsya-komnata-v-kvartire'},{title:'Продажа комнаты',slug:'prodazha-komnaty'},{title:'Продажа половины квартиры',slug:'prodazha-poloviny-kvartiry'},{title:'Продажа, аренда квартиры',slug:'prodazha-arenda-kvartiry'},{title:'Сдам квартиру'}])assert.equal(oby(obyHtml(obyCard(o))).records.length,0,JSON.stringify(o));
});

test('OBYAVA does not guess a village, house area from land or floor from storey count',()=>{
 const houses='https://obyava.ua/ru/nedvizhimost/prodazha-domov/zaporozhe';for(const o of [{title:'Продам БУДИНОК в селі'},{slug:'prodaetsya-dom-v-sele-nedorogo'},{title:'Дом в дачном кооперативе'}])assert.equal(oby(obyHtml(obyCard(o)),houses).records.length,0);
 const r=oby(obyHtml(obyCard({title:'Продам дом',slug:'prodam-dom',params:'2 этажный | 2018 г. | 290 м2 | 8 соток'})),houses).records[0];assert.equal(r.area,290);assert.equal(r.floorsTotal,2);assert.equal(Object.hasOwn(r,'floor'),false);
 assert.equal(oby(obyHtml(obyCard({noCity:true})),OBY,{cityName:'Запоріжжя'}).records.length,0);
});

test('OBYAVA rejects conflicting or hidden primary prices, off-site IDs and duplicate conflicts',()=>{
 assert.equal(oby(obyHtml(obyCard().replace('class="single-item__price"','class="single-item__price -hidden"'))).records.length,0);
 assert.equal(oby(obyHtml(obyCard().replace('<h2>','<div class="single-item__price">1 ГРН</div><h2>'))).records.length,0);
 assert.equal(oby(obyHtml(obyCard({url:'https://evil.example/ru/prodam-kvartiru-900001.html'}))).records.length,0);
 assert.equal(oby(obyHtml(obyCard()+obyCard())).records.length,1);assert.equal(oby(obyHtml(obyCard()+obyCard({price:'1 200 000 ГРН'}))).records.length,0);
});

test('provider scanner dispatch supports incompatible housing JSONLD but still obeys robots and per-page outcomes',async()=>{
 const pages=new Map([[DOM,domHtml(domCard())+'<script type="application/ld+json">{"@type":["Offer","Apartment"],"price":28000,"priceCurrency":"USD"}</script>'],[OBY,obyHtml(obyCard())]]);
 let now=100000,blocked=false;const calls=[];const scanner=createWebScanner({lookupImpl:async()=>[{address:'93.184.216.34',family:4}],now:()=>now,sleepImpl:async ms=>{now+=ms;},requestImpl:async url=>{calls.push(url.href);return{status:200,headers:{'content-type':url.pathname==='/robots.txt'?'text/plain':'text/html'},body:url.pathname==='/robots.txt'?(blocked?'User-agent: *\nDisallow: /':'User-agent: *\nAllow: /'):pages.get(url.href)||''};}});
 const r=await scanner.scan({urls:[DOM,OBY],maxPages:2,followLinks:false});assert.equal(r.records.length,2);assert.deepEqual(r.pageResults.map(x=>x.status),['ok','ok']);assert.equal(r.errors.length,0);
 blocked=true;calls.length=0;const b=await scanner.scan({urls:[DOM],maxPages:1});assert.equal(b.records.length,0);assert.equal(b.pageResults[0].status,'error');assert.ok(calls.every(url=>new URL(url).pathname==='/robots.txt'));
});

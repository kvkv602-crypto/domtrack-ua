'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');
const {parseCSV,readImport}=require('../lib/import.cjs');
const {normalizeRates,createFxStore,kyivDate}=require('../lib/fx.cjs');
const {showWindowsNotifications}=require('../lib/notify.cjs');
test('CSV supports Ukrainian headers, decimal commas, quoted separators and seller labels',()=>{
 const rows=parseCSV('id;Заголовок;Місто;Область;Тип;Ціна;Валюта;Площа;Продавець\r\nabc;"Квартира; ""Світла""";Київ;Київська;квартира;"75 000,50";$;60,5;власник');
 assert.equal(rows[0].price,75000.5);assert.equal(rows[0].area,60.5);assert.equal(rows[0].title,'Квартира; "Світла"');assert.equal(rows[0].sellerType,'owner');assert.equal(rows[0].currency,'USD');
});
test('CSV rejects malformed quoting, duplicate columns, absent mandatory data and rent',()=>{
 for(const text of ['city,price,currency,kind\n"unclosed,1,USD,apartment','city,город,price,currency,kind\nКиїв,Київ,1,USD,apartment','price,currency\n1,USD','city,price,currency,kind,deal\nКиїв,1,USD,apartment,rent'])assert.throws(()=>parseCSV(text));
});
test('JSON import accepts the backup contract and defaults the source without guessing price',()=>{
 assert.equal(readImport({records:[{externalId:'x',city:'Київ',price:1} ]})[0].source,'import');
 assert.throws(()=>readImport({format:'other',version:1,records:[{}]}));assert.throws(()=>readImport({records:[{deal:'rent'}]}));
});
test('historical FX requests each exact date and preserves missing quotes',async()=>{
 const calls=[];const fx=createFxStore({fetchImpl:async url=>{calls.push(url);const d=new URL(url).searchParams.get('date');const iso=`${d.slice(6,8)}.${d.slice(4,6)}.${d.slice(0,4)}`;return new Response(JSON.stringify([{cc:'USD',rate:d.endsWith('01')?40:42,exchangedate:iso},{cc:'EUR',rate:44,exchangedate:iso}]));}});
 const history=[{observedAt:'2025-10-01T09:00:00Z',price:100,currency:'USD'},{observedAt:'2025-10-02T09:00:00Z',price:100,currency:'USD'}];
 assert.equal(fx.convertHistory(history)[0].priceUAH,null);const result=await fx.loadHistory(history);assert.deepEqual(result.history.map(h=>h.priceUAH),[4000,4200]);assert.equal(calls.length,2);assert.ok(calls.some(x=>x.includes('date=20251001')));assert.equal(result.history[0].price,100);
 await fx.getForDate('2025-10-01');assert.equal(calls.length,2);
});
test('NBU malformed/mismatched dates cannot silently supply a historical rate',async()=>{
 assert.throws(()=>normalizeRates([{cc:'USD',rate:NaN,exchangedate:'01.01.2025'}]));
 const fx=createFxStore({fetchImpl:async()=>new Response(JSON.stringify([{cc:'USD',rate:40,exchangedate:'02.10.2025'},{cc:'EUR',rate:44,exchangedate:'02.10.2025'}]))});
 await assert.rejects(fx.getForDate('2025-10-01'),/запрошено/);assert.equal(fx.status('2025-10-01').usable,false);
});
test('observation dates use Kyiv civil date, not UTC date',()=>{assert.equal(kyivDate('2025-10-01T22:30:00Z'),'2025-10-02');});
test('daily or monthly amounts cannot become total sale prices during import',()=>{
 assert.throws(()=>readImport({records:[{deal:'sale',period:'day'}]}),/total/);
 assert.throws(()=>parseCSV('city,price,currency,kind,period\nКиїв,100,USD,apartment,month'),/total/);
});
test('Windows notifications pass user text in stdin JSON to a fixed script file',()=>{
 const calls=[];const hostile="'; Start-Process calc; # `$(secret)";let input;
 const n=showWindowsNotifications([{type:'targetprice',message:hostile},{type:'pricedrop',message:'ignore'}],{platform:'win32',disabled:false,spawnImpl:(...args)=>{calls.push(args);return {on(){},unref(){},stdin:{on(){},end(value){input=value;}}};}});
 assert.equal(n,1);assert.equal(calls.length,1);assert.equal(calls[0][2].windowsHide,true);assert.ok(!calls[0][1].join(' ').includes(hostile));assert.ok(!calls[0][1].includes('-ExecutionPolicy'));
 assert.equal(calls[0][1].includes('-Command'),false);assert.equal(calls[0][1].includes('-File'),true);assert.equal(JSON.parse(input).text,hostile);
});

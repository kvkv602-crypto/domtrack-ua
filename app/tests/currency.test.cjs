'use strict';

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {createApp}=require('../server.cjs');
const {createFxStore}=require('../lib/fx.cjs');
const {planCurrency,currencyRows}=require('../lib/currency.cjs');
const DATE='2025-10-01';
const record=(externalId,price,currency,extra={})=>({source:'fx-fixture',externalId,title:'Світла квартира з лоджією',city:'Київ',region:'Київська',kind:'apartment',deal:'sale',price,currency,rooms:2,area:60,floor:4,floorsTotal:9,address:'вул. Садова, 12, кв. 7',observedAt:DATE+'T09:00:00Z',...extra});
function responseFor(date,usd=40,eur=50){const day=date.slice(6,8)+'.'+date.slice(4,6)+'.'+date.slice(0,4);return new Response(JSON.stringify([{cc:'USD',rate:usd,exchangedate:day},{cc:'EUR',rate:eur,exchangedate:day}]));}
async function setup(t,fxFetch){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'domtrack-currency-'));
  const monitor={status:()=>({}),schedule(){},evaluate:async()=>({}),close:async()=>{},check:async()=>({})};
  const keyVault={load:async()=>null,save:async()=>{},remove:async()=>{}};
  const app=createApp({dataDir:dir,monitor,keyVault,notifier:()=>{},fxFetch,sourceRegistry:{getSources:()=>[]}});const address=await app.start(0);const base='http://127.0.0.1:'+address.port;
  t.after(async()=>{await app.close();fs.rmSync(dir,{recursive:true,force:true});});
  const req=(route,method='GET',payload)=>fetch(base+route,{method,headers:{'X-App-Token':app.token,...(payload===undefined?{}:{'Content-Type':'application/json'})},...(payload===undefined?{}:{body:JSON.stringify(payload)})});
  const json=async route=>{const response=await req(route);assert.equal(response.status,200);return response.json();};
  const ingest=async records=>{const response=await req('/api/import','POST',{records});assert.equal(response.status,200,await response.text());};
  return {app,req,json,ingest};
}

test('changing display currency keeps original-currency ads, automatically fetches exact NBU and preserves history',async t=>{
  const calls=[];const {json,ingest}=await setup(t,async url=>{calls.push(url);return responseFor(new URL(url).searchParams.get('date'));});
  await ingest([record('usd',100,'USD'),record('eur',90,'EUR'),record('uah',5000,'UAH')]);
  const eur=await json('/api/listings?currency=EUR&fxDate='+DATE+'&sort=price-asc');
  assert.equal(eur.total,3);assert.deepEqual(eur.items.map(row=>row.externalId),['usd','eur','uah']);assert.deepEqual(eur.items.map(row=>row.convertedPrice),[80,90,100]);
  assert.ok(eur.items.every(row=>row.displayCurrency==='EUR'&&!row.rateUnavailable));assert.equal(eur.items[0].price,100);assert.equal(eur.items[0].currency,'USD');assert.equal(eur.items[0].fxDate,DATE);assert.equal(eur.items[1].fxDate,null);
  const uah=await json('/api/listings?currency=UAH&fxDate='+DATE+'&sort=price-asc');assert.equal(uah.total,3);assert.deepEqual(uah.items.map(row=>row.convertedPrice),[4000,4500,5000]);assert.equal(calls.length,1);assert.equal(new URL(calls[0]).origin,'https://bank.gov.ua');assert.equal(new URL(calls[0]).searchParams.get('date'),'20251001');
  const detail=await json('/api/listings/'+eur.items[0].id);assert.equal(detail.history[0].price,100);assert.equal(detail.history[0].currency,'USD');
  const exported=await json('/api/export');assert.deepEqual(exported.records.map(row=>[row.externalId,row.price,row.currency]).sort(),[['eur',90,'EUR'],['uah',5000,'UAH'],['usd',100,'USD']]);
});

test('price bounds and descending pagination compare converted values rather than source units',async t=>{
  const {json,ingest}=await setup(t,async url=>responseFor(new URL(url).searchParams.get('date')));
  await ingest([record('usd',100,'USD'),record('eur',80,'EUR'),record('uah',3000,'UAH')]);
  const filtered=await json('/api/listings?currency=USD&fxDate='+DATE+'&priceMin=90&priceMax=110&sort=price-desc&pageSize=1');
  assert.equal(filtered.total,2);assert.equal(filtered.items[0].convertedPrice,100);assert.equal(filtered.currencyComparison.priceFilterApplied,true);
  const next=await json('/api/listings?currency=USD&fxDate='+DATE+'&priceMin=90&priceMax=110&sort=price-desc&pageSize=1&page=2');assert.equal(next.total,2);assert.equal(next.items[0].convertedPrice,100);assert.notEqual(next.items[0].externalId,filtered.items[0].externalId);
});

test('missing FX never hides unbounded ads and exposes unverified budget rows without inventing prices',async t=>{
  let calls=0;const {json,ingest}=await setup(t,async()=>{calls++;throw Error('offline fixture');});
  await ingest([record('usd',100,'USD'),record('eur',90,'EUR'),record('uah',5000,'UAH')]);
  const all=await json('/api/listings?currency=EUR&fxDate='+DATE+'&sort=price-asc');assert.equal(all.total,3);assert.equal(all.items[0].currency,'EUR');assert.equal(all.currencyComparison.rateUnavailableCount,2);assert.ok(all.currencyComparison.retryAt>Date.now());
  assert.ok(all.items.filter(row=>row.currency!=='EUR').every(row=>row.convertedPrice===null&&row.rateUnavailable&&row.fxDate===null));
  const bounded=await json('/api/listings?currency=EUR&fxDate='+DATE+'&priceMax=50');assert.equal(bounded.total,2);assert.equal(bounded.currencyComparison.unverifiedPriceCount,2);assert.ok(bounded.items.every(row=>row.priceRangeUnverified));assert.match(bounded.currencyComparison.warning,/не проверено/);
  await json('/api/listings?currency=UAH&fxDate='+DATE);assert.equal(calls,1,'Repeated currency switches must not hammer a failed NBU date');
});

test('same-currency display works without requesting an unnecessary FX rate',async t=>{
  let calls=0;const {json,ingest}=await setup(t,async()=>{calls++;throw Error('must not fetch');});await ingest([record('eur',123,'EUR')]);
  const result=await json('/api/listings?currency=EUR&fxDate='+DATE+'&priceMax=200');assert.equal(result.total,1);assert.equal(result.items[0].convertedPrice,123);assert.equal(result.items[0].fxRate,1);assert.equal(result.items[0].rateUnavailable,false);assert.equal(calls,0);
});

test('another cached date cannot substitute for a missing exact date',async t=>{
  const {json,ingest}=await setup(t,async()=>responseFor('20251002'));
  await ingest([record('usd',100,'USD')]);const known=await json('/api/listings?currency=EUR&fxDate=2025-10-02');assert.equal(known.items[0].convertedPrice,80);
  const missing=await json('/api/listings?currency=EUR&fxDate='+DATE);assert.equal(missing.total,1);assert.equal(missing.items[0].convertedPrice,null);assert.equal(missing.items[0].rateUnavailable,true);assert.equal(missing.currencyComparison.date,DATE);
});

test('selected dated rates change budget matches and never alter the stored price',async t=>{
  const {json,ingest}=await setup(t,async url=>{const date=new URL(url).searchParams.get('date');return responseFor(date,date==='20251001'?40:50);});
  await ingest([record('usd',100,'USD')]);assert.equal((await json('/api/listings?currency=EUR&fxDate='+DATE+'&priceMax=90')).total,1);assert.equal((await json('/api/listings?currency=EUR&fxDate=2025-10-02&priceMax=90')).total,0);
  const all=await json('/api/listings?currency=EUR&fxDate=2025-10-02');assert.equal(all.items[0].convertedPrice,100);assert.equal(all.items[0].price,100);assert.equal(all.items[0].currency,'USD');
});

test('confirmed mixed-currency duplicates select the converted cheapest offer before pagination and retain favorites',async t=>{
  const {req,json,ingest}=await setup(t,async url=>responseFor(new URL(url).searchParams.get('date')));
  await ingest([record('usd',100,'USD'),record('eur',70,'EUR'),record('uah',3000,'UAH',{address:'вул. Інша, 99',title:'Інша квартира'} )]);
  const raw=await json('/api/listings?dedupe=false');const usd=raw.items.find(row=>row.externalId==='usd'),eur=raw.items.find(row=>row.externalId==='eur');
  assert.equal((await req('/api/preferences','POST',{sameObjects:[usd.id,eur.id],favorite:{id:usd.id,active:true}})).status,200);
  const first=await json('/api/listings?currency=EUR&fxDate='+DATE+'&sort=price-asc&pageSize=1');assert.equal(first.total,2);assert.equal(first.items[0].externalId,'uah');assert.equal(first.collapsedCount,1);
  const second=await json('/api/listings?currency=EUR&fxDate='+DATE+'&sort=price-asc&pageSize=1&page=2');assert.equal(second.items[0].externalId,'eur');assert.equal(second.items[0].convertedPrice,70);assert.equal(second.items[0].isFavorite,true);assert.equal(second.items[0].duplicateAlternatives[0].currency,'USD');assert.equal(second.items[0].duplicateAlternatives[0].convertedPrice,80);
  const favorites=await json('/api/listings?currency=EUR&fxDate='+DATE+'&favorites=true');assert.equal(favorites.total,1);assert.equal(favorites.items[0].externalId,'eur');
});

test('saved budget searches compare cross-currency prices but never claim unverified matches',async t=>{
  const {req,json,ingest}=await setup(t,async url=>responseFor(new URL(url).searchParams.get('date')));
  await ingest([record('usd',200,'USD')]);await json('/api/listings?currency=EUR&fxDate='+DATE);
  await req('/api/preferences','POST',{saveSearch:{name:'До 90 EUR',filters:{currency:'EUR',priceMax:90,fxDate:DATE}}});
  await ingest([record('usd',100,'USD',{observedAt:'2025-10-02T09:00:00Z'})]);
  const prefs=await json('/api/preferences');assert.ok(prefs.notifications.some(event=>event.type==='newmatch'));
  const offline=await setup(t,async()=>{throw Error('offline');});await offline.req('/api/preferences','POST',{saveSearch:{name:'Неизвестный бюджет',filters:{currency:'EUR',priceMax:90,fxDate:DATE}}});await offline.ingest([record('usd',50,'USD')]);assert.ok(!(await offline.json('/api/preferences')).notifications.some(event=>event.type==='newmatch'));
});

test('invalid display currencies, dates and price ranges fail before NBU requests',async t=>{
  let calls=0;const {req}=await setup(t,async()=>{calls++;throw Error('not expected');});
  for(const query of ['currency=BTC','currency=EUR&fxDate=2025-02-30','currency=EUR&priceMin=-1','currency=EUR&priceMin=200&priceMax=100','priceMax=100','sort=price-asc'])assert.equal((await req('/api/listings?'+query)).status,400,query);assert.equal(calls,0);
  assert.equal((await req('/api/preferences','POST',{saveSearch:{name:'Неверная дата',filters:{currency:'EUR',fxDate:'2025-02-30'}}})).status,400);
});

test('a failed FX date is retried only after the cooldown and recovery clears the failure',async()=>{
  let tick=1000,calls=0;const fx=createFxStore({now:()=>tick,fetchImpl:async()=>{calls++;if(calls===1)throw Error('offline');return responseFor('20251001');}});
  await assert.rejects(fx.getForDate(DATE));assert.equal(fx.status(DATE).usable,false);assert.equal(fx.status(DATE).retryAt,61000);
  await assert.rejects(fx.getForDate(DATE),error=>error.code==='FX_RETRY_WAIT');assert.equal(calls,1);tick=62000;const recovered=await fx.getForDate(DATE);assert.equal(calls,2);assert.equal(recovered.usable,true);assert.equal(recovered.retryAt,undefined);
});

test('conversion filters use the displayed cent precision at range boundaries',()=>{
  const plan=planCurrency({currency:'EUR',priceMin:'0.01',priceMax:'0.01'},DATE);const quote={usable:true,asOf:DATE,requestedDate:DATE,rates:{UAH:1,USD:1,EUR:2}};
  const result=currencyRows([{id:1,price:.01,currency:'USD',area:1}],plan,quote);assert.equal(result.items.length,1);assert.equal(result.items[0].convertedPrice,.01);assert.equal(result.items[0].price,.01);
});

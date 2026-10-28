'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {analyzeListing,buildDailySummary,dateKyiv}=require('../lib/insights.cjs');

const NOW='2026-10-28T12:00:00Z';
const listing=(overrides={})=>({id:1,source:'a',externalId:'1',url:'https://a.test/1',title:'Квартира',city:'Київ',region:'Київська область',district:'Шевченківський',kind:'apartment',deal:'sale',period:'total',price:100000,currency:'USD',area:100,rooms:3,floor:5,floorsTotal:9,address:'вул. Шевченка, 10',firstSeen:'2026-10-10T10:00:00Z',lastSeen:'2026-10-27T10:00:00Z',status:'active',...overrides});
const others=()=>[listing({id:2,externalId:'2',url:'https://a.test/2',address:'вул. Інша, 2',price:90000}),listing({id:3,externalId:'3',url:'https://a.test/3',address:'вул. Інша, 3',price:100000}),listing({id:4,externalId:'4',url:'https://a.test/4',address:'вул. Інша, 4',price:120000})];

test('freshness uses last successful observation, never publication date',()=>{
  const result=analyzeListing(listing({lastSeen:'2026-10-20T12:00:00Z',publishedAt:'2026-10-28T11:59:00Z'}),[],{now:NOW});
  assert.equal(result.freshness.stale,true);assert.equal(result.freshness.ageDays,8);assert.equal(result.freshness.lastChecked,'2026-10-20T12:00:00.000Z');
  assert.equal(analyzeListing(listing(),[],{now:NOW}).freshness.stale,false);
  assert.equal(analyzeListing(listing({lastSeen:'2026-10-21T12:00:00Z'}),[],{now:NOW}).freshness.stale,true);
});
test('unknown and invalid future lastSeen never appear fresh',()=>{
  for(const lastSeen of [null,undefined,'invalid','2026-10-29T10:00:00Z']){const f=analyzeListing(listing({lastSeen}),[],{now:NOW}).freshness;assert.equal(f.stale,null);assert.equal(f.ageDays,null);assert.equal(f.lastChecked,null);}
});
test('comparable median is based on at least three matching asking prices',()=>{
  const result=analyzeListing(listing(),others(),{now:NOW}).comparables;
  assert.equal(result.count,3);assert.equal(result.medianPricePerM2,1000);assert.equal(result.targetPricePerM2,1000);assert.equal(result.differencePercent,0);assert.match(result.methodLabel,/не подтверждённых сделок/);
});
test('comparable filtering keeps currency, district, rooms, type, area and active sale boundaries',()=>{
  const wrong=[{id:5,currency:'UAH'},{id:6,district:'Подільський'},{id:7,rooms:2},{id:8,area:121},{id:9,area:79},{id:10,kind:'house'},{id:11,city:'Львів'},{id:12,status:'inactive'},{id:13,deal:'rent'},{id:14,price:0},{id:15,rooms:null}].map(v=>listing({...v,externalId:String(v.id),url:'https://a.test/'+v.id}));
  const result=analyzeListing(listing(),[...others(),...wrong],{now:NOW}).comparables;
  assert.equal(result.count,3);assert.deepEqual(result.items.map(r=>r.id),[2,3,4]);
  const edge=listing({id:20,externalId:'20',url:'https://a.test/20',area:120,price:120000,address:'Крайня, 20'});
  assert.equal(analyzeListing(listing(),[...others(),edge],{now:NOW}).comparables.count,4);
});
test('insufficient sample and missing target area have no invented median',()=>{
  assert.equal(analyzeListing(listing(),others().slice(0,2),{now:NOW}).comparables.medianPricePerM2,null);
  const result=analyzeListing(listing({area:null}),others(),{now:NOW}).comparables;assert.equal(result.medianPricePerM2,null);assert.equal(result.differencePercent,null);assert.equal(result.targetPricePerM2,null);
});
test('user-confirmed target and comparable duplicates do not inflate sample',()=>{
  const target=listing({title:'Авторський лофт із терасою та бібліотекою на даху'});
  const targetClone=listing({id:8,source:'b',externalId:'8',url:'https://b.test/8',title:target.title,price:95000});
  const matching=others();matching[0].title='Пентхаус із бібліотекою панорамними вікнами та терасою';matching[0].address='вул. Інша, 12, кв. 5';
  const clone=listing({...matching[0],id:9,source:'b',externalId:'9',url:'https://b.test/9',price:89000});
  const result=analyzeListing(target,[targetClone,...matching,clone],{now:NOW,sameGroups:[[1,8],[2,9]]}).comparables;
  assert.equal(result.count,3);assert.ok(!result.items.some(r=>r.id===8||r.id===1));assert.equal(result.items.filter(r=>r.id===2||r.id===9).length,1);
});
test('high-confidence similarity does not automatically exclude or collapse original offers',()=>{
  const target=listing({title:'Авторський лофт із терасою та бібліотекою на даху'});
  const targetClone=listing({id:8,source:'b',externalId:'8',url:'https://b.test/8',title:target.title,price:95000});
  const matching=others();matching[0].title='Пентхаус із бібліотекою панорамними вікнами та терасою';matching[0].address='вул. Інша, 12, кв. 5';
  const clone=listing({...matching[0],id:9,source:'b',externalId:'9',url:'https://b.test/9',price:89000});
  const result=analyzeListing(target,[targetClone,...matching,clone],{now:NOW}).comparables;
  assert.equal(result.count,5);assert.ok(result.items.some(r=>r.id===8));assert.equal(result.items.filter(r=>r.id===2||r.id===9).length,2);
  assert.equal(result.medianPricePerM2,950);assert.equal(result.items.find(r=>r.id===2).price,90000);assert.equal(result.items.find(r=>r.id===9).price,89000);
  const different=analyzeListing(target,matching.concat(clone),{now:NOW,sameGroups:[[2,9]],differentPairs:[[2,9]]}).comparables;
  assert.equal(different.count,4);
});
test('source original ID confirms a repeated observation but URL similarity alone does not',()=>{
  const original=listing({id:7,url:'https://a.test/1?utm_source=x',address:'Інша, 9'});
  assert.equal(analyzeListing(listing(),[...others(),original],{now:NOW}).comparables.count,3);
  const unconfirmed=listing({id:7,source:'b',externalId:'7',url:'https://a.test/1?utm_source=x',address:'Інша, 9'});
  assert.equal(analyzeListing(listing(),[...others(),unconfirmed],{now:NOW}).comparables.count,4);
});
test('business query and hash identifiers remain distinct in original source URLs',()=>{
  const rows=[listing({id:7,externalId:null,url:'https://a.test/ad?listing=7#details',address:'Інша, 7'}),listing({id:8,externalId:null,url:'https://a.test/ad?listing=8#details',address:'Інша, 8'}),listing({id:9,externalId:null,url:'https://a.test/ad?listing=8#other',address:'Інша, 9'})];
  const result=analyzeListing(listing(),rows,{now:NOW}).comparables;
  assert.equal(result.count,3);assert.equal(result.items.find(r=>r.id===8).url,'https://a.test/ad?listing=8#details');
});
test('possible relisting requires strong physical evidence and nonoverlapping observations',()=>{
  const target=listing({firstSeen:'2026-10-20T12:00:00Z',address:'вул. Шевченка, 10, кв. 15'});
  const old=listing({id:20,source:'b',externalId:'20',url:'https://b.test/old',address:'вул. Шевченка, 10, квартира 15',firstSeen:'2026-09-01T10:00:00Z',lastSeen:'2026-10-10T12:00:00Z'});
  const result=analyzeListing(target,[old],{now:NOW}).possibleRelistings;assert.equal(result.length,1);assert.equal(result[0].confidence,'possible');assert.match(result[0].reason,/Снятие с публикации не подтверждено/);
  for(const change of [{floor:6},{rooms:2},{area:102},{address:'вул. Шевченка, 10, кв. 16'},{lastSeen:'2026-10-20T12:00:00Z'},{url:target.url}])assert.equal(analyzeListing(target,[{...old,...change}],{now:NOW}).possibleRelistings.length,0);
});
test('generic titles without unit number cannot claim a relisting',()=>{
  const target=listing({firstSeen:'2026-10-20T12:00:00Z'}),old=listing({id:30,externalId:'30',url:'https://a.test/30',lastSeen:'2026-10-10T12:00:00Z'});
  assert.equal(analyzeListing(target,[old],{now:NOW}).possibleRelistings.length,0);
});
test('Kyiv day boundary is used for summary, not UTC day',()=>{
  assert.equal(dateKyiv('2026-10-27T22:30:00Z'),'2026-10-28');
  const result=buildDailySummary([listing({firstSeen:'2026-10-27T22:30:00Z'})],{day:'2026-10-28',now:NOW,records:[],notifications:[{id:'t1',type:'targetprice',createdAt:'2026-10-27T22:40:00Z'},{id:'t1',type:'targetprice',createdAt:'2026-10-27T22:40:00Z'}]});
  assert.equal(result.newListings,1);assert.equal(result.targetsReached,1);assert.equal(result.timeZone,'Europe/Kyiv');
});
test('daily drops come from actual chronological price observations, not current change labels',()=>{
  const item=listing({firstSeen:'2026-10-01T10:00:00Z',lastSeen:'2026-10-28T10:00:00Z',price:90,previousPrice:100,changeAmount:-10});
  const row=(price,observedAt,changes={})=>({...item,price,observedAt,...changes});
  const records=[row(90,'2026-10-28T10:00:00Z'),row(100,'2026-10-01T10:00:00Z'),row(90,'2026-10-10T10:00:00Z')];
  assert.equal(buildDailySummary([item],{day:'2026-10-28',now:NOW,records}).priceDrops,0);
  records.push(row(80,'2026-10-28T11:00:00Z'));records.push(row(80,'2026-10-28T11:00:00Z'));
  const result=buildDailySummary([item],{day:'2026-10-28',now:NOW,records});assert.equal(result.priceDrops,1);assert.equal(result.priceDropEvents,1);
});
test('currency changes and absent histories never manufacture drops',()=>{
  const item=listing(),records=[{...item,price:100,currency:'USD',observedAt:'2026-10-27T10:00:00Z'},{...item,price:90,currency:'EUR',observedAt:'2026-10-28T10:00:00Z'}];
  assert.equal(buildDailySummary([item],{day:'2026-10-28',now:NOW,records}).priceDrops,0);
  const result=buildDailySummary([item],{day:'2026-10-28',now:NOW});assert.equal(result.priceDrops,null);assert.equal(result.priceDropHistoryAvailable,false);assert.match(result.text,/нет истории/);
  assert.throws(()=>buildDailySummary([],{day:'2026-02-30'}),/Некорректная дата/);
});
test('analysis does not modify source records or prices',()=>{
  const target=listing(),all=others(),copy=JSON.stringify({target,all});
  analyzeListing(target,all,{now:NOW});assert.equal(JSON.stringify({target,all}),copy);
});

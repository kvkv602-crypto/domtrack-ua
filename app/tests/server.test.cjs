'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const os=require('node:os');const path=require('node:path');
const {createApp}=require('../server.cjs');
const rieltorCatalog=require('../lib/catalog.cjs');
const singleRieltorRegistry={getSources:city=>{rieltorCatalog.resolveSearch(city);return [{id:'rieltor',name:'Rieltor.ua',url:'https://rieltor.ua/',available:true,kinds:['apartment','house']}];},resolvePage:(id,city,kind,page)=>{assert.equal(id,'rieltor');return rieltorCatalog.resolvePage(city,kind,page);}};
const record=(extra={})=>({source:'example',externalId:'a',title:'Світла простора тиха сонячна квартира з лоджією',city:'Київ',region:'Київська',kind:'apartment',deal:'sale',price:80000,currency:'USD',area:60,rooms:2,floor:4,floorsTotal:9,address:'вул. Садова, 12, кв. 7',sellerType:'owner',observedAt:'2025-10-01T09:00:00Z',...extra});
async function setup(t,options={}){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'domtrack-api-'));
 const monitor={status:()=>({running:false,checks:{},intervalMinutes:360}),schedule(){},evaluate:async()=>({}),close:async()=>{},check:async()=>({checked:0})};
 let vaulted=null;const keyVault={load:async()=>vaulted,save:async value=>{vaulted=value;},remove:async()=>{vaulted=null;}};
 const app=createApp({dataDir:dir,monitor,notifier:()=>{},keyVault,sourceRegistry:singleRieltorRegistry,...options});const address=await app.start(0);const url=`http://127.0.0.1:${address.port}`;
 t.after(async()=>{await app.close();fs.rmSync(dir,{recursive:true,force:true});});
 const req=(endpoint,method='GET',payload,headers={})=>fetch(url+endpoint,{method,headers:{'X-App-Token':app.token,...payload!==undefined?{'Content-Type':'application/json'}:{},...headers},body:payload===undefined?undefined:JSON.stringify(payload)});
 return {app,url,req,dir};
}
test('localhost API requires token and rejects hostile origins and hosts',async t=>{
 const {app,url,req}=await setup(t);const html=await fetch(url).then(r=>r.text());assert.ok(html.includes(app.token));assert.ok(!html.includes('__APP_TOKEN__'));
 assert.equal((await fetch(url+'/api/status')).status,403);assert.equal((await req('/api/status','GET',undefined,{Origin:'https://evil.example'})).status,403);
 const hostile=await new Promise((resolve,reject)=>{const q=require('node:http').request(url+'/api/status',{headers:{Host:'evil.example','X-App-Token':app.token}},r=>{r.resume();resolve(r.statusCode);});q.on('error',reject);q.end();});
 assert.equal(hostile,403);assert.equal((await req('/api/status')).status,200);
});
test('import, filters, history and backup work through HTTP with ID-only records',async t=>{
 const {req}=await setup(t);let r=await req('/api/import','POST',{records:[record(),record({price:76000,observedAt:'2025-10-02T09:00:00Z'})]});assert.equal(r.status,200,await r.text());
 const list=await req('/api/listings?city=Киев&currency=USD&priceMax=77000&sellerType=owner&rooms=2&areaMin=60&floorMin=4').then(r=>r.json());assert.equal(list.total,1);assert.equal(list.items[0].changeAmount,-4000);
 const detail=await req('/api/listings/'+list.items[0].id).then(r=>r.json());assert.equal(detail.history.length,2);assert.equal(detail.history[0].priceUAH,null);
 const backup=await req('/api/export').then(r=>r.json());assert.equal(backup.records.length,2);assert.equal(backup.format,'domtrack-ua');
 assert.equal((await req('/api/listings?priceMin=1')).status,400);
});
test('only confirmed clones collapse before pagination and retain alternatives and histories',async t=>{
 const {req}=await setup(t);await req('/api/import','POST',{records:[record(),record({source:'other',externalId:'b',price:70000})]});
 const possible=await req('/api/listings').then(r=>r.json());assert.equal(possible.total,2);
 await req('/api/preferences','POST',{sameObjects:possible.items.map(r=>r.id)});
 const collapsed=await req('/api/listings?pageSize=1').then(r=>r.json());assert.equal(collapsed.total,1);assert.equal(collapsed.rawTotal,2);assert.equal(collapsed.items[0].price,70000);assert.equal(collapsed.items[0].duplicateCount,2);
 const raw=await req('/api/listings?dedupe=false').then(r=>r.json());assert.equal(raw.total,2);
 const group=await req('/api/groups/'+collapsed.items[0].duplicateGroupId).then(r=>r.json());assert.equal(group.history.length,2);
});
test('favorites, notes and source-less CSV are real persisted operations',async t=>{
 const {req}=await setup(t);const csv='id;title;city;kind;price;currency;sellerType\nx;Учебный дом;Ірпінь;house;120000;USD;власник';
 assert.equal((await req('/api/import','POST',{csv})).status,200);const list=await req('/api/listings').then(r=>r.json());const id=list.items[0].id;
 assert.equal((await req('/api/preferences','POST',{favorite:{id,active:true},note:{id,text:'Позвонить в пятницу'}})).status,200);
 const prefs=await req('/api/preferences').then(r=>r.json());assert.deepEqual(prefs.favorites,[id]);assert.equal(prefs.notes[id],'Позвонить в пятницу');
 assert.equal((await req('/api/listings?favorites=true').then(r=>r.json())).total,1);
});
test('API key never appears in state or persisted settings and empty source fails honestly',async t=>{
 const {req,dir}=await setup(t);await req('/api/settings','POST',{apiKey:'test-only-secret-not-a-real-key'});const status=await req('/api/status').then(r=>r.text());assert.ok(!status.includes('test-only-secret'));assert.ok(!fs.readFileSync(path.join(dir,'settings.json'),'utf8').includes('test-only-secret'));
 await req('/api/settings','POST',{apiKey:''});assert.equal((await req('/api/collect','POST',{})).status,409);
});
test('published contacts survive import, while unsafe/masked contacts are rejected atomically',async t=>{
 const {req}=await setup(t);const data=record({contactPhone:'+380 00 000 00 00',contactEmail:'owner@example.invalid',contactUrl:'https://example.invalid/contact',sellerName:'Учебный продавец',district:'Печерський',latitude:50.45,longitude:30.52});
 assert.equal((await req('/api/import','POST',{records:[data]})).status,200);
 const list=await req('/api/listings?district=Печерський').then(r=>r.json());assert.equal(list.items[0].contactEmail,'owner@example.invalid');assert.equal(list.items[0].latitude,50.45);
 assert.equal((await req('/api/import','POST',{records:[record({externalId:'bad',contactPhone:'+38050****123'})]})).status,400);
 assert.equal((await req('/api/listings').then(r=>r.json())).total,1);
});
test('a known listing entering a saved price range produces a new-match notification',async t=>{
 const {req}=await setup(t);await req('/api/import','POST',{records:[record()]});
 await req('/api/preferences','POST',{saveSearch:{name:'До 75 тысяч',filters:{currency:'USD',priceMax:'75000'}}});
 await req('/api/import','POST',{records:[record({price:74000,observedAt:'2025-10-02T09:00:00Z'})]});
 const prefs=await req('/api/preferences').then(r=>r.json());assert.ok(prefs.notifications.some(n=>n.type==='newmatch'));
});
test('semantic URL IDs and hash routes remain separate observations without external IDs',async t=>{
 const {req}=await setup(t);await req('/api/import','POST',{records:[record({externalId:null,url:'https://example.invalid/flat?id=1'}),record({externalId:null,url:'https://example.invalid/flat?id=2'}),record({externalId:null,url:'https://example.invalid/#/flat/3'})]});
 assert.equal((await req('/api/listings?dedupe=false').then(r=>r.json())).total,3);
});

test('ordinary preferences do not postpone periodic price monitoring',async t=>{
 let schedules=0;
 const monitor={status:()=>({}),schedule(){schedules++;},evaluate:async()=>({}),close:async()=>{},check:async()=>({})};
 const {req}=await setup(t,{monitor});await req('/api/import','POST',{records:[record()]});
 const id=(await req('/api/listings').then(r=>r.json())).items[0].id;const initial=schedules;
 assert.equal((await req('/api/preferences','POST',{favorite:{id,active:true},note:{id,text:'Учебная заметка'}})).status,200);
 assert.equal(schedules,initial);
 assert.equal((await req('/api/preferences','POST',{watch:{id,active:true,targetPrice:70000,currency:'USD'}})).status,200);
 assert.equal(schedules,initial+1);
});

test('free API budget defaults to 100, validates and is passed to a recreated client',async t=>{
 const budgets=[];const {req}=await setup(t,{apiKey:'fake-budget-test-key',riaFactory:options=>{budgets.push(options.monthlyBudget);return {regions:async()=>[],cities:async()=>[]};}});
 await req('/api/ria/regions');assert.deepEqual(budgets,[100]);
 assert.equal((await req('/api/settings','POST',{collector:{monthlyBudget:999}})).status,400);
 assert.equal((await req('/api/settings','POST',{collector:{monthlyBudget:1000}})).status,200);
 assert.equal((await req('/api/ria/cities?stateId=10')).status,200);
 assert.deepEqual(budgets,[100,1000]);
});

test('launcher shutdown requires the owning process PID as well as the session token',async t=>{
 const {app,req}=await setup(t);
 const status=await req('/api/status').then(r=>r.json());assert.equal(status.serverPid,process.pid);
 assert.equal((await req('/api/shutdown','POST',{serverPid:process.pid+1})).status,403);
 assert.equal((await req('/api/status')).status,200);
 assert.equal((await req('/api/shutdown','POST',{serverPid:status.serverPid})).status,200);
 await app.close();assert.equal(app.server.listening,false);
});

test('saved API key is loaded on startup without exposing its value to the browser',async t=>{
 const existing='fake-persisted-key';const used=[];
 const {req}=await setup(t,{keyVault:{load:async()=>existing,save:async()=>{},remove:async()=>{}},riaFactory:options=>{used.push(options.apiKey);return {regions:async()=>[]};}});
 const response=await req('/api/status').then(r=>r.text());assert.ok(!response.includes(existing));assert.equal(JSON.parse(response).keyStorage.stored,true);assert.equal(JSON.parse(response).apiKeyConfigured,true);
 await req('/api/ria/regions');assert.deepEqual(used,[existing]);
});

test('failure to save a replacement preserves the existing working key and hides errors',async t=>{
 const existing='fake-existing-key',replacement='fake-replacement-key';const used=[];
 const {req}=await setup(t,{keyVault:{load:async()=>existing,save:async()=>{throw Error('do not leak '+replacement);},remove:async()=>{}},riaFactory:options=>{used.push(options.apiKey);return {regions:async()=>[]};}});
 const response=await req('/api/settings','POST',{apiKey:replacement});assert.equal(response.status,500);assert.ok(!(await response.text()).includes(replacement));
 await req('/api/ria/regions');assert.deepEqual(used,[existing]);assert.equal((await req('/api/status').then(r=>r.json())).keyStorage.stored,true);
});

test('unreadable protected storage leaves local features available and accepts explicit replacement',async t=>{
 let saved;const {req}=await setup(t,{keyVault:{load:async()=>{throw Error('corrupt vault');},save:async key=>{saved=key;},remove:async()=>{}}});
 let status=await req('/api/status').then(r=>r.json());assert.equal(status.apiKeyConfigured,false);assert.ok(status.keyStorage.error);
 assert.equal((await req('/api/settings','POST',{apiKey:'fake-new-key'})).status,200);assert.equal(saved,'fake-new-key');
 status=await req('/api/status').then(r=>r.json());assert.equal(status.keyStorage.error,null);assert.equal(status.keyStorage.stored,true);
});

test('discovery endpoint matches the live instance and stores no token, key or data path',async t=>{
 const {app,dir,req}=await setup(t,{apiKey:'fake-endpoint-key'});
 const file=path.join(dir,'instance.json'),raw=fs.readFileSync(file,'utf8'),endpoint=JSON.parse(raw);
 const status=await req('/api/status').then(r=>r.json());
 assert.deepEqual(Object.keys(endpoint).sort(),['apiVersion','appId','appVersion','dataId','instanceId','port','serverPid']);
 assert.equal(endpoint.appVersion,'1.3.0');assert.equal(endpoint.apiVersion,4);assert.equal(status.appVersion,'1.3.0');assert.equal(status.apiVersion,4);
 assert.equal(endpoint.appId,'domtrack-ua');assert.equal(endpoint.serverPid,process.pid);assert.equal(endpoint.port,app.server.address().port);
 assert.match(endpoint.instanceId,/^[a-f0-9]{32}$/);assert.match(endpoint.dataId,/^[a-f0-9]{64}$/);
 for(const field of ['appId','instanceId','dataId','serverPid'])assert.equal(endpoint[field],status[field]);
 let normalized=path.resolve(dir),root=path.parse(normalized).root;while(normalized.length>root.length&&/[\\/]$/.test(normalized))normalized=normalized.slice(0,-1);if(process.platform==='win32')normalized=normalized.toLowerCase();
 assert.equal(endpoint.dataId,require('node:crypto').createHash('sha256').update(normalized,'utf8').digest('hex'));
 assert.ok(!raw.includes(app.token));assert.ok(!raw.includes('fake-endpoint-key'));assert.ok(!raw.includes(dir));
 await app.close();assert.equal(fs.existsSync(file),false);
});

test('shutdown preserves an endpoint belonging to a newer instance even with the same PID',async t=>{
 const {app,dir}=await setup(t);const file=path.join(dir,'instance.json');const newer={...JSON.parse(fs.readFileSync(file,'utf8')),instanceId:'f'.repeat(32)};
 fs.writeFileSync(file,JSON.stringify(newer));await app.close();assert.deepEqual(JSON.parse(fs.readFileSync(file,'utf8')),newer);
});

test('endpoint publication failure closes the new listener and preserves the existing path',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'domtrack-endpoint-failure-'));const file=path.join(dir,'instance.json');fs.mkdirSync(file);
 const app=createApp({dataDir:dir,sourceRegistry:singleRieltorRegistry,keyVault:{load:async()=>null,save:async()=>{},remove:async()=>{}},notifier:()=>{}});
 t.after(async()=>{await app.close();assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir())+path.sep));fs.rmSync(dir,{recursive:true,force:true});});
 await assert.rejects(app.start(0));assert.equal(app.server.listening,false);assert.equal(fs.statSync(file).isDirectory(),true);
 assert.equal(fs.readdirSync(dir).some(name=>name.startsWith('instance.json.tmp-')),false);
});

test('automatic port selection works while a foreign listener keeps port 8789',async t=>{
 const foreign=require('node:http').createServer((req,res)=>res.end('foreign-listener-still-running'));let owned=false;
 try{await new Promise((resolve,reject)=>{foreign.once('error',reject);foreign.listen(8789,'127.0.0.1',resolve);});owned=true;}
 catch(e){if(e.code!=='EADDRINUSE')throw e;t.diagnostic('Port 8789 already belongs to another listener; it is left untouched.');}
 t.after(async()=>{if(owned)await new Promise(resolve=>foreign.close(resolve));});
 const {app,req}=await setup(t);assert.notEqual(app.server.address().port,8789);assert.equal((await req('/api/status')).status,200);
 await app.close();
 if(owned){assert.equal(foreign.listening,true);assert.equal(await fetch('http://127.0.0.1:8789/').then(r=>r.text()),'foreign-listener-still-running');}
});

function scanResult(records=[],extra={}){
 return {records,pagesVisited:4,pagesAttempted:4,urlsDiscovered:0,errors:[],unsupportedPages:[],notices:[],scope:'configured-seeds',...extra};
}
function zapRecord(extra={}){
 return record({source:'web:rieltor.ua',externalId:'zap-flat-1',url:'https://rieltor.ua/fixtures/zap-flat-1',city:'Запоріжжя',region:'Запорізька',title:'Тестова двокімнатна квартира у Запоріжжі',price:45000,...extra});
}

test('online city catalog is available without an API key and does not trigger a scan',async t=>{
 let calls=0;const {req}=await setup(t,{webScanner:{scan:async()=>{calls++;return scanResult();}}});
 const response=await req('/api/catalog');assert.equal(response.status,200);const catalog=await response.json();
 assert.equal(catalog.source.id,'rieltor');assert.ok(catalog.cities.some(city=>city.cityName==='Запоріжжя'&&city.aliases.includes('Запорожье')));
 assert.match(catalog.coverage,/подключённым|Полный интернет не охвачен/i);assert.equal(catalog.sources[0].id,'rieltor');assert.equal(calls,0);
 assert.equal((await req('/api/status').then(r=>r.json())).apiKeyConfigured,false);
});

test('online search resolves a Russian city alias, imports before filtering and preserves suburb identity',async t=>{
 const specs=[];const records=[zapRecord(),zapRecord({externalId:'zap-house-1',url:'https://rieltor.ua/fixtures/zap-house-1',kind:'house',price:120000,rooms:5}),zapRecord({externalId:'suburb-flat-1',url:'https://rieltor.ua/fixtures/suburb-flat-1',city:'Вільнянськ',price:32000})];
 const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return scanResult(records);}}});
 const response=await req('/api/search','POST',{city:'Запорожье',kind:'all'});assert.equal(response.status,200);const result=await response.json();
 assert.deepEqual(specs[0].urls,[
  'https://rieltor.ua/zaporozhje/flats-sale/','https://rieltor.ua/zaporozhje/flats-sale/?page=2',
  'https://rieltor.ua/zaporozhje/houses-sale/','https://rieltor.ua/zaporozhje/houses-sale/?page=2'
 ]);
 assert.equal(specs[0].followLinks,false);assert.equal(specs[0].maxPages,4);assert.equal(specs[0].cityName,'Запоріжжя');assert.equal(specs[0].regionName,'Запорізька');
 assert.equal(result.recordsFound,3);assert.equal(result.recordsImported,3);assert.equal(result.ingested.inserted,3);assert.equal(result.outcome,'loaded');assert.equal(result.success,true);assert.equal(result.cityListings,2);
 assert.equal(result.search.kind,'both');assert.equal(result.search.pagesPerType,2);assert.equal(result.search.source,'Rieltor.ua');assert.match(result.coverage,/часть предложений|полный рынок не охвачен/i);assert.ok(!Object.hasOwn(result,'records'));
 const filtered=await req('/api/listings?city=Запорожье&kind=apartment&currency=USD&priceMax=50000&rooms=2').then(r=>r.json());assert.equal(filtered.total,1);assert.equal(filtered.items[0].externalId,'zap-flat-1');assert.equal(filtered.items[0].previousPrice,null);
 const suburb=await req('/api/listings?city=Вільнянськ').then(r=>r.json());assert.equal(suburb.total,1);assert.equal(suburb.items[0].city,'Вільнянськ');
 const repeated=await req('/api/search','POST',{city:'Запоріжжя',kind:'all'}).then(r=>r.json());assert.equal(repeated.recordsFound,3);assert.equal(repeated.recordsImported,0);assert.equal(repeated.outcome,'loaded');assert.equal(repeated.cityListings,2);
});

test('online search never forwards user-provided URLs or crawling overrides',async t=>{
 let spec;const {req}=await setup(t,{webScanner:{scan:async value=>{spec=value;return scanResult([],{pagesVisited:2,pagesAttempted:2});}}});
 const response=await req('/api/search','POST',{city:'Запорожье',kind:'apartment',urls:['http://127.0.0.1/private','https://untrusted.example/'],followLinks:true,maxPages:20,cityName:'Чуже місто',regionName:'Чужа область'});
 assert.equal(response.status,200);assert.deepEqual(spec.urls,['https://rieltor.ua/zaporozhje/flats-sale/','https://rieltor.ua/zaporozhje/flats-sale/?page=2']);
 assert.equal(spec.followLinks,false);assert.equal(spec.maxPages,2);assert.equal(spec.cityName,'Запоріжжя');assert.equal(spec.regionName,'Запорізька');
 assert.equal((await response.json()).outcome,'no-listings');
});

test('unsupported cities fail before networking and never fall back to another city',async t=>{
 let calls=0;const {req}=await setup(t,{webScanner:{scan:async()=>{calls++;return scanResult([zapRecord()]);}}});
 const before=await req('/api/status').then(r=>r.json());const response=await req('/api/search','POST',{city:'Невідоме тестове місто',kind:'all'});
 assert.equal(response.status,400);assert.match((await response.json()).error,/не поддерживается|не подключён|пока/i);assert.equal(calls,0);
 const after=await req('/api/status').then(r=>r.json());assert.deepEqual(after.onlineSearch,before.onlineSearch);assert.equal(after.listingCount,0);
});

test('source errors are reported as a failed source search rather than an empty market',async t=>{
 const failure={url:'https://rieltor.ua/zaporozhje/flats-sale/',error:'HTTP 403: источник отказал в доступе'};
 const {req}=await setup(t,{webScanner:{scan:async()=>scanResult([],{pagesVisited:0,errors:[failure]})}});
 const response=await req('/api/search','POST',{city:'Запорожье',kind:'all'});assert.equal(response.status,200);const result=await response.json();
 assert.equal(result.outcome,'source-error');assert.equal(result.success,false);assert.equal(result.recordsFound,0);assert.equal(result.recordsImported,0);assert.equal(result.cityListings,0);assert.deepEqual(result.errors,[failure]);
 assert.equal((await req('/api/listings').then(r=>r.json())).total,0);
});

test('unsupported source data preserves its diagnosis and is not presented as no listings',async t=>{
 const unsupported={url:'https://rieltor.ua/zaporozhje/flats-sale/',reason:'Нет подходящей JSON-LD-разметки.'};
 const {req}=await setup(t,{webScanner:{scan:async()=>scanResult([],{unsupportedPages:[unsupported],notices:['JavaScript и CAPTCHA не обходятся.']})}});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'all'}).then(r=>r.json());
 assert.equal(result.outcome,'unsupported');assert.equal(result.success,false);assert.equal(result.recordsFound,0);assert.deepEqual(result.unsupportedPages,[unsupported]);assert.ok(result.notices.includes('JavaScript и CAPTCHA не обходятся.'));assert.equal(result.search.retryable,true);
});

test('partial source results remain usable while errors are retained',async t=>{
 const failure={url:'https://rieltor.ua/zaporozhje/houses-sale/',error:'Источник временно недоступен'};
 const {req}=await setup(t,{webScanner:{scan:async()=>scanResult([zapRecord()],{pagesVisited:1,errors:[failure]})}});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'all'}).then(r=>r.json());
 assert.equal(result.outcome,'loaded');assert.equal(result.success,true);assert.equal(result.cityListings,1);assert.equal(result.recordsImported,1);assert.deepEqual(result.errors,[failure]);
 assert.equal((await req('/api/listings?city=Запорожье').then(r=>r.json())).total,1);
});

test('a successful online load remains distinguishable from zero matches under strict filters',async t=>{
 const incomplete=zapRecord({rooms:undefined,area:undefined,floor:undefined,floorsTotal:undefined,sellerType:'unknown'});
 const {req}=await setup(t,{webScanner:{scan:async()=>scanResult([incomplete])}});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'apartment'}).then(r=>r.json());assert.equal(result.outcome,'loaded');assert.equal(result.success,true);assert.equal(result.cityListings,1);
 const filtered=await req('/api/listings?city=Запорожье&sellerType=owner&rooms=2&floorMin=2').then(r=>r.json());assert.equal(filtered.total,0);
 const city=await req('/api/listings?city=Запорожье').then(r=>r.json());assert.equal(city.total,1);assert.equal(city.items[0].sellerType,'unknown');assert.equal(city.items[0].rooms,null);assert.equal(city.items[0].floor,null);
});

test('an unexpected scanner failure stays an explicit error and does not manufacture data',async t=>{
 const {req}=await setup(t,{webScanner:{scan:async()=>{throw Error('Тестовая ошибка подключения к источнику');}}});
 const response=await req('/api/search','POST',{city:'Запорожье',kind:'all'});assert.equal(response.status,200);const failure=await response.json();assert.equal(failure.outcome,'source-error');assert.match(failure.errors[0].message,/ошибка подключения/);
 const status=await req('/api/status').then(r=>r.json());assert.equal(status.listingCount,0);assert.equal(status.web.running,false);assert.match(status.web.lastError,/ошибка подключения/);
});

function pagedSource(spec,outcome=()=>({status:'ok',recordsFound:1})){
 const pageResults=[],records=[],errors=[],unsupportedPages=[];
 for(const href of spec.urls){
  const url=new URL(href),kind=url.pathname.includes('houses-sale')?'house':'apartment',page=Number(url.searchParams.get('page')||1);const value=outcome({url:href,kind,page,city:spec.cityName})||{status:'ok',recordsFound:1};
  const status=value.status||'ok',recordsFound=value.recordsFound??(status==='ok'?1:0);
  pageResults.push({url:href,status,recordsFound});
  if(status==='error')errors.push({url:href,message:'Тестовая недоступность источника'});
  if(status==='unsupported')unsupportedPages.push({url:href,reason:'Тестовая неподдерживаемая разметка'});
  if(recordsFound>0)records.push(zapRecord({city:spec.cityName,region:spec.regionName,kind,externalId:`${spec.cityName}:${kind}:${page}`,url:`https://rieltor.ua/fixtures/${encodeURIComponent(spec.cityName)}/${kind}/${page}`,title:`Тестове житло ${spec.cityName} ${kind} сторінка ${page}`}));
 }
 return scanResult(records,{pagesVisited:spec.urls.length,pagesAttempted:spec.urls.length,pageResults,errors,unsupportedPages});
}

test('load-more advances to later pages and refreshing 1 and 2 preserves deeper progress',async t=>{
 const specs=[];const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return pagedSource(spec);}}});
 let result=await req('/api/search','POST',{city:'Запорожье',kind:'all'}).then(r=>r.json());
 assert.deepEqual(result.search.nextPages,{apartment:3,house:3});assert.equal(result.search.hasMore,true);assert.equal(result.search.mode,'refresh');assert.equal(result.search.retryable,false);assert.equal(result.cityListings,4);
 result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more',cursor:9999,page:9999}).then(r=>r.json());
 assert.deepEqual(specs[1].urls,['https://rieltor.ua/zaporozhje/flats-sale/?page=3','https://rieltor.ua/zaporozhje/flats-sale/?page=4','https://rieltor.ua/zaporozhje/houses-sale/?page=3','https://rieltor.ua/zaporozhje/houses-sale/?page=4']);
 assert.deepEqual(result.search.pages.map(page=>page.page),[3,4,3,4]);assert.deepEqual(result.search.nextPages,{apartment:5,house:5});assert.equal(result.search.firstPage,3);assert.equal(result.cityListings,8);
 result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'refresh'}).then(r=>r.json());
 assert.deepEqual(result.search.nextPages,{apartment:5,house:5});assert.equal(result.cityListings,8);assert.equal(result.recordsImported,0);
 assert.ok(!specs[2].urls.some(url=>url.includes('page=3')));
 result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'}).then(r=>r.json());assert.equal(result.search.firstPage,5);assert.deepEqual(result.search.nextPages,{apartment:7,house:7});
});

test('apartment, house and city cursors do not overwrite one another',async t=>{
 const specs=[];const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return pagedSource(spec);}}});
 await req('/api/search','POST',{city:'Запорожье',kind:'all'});
 await req('/api/search','POST',{city:'Запорожье',kind:'house',mode:'more'});
 assert.deepEqual(specs[1].urls,['https://rieltor.ua/zaporozhje/houses-sale/?page=3','https://rieltor.ua/zaporozhje/houses-sale/?page=4']);
 await req('/api/search','POST',{city:'Одесса',kind:'all',mode:'more'});
 assert.equal(specs[2].urls[0],'https://rieltor.ua/odessa/flats-sale/');
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'}).then(r=>r.json());
 assert.deepEqual(specs[3].urls,['https://rieltor.ua/zaporozhje/flats-sale/?page=3','https://rieltor.ua/zaporozhje/flats-sale/?page=4','https://rieltor.ua/zaporozhje/houses-sale/?page=5','https://rieltor.ua/zaporozhje/houses-sale/?page=6']);
 assert.deepEqual(result.search.nextPages,{apartment:5,house:7});
});

test('load-more cursor survives a normal server restart in the same data directory',async t=>{
 const scanner={scan:async spec=>pagedSource(spec)};const {req,app,dir}=await setup(t,{webScanner:scanner});
 await req('/api/search','POST',{city:'Запорожье',kind:'all'});await app.close();
 const second=createApp({dataDir:dir,webScanner:scanner,sourceRegistry:singleRieltorRegistry,keyVault:{load:async()=>null,save:async()=>{},remove:async()=>{}},notifier:()=>{}});
 try{
  const address=await second.start(0),base=`http://127.0.0.1:${address.port}`,headers={'X-App-Token':second.token,'Content-Type':'application/json'};
  const status=await fetch(base+'/api/status',{headers}).then(r=>r.json());assert.deepEqual(status.onlineSearch.nextPages,{apartment:3,house:3});
  const response=await fetch(base+'/api/search',{method:'POST',headers,body:JSON.stringify({city:'Запорожье',kind:'all',mode:'more'})});assert.equal(response.status,200);const result=await response.json();
  assert.equal(result.search.firstPage,3);assert.deepEqual(result.search.nextPages,{apartment:5,house:5});assert.equal(result.cityListings,8);
 }finally{await second.close();}
});

test('partial source failure retries the first unconfirmed page while other kind progresses',async t=>{
 const specs=[];let fail=true;const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return pagedSource(spec,({kind,page})=>fail&&kind==='apartment'&&page===3?{status:'error',recordsFound:0}:{status:'ok',recordsFound:1});}}});
 await req('/api/search','POST',{city:'Запорожье',kind:'all'});
 let result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'}).then(r=>r.json());
 assert.equal(result.outcome,'loaded');assert.equal(result.search.retryable,true);assert.deepEqual(result.search.nextPages,{apartment:3,house:5});assert.equal(result.search.hasMore,true);
 fail=false;result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'}).then(r=>r.json());
 assert.deepEqual(specs[2].urls,['https://rieltor.ua/zaporozhje/flats-sale/?page=3','https://rieltor.ua/zaporozhje/flats-sale/?page=4','https://rieltor.ua/zaporozhje/houses-sale/?page=5','https://rieltor.ua/zaporozhje/houses-sale/?page=6']);
 assert.equal(result.search.retryable,false);assert.deepEqual(result.search.nextPages,{apartment:5,house:7});
});

test('unsupported or blocked pages do not advance cursors or falsely exhaust the source',async t=>{
 let blocked=false;const specs=[];const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return pagedSource(spec,()=>blocked?{status:'unsupported',recordsFound:0}:{status:'ok',recordsFound:1});}}});
 await req('/api/search','POST',{city:'Запорожье',kind:'apartment'});
 blocked=true;let result=await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'more'}).then(r=>r.json());
 assert.equal(result.outcome,'unsupported');assert.equal(result.success,false);assert.deepEqual(result.search.nextPages,{apartment:3});assert.equal(result.search.canLoadMore,true);assert.equal(result.search.retryable,true);
 result=await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'refresh'}).then(r=>r.json());
 assert.deepEqual(result.search.nextPages,{apartment:3});assert.equal(result.search.hasMore,true);
 blocked=false;await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'more'});
 assert.deepEqual(specs[3].urls,['https://rieltor.ua/zaporozhje/flats-sale/?page=3','https://rieltor.ua/zaporozhje/flats-sale/?page=4']);
});

test('a confirmed empty tail completes only that kind and does not suppress other pages',async t=>{
 const specs=[];const {req}=await setup(t,{webScanner:{scan:async spec=>{specs.push(spec);return pagedSource(spec,({kind,page})=>kind==='apartment'&&page>=3?{status:'empty',recordsFound:0}:{status:'ok',recordsFound:1});}}});
 await req('/api/search','POST',{city:'Запорожье',kind:'all'});
 let result=await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'}).then(r=>r.json());
 assert.equal(result.search.sections.find(section=>section.kind==='apartment').hasMore,false);assert.equal(result.search.hasMore,true);assert.equal(result.search.nextPage,5);
 await req('/api/search','POST',{city:'Запорожье',kind:'all',mode:'more'});
 assert.deepEqual(specs[2].urls,['https://rieltor.ua/zaporozhje/houses-sale/?page=5','https://rieltor.ua/zaporozhje/houses-sale/?page=6']);
 const before=specs.length;result=await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'more'}).then(r=>r.json());
 assert.equal(specs.length,before);assert.equal(result.outcome,'complete');assert.equal(result.search.hasMore,false);assert.equal(result.search.canLoadMore,false);
});

test('contradictory empty and populated pages are retried instead of silently skipping data',async t=>{
 const {req}=await setup(t,{webScanner:{scan:async spec=>pagedSource(spec,({page})=>page===3?{status:'empty',recordsFound:0}:{status:'ok',recordsFound:1})}});
 await req('/api/search','POST',{city:'Запорожье',kind:'apartment'});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'more'}).then(r=>r.json());
 assert.deepEqual(result.search.nextPages,{apartment:3});assert.equal(result.search.retryable,true);assert.equal(result.search.hasMore,true);assert.equal(result.cityListings,3);
});

test('invalid pagination mode is rejected before scanner execution and status marks desktop mode',async t=>{
 let calls=0;const {req}=await setup(t,{desktop:true,webScanner:{scan:async spec=>{calls++;return pagedSource(spec);}}});
 assert.equal((await req('/api/search','POST',{city:'Запорожье',kind:'apartment',mode:'arbitrary'})).status,400);assert.equal(calls,0);
 const status=await req('/api/status').then(r=>r.json());assert.equal(status.desktop,true);assert.equal(status.apiVersion,4);assert.equal(status.appVersion,'1.3.0');
});

test('per-page seed indices preserve pagination evidence when diagnostic URLs hide query strings',async t=>{
 const {req}=await setup(t,{webScanner:{scan:async spec=>{
  const result=pagedSource(spec,({kind,page})=>kind==='apartment'&&page===2?{status:'error',recordsFound:0}:{status:'ok',recordsFound:1});
  result.pageResults=result.pageResults.map((row,seedIndex)=>{const url=new URL(row.url);return {...row,url:url.origin+url.pathname,seedIndex};});return result;
 }}});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'all'}).then(r=>r.json());
 assert.deepEqual(result.search.nextPages,{apartment:2,house:3});assert.equal(result.search.pages[1].status,'error');assert.equal(result.search.pages[1].page,2);assert.equal(result.search.retryable,true);
});

test('aggregate results without per-page evidence never skip unseen pages',async t=>{
 const {req}=await setup(t,{webScanner:{scan:async()=>scanResult([zapRecord()])}});
 const result=await req('/api/search','POST',{city:'Запорожье',kind:'all'}).then(r=>r.json());
 assert.equal(result.success,true);assert.deepEqual(result.search.nextPages,{apartment:1,house:1});assert.equal(result.search.retryable,true);assert.equal(result.search.hasMore,true);
});

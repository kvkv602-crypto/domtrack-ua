'use strict';

const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {createStore}=require('./lib/store.cjs');
const {readImport}=require('./lib/import.cjs');
const {demoRecords}=require('./lib/demo.cjs');
const duplicateTools=require('./lib/duplicates.cjs');
const {analyzeDuplicates}=duplicateTools;
const {analyzeListing,buildDailySummary}=require('./lib/insights.cjs');
const {createFxStore,kyivDate,validateDate}=require('./lib/fx.cjs');
const {planCurrency,currencyRows}=require('./lib/currency.cjs');
const {createPreferences}=require('./lib/preferences.cjs');
const {showWindowsNotifications}=require('./lib/notify.cjs');

const PUBLIC=path.join(__dirname,'public');
const APP_ID='domtrack-ua';
const APP_VERSION='1.2.0';
const API_VERSION=3;
const MAX_BODY=16*1024*1024;
const DEFAULTS={stateId:10,cityId:10,cityName:'Київ',regionName:'Київська',kind:'both',enabled:false,intervalMinutes:360,maxListings:15,monthlyBudget:100};
function error(message,statusCode=400){const e=new Error(message);e.statusCode=statusCode;return e;}
function jsonRead(file,fallback){try{return JSON.parse(fs.readFileSync(file,'utf8'));}catch{return fallback;}}
function atomicWrite(file,value){const tmp=file+'.tmp';fs.writeFileSync(tmp,JSON.stringify(value,null,2),{mode:0o600});fs.renameSync(tmp,file);}
function positive(v,name,min,max){if(typeof v==='boolean'||v==null||String(v).trim()===''||!Number.isInteger(Number(v))||Number(v)<min||Number(v)>max)throw error(`${name}: требуется целое число от ${min} до ${max}.`);return Number(v);}
function validateSettings(value){
  if(!value||typeof value!=='object'||Array.isArray(value))throw error('Некорректные настройки сбора.');
  const s={...value};
  const out={stateId:positive(s.stateId,'Область ID',1,10000000),cityId:positive(s.cityId,'Город ID',1,100000000),
    cityName:String(s.cityName||'').trim(),regionName:String(s.regionName||'').trim(),kind:s.kind,
    enabled:s.enabled===true,intervalMinutes:positive(s.intervalMinutes,'Интервал, минут',120,10080),maxListings:positive(s.maxListings,'Объектов за проход',1,20),monthlyBudget:s.monthlyBudget??100};
  if(![100,1000].includes(out.monthlyBudget))throw error('Бесплатный лимит API: 100 или 1000 запросов за 30 дней.');
  if(!['apartment','house','both'].includes(out.kind))throw error('Тип сбора: apartment, house или both.');
  if(!out.cityName||out.cityName.length>150||!out.regionName||out.regionName.length>150)throw error('Укажите город и область длиной до 150 символов.');
  return out;
}
async function body(req){
  if(!String(req.headers['content-type']||'').toLowerCase().startsWith('application/json'))throw error('Требуется Content-Type: application/json.',415);
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>MAX_BODY)throw error('Размер запроса превышает 16 МБ.',413);chunks.push(chunk);}
  try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw error('Некорректный JSON.');}
}
function send(res,status,value){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(value));}

function createApp(options={}){
  const dataDir=path.resolve(options.dataDir||process.env.DOMTRACK_DATA_DIR||path.join(__dirname,'data'));
  let identityPath=dataDir;const identityRoot=path.parse(identityPath).root;
  while(identityPath.length>identityRoot.length&&/[\\/]$/.test(identityPath))identityPath=identityPath.slice(0,-1);
  if(process.platform==='win32')identityPath=identityPath.toLowerCase();
  const dataId=crypto.createHash('sha256').update(identityPath,'utf8').digest('hex');
  const instanceId=crypto.randomBytes(16).toString('hex');
  const instancePath=path.join(dataDir,'instance.json');let publishedPort=null;
  fs.mkdirSync(dataDir,{recursive:true,mode:0o700});
  const store=options.store||createStore(path.join(dataDir,'domtrack.sqlite'));
  const configPath=path.join(dataDir,'settings.json'),ratePath=path.join(dataDir,'ria-rate.json');
  const loaded=jsonRead(configPath,{});let settings;
  let onlineSearch=loaded.onlineSearch&&typeof loaded.onlineSearch.cityName==='string'&&loaded.onlineSearch.cityName.length<151?loaded.onlineSearch:null;
  try{settings=validateSettings({...DEFAULTS,...loaded.collector});}catch{settings={...DEFAULTS};}
  let apiKey=options.apiKey||process.env.RIA_API_KEY||'';
  const keyVault=options.keyVault||require('./lib/key-vault.cjs').createKeyVault({file:path.join(dataDir,'ria-key.enc.json')});
  let keyStored=false,keyStorageError=null,keyInitialization=null;
  function initializeKey(){
    if(!keyInitialization)keyInitialization=(async()=>{try{const saved=await keyVault.load();keyStored=!!saved;if(saved&&!options.apiKey)apiKey=saved;}catch{keyStorageError='Не удалось открыть защищённое хранилище ключа. Вставьте ключ повторно в разделе «Источники».';}})();
    return keyInitialization;
  }
  const token=crypto.randomBytes(32).toString('hex');
  let ria=null, timer=null,closed=false,activeCollect=null,activeScan=null,activeSearch=null,closing=null;
  const fx=createFxStore({file:path.join(dataDir,'fx.json'),fetchImpl:options.fxFetch});
  const preferences=createPreferences(path.join(dataDir,'preferences.json'));
  const groupCache=new Map();
  const webScanner=options.webScanner||require('./lib/web.cjs').createWebScanner();
  const catalog=options.catalog||require('./lib/catalog.cjs');
  const sourceRegistry=options.sourceRegistry||require('./lib/source-registry.cjs');
  function connectedSources(city){try{return sourceRegistry.getSources(city);}catch{return [];}}
  const searchProgress={running:false,currentSource:null,completedSources:0,sourceCount:0};
  const onlineCursors=new Map();
  const cursorKey=(city,kind,sourceId='rieltor')=>JSON.stringify([city,kind,sourceId]);
  if(Array.isArray(loaded.onlineCursors))for(const saved of loaded.onlineCursors.slice(0,2000)){
    try{
      if(!saved||!['apartment','house'].includes(saved.kind)||!Number.isSafeInteger(saved.nextPage)||saved.nextPage<1||saved.nextPage>10001)continue;
      const place=catalog.resolveSearch(saved.cityName,saved.kind);
      const sourceId=typeof saved.sourceId==='string'?saved.sourceId:'rieltor';
      if(!sourceRegistry.getSources(place.cityName).some(source=>source.id===sourceId))continue;
      onlineCursors.set(cursorKey(place.cityName,saved.kind,sourceId),{cityName:place.cityName,kind:saved.kind,sourceId,nextPage:saved.nextPage,hasMore:saved.nextPage<=10000&&saved.hasMore!==false,retryable:saved.retryable===true,limitReached:saved.nextPage>10000||saved.limitReached===true});
    }catch{}
  }
  const webState={running:false,lastRun:null,lastError:null,lastResult:null};
  const collector={running:false,lastRun:null,lastError:null,lastResult:null,nextRun:null,settings,configured:!!apiKey};
  let watch=Object.create(null);
  if(loaded.watch&&typeof loaded.watch==='object')for(const [key,value] of Object.entries(loaded.watch)){
    if(/^\d+:\d+:(both|apartment|house)$/.test(key)&&value&&Array.isArray(value.ids))watch[key]={ids:value.ids.filter(x=>typeof x==='string'&&/^\d+$/.test(x)).slice(-10000),cursor:Number.isSafeInteger(value.cursor)&&value.cursor>=0?value.cursor:0};
  }
  const cache={regions:null,cities:new Map()};
  function saveConfig(){atomicWrite(configPath,{collector:settings,watch,onlineSearch,onlineCursors:[...onlineCursors.values()]});}
  function getRia(){
    if(!apiKey)throw error('Для загрузки с DIM.RIA укажите свой API-ключ в разделе «Источники».',409);
    if(!ria){
      const factory=options.riaFactory||require('./lib/ria.cjs').createRiaClient;
      const saved=jsonRead(ratePath,[]);
      ria=factory({apiKey,monthlyBudget:settings.monthlyBudget,initialRequestTimestamps:Array.isArray(saved)?saved:[],onRequest:timestamps=>atomicWrite(ratePath,timestamps)});
    }
    return ria;
  }
  function schedule(){
    clearTimeout(timer);collector.nextRun=null;collector.configured=!!apiKey;
    if(closed||!settings.enabled||!apiKey)return;
    const delay=settings.intervalMinutes*60000;collector.nextRun=new Date(Date.now()+delay).toISOString();
    timer=setTimeout(()=>{collect().catch(()=>{});},delay);timer.unref?.();
  }
  function quota(){try{return ria?.rateLimiter?.snapshot()||null;}catch{return null;}}
  async function runCollect(){
    if(collector.running)throw error('Сбор уже выполняется. Дождитесь его завершения.',409);
    const client=getRia();collector.running=true;collector.lastError=null;collector.nextRun=null;clearTimeout(timer);
    const runSettings={...settings};const watchKey=`${settings.stateId}:${settings.cityId}:${settings.kind}`;
    const existing=watch[watchKey]||{ids:[],cursor:0};
    const maxRefresh=Math.min(Math.max(1,Math.floor(settings.maxListings/2)),existing.ids.length);
    runSettings.refreshIds=Array.from({length:maxRefresh},(_,i)=>existing.ids[(existing.cursor+i)%existing.ids.length]);
    try{
      const result=await client.collect(runSettings);
      const ingested=result.records?.length?ingestRecords(result.records):{received:0,inserted:0,duplicates:0,updatedListings:0};
      const ids=new Set(existing.ids);for(const r of result.records||[])if(r.source==='domria'&&/^\d+$/.test(r.externalId))ids.add(r.externalId);
      watch[watchKey]={ids:[...ids].slice(-10000),cursor:existing.ids.length?(existing.cursor+maxRefresh)%existing.ids.length:0};
      saveConfig();
      collector.lastResult={...result,records:undefined,ingested,observedCount:result.records?.length||0,coverage:'Ограниченная выборка DIM.RIA; весь рынок не охвачен.'};
      collector.lastRun=new Date().toISOString();
      if(result.errors?.length)collector.lastError=`Часть объявлений не загружена (${result.errors.length}). См. результат сбора.`;
      return {message:'Проход завершён. Наблюдения сохранены.',...collector.lastResult};
    }catch(e){collector.lastRun=new Date().toISOString();collector.lastError=safeError(e);throw e;}
    finally{
      collector.running=false;
      const snapshot=quota();if(snapshot?.timestamps)atomicWrite(ratePath,snapshot.timestamps);
      schedule();
    }
  }
  async function collect(){
    if(collector.running)throw error('Сбор уже выполняется.',409);
    const task=runCollect();activeCollect=task;try{return await task;}finally{if(activeCollect===task)activeCollect=null;}
  }
  function storeRows(filters={}){
    const all=[];let page=1,total=Infinity;
    const clean={...filters,deal:'sale'};for(const key of ['page','pageSize','dedupe','favorites','currency','priceMin','priceMax','fxDate'])delete clean[key];
    if(clean.sort==='price-asc'||clean.sort==='price-desc')clean.sort='updated';
    while(all.length<total){const result=store.list({...clean,page,pageSize:200});total=result.total;all.push(...result.items);if(!result.items.length)break;page++;}
    return all;
  }
  function allFiltered(filters={}){
    const plan=planCurrency(filters,fx.status().requestedDate);return currencyRows(storeRows(filters),plan,fx.status(plan.date),{includeUnverified:false}).items;
  }
  function analyze(items,quote=fx.status()){
    const rules=preferences.get().duplicateRules||{};const result=analyzeDuplicates(items,{...rules,...(quote.usable?{rates:quote.rates,asOf:quote.asOf}:{})});
    for(const g of result.groups)g.confirmed=duplicateTools.isConfirmedGroup(g);
    if(groupCache.size>5000)groupCache.clear();for(const group of result.groups)groupCache.set(group.id,group);return result;
  }
  async function listResponse(filters){
    const page=positive(filters.page||1,'Страница',1,1000000),pageSize=positive(filters.pageSize||50,'Размер страницы',1,200);
    const plan=planCurrency(filters,fx.status().requestedDate),stored=storeRows(filters);
    let quote=fx.status(plan.date);
    if(plan.currency&&stored.some(row=>row.currency!==plan.currency)&&!quote.usable){try{quote=await fx.getForDate(plan.date);}catch{quote=fx.status(plan.date);}}
    const initial=currencyRows(stored,plan,quote);let rows=initial.items;const rawTotal=rows.length;const result=analyze(rows,quote);
    const prefs=preferences.get(),fav=new Set(prefs.favorites);
    const suppressed=new Set(),groupsById=new Map();
    for(const g of result.groups){const confirmed=duplicateTools.isConfirmedGroup(g);for(const item of g.items){groupsById.set(item.id,g);if(confirmed&&item.id!==g.representativeId)suppressed.add(item.id);}}
    rows=rows.map(r=>{const g=groupsById.get(r.id);return g?{...r,duplicateCount:g.items.length,duplicateGroupId:g.id,duplicateAlternatives:g.items.filter(a=>a.id!==r.id),duplicateConfidence:g.confidence,duplicateReasons:g.reasons,duplicateConfirmed:duplicateTools.isConfirmedGroup(g)}:r;});
    if(filters.dedupe!=='false')rows=rows.filter(r=>!suppressed.has(r.id));
    if(filters.favorites==='true')rows=rows.filter(r=>fav.has(r.id)||(r.duplicateConfirmed&&(r.duplicateAlternatives||[]).some(a=>fav.has(a.id))));
    const compared=currencyRows(rows,plan,quote);rows=compared.items;
    rows=rows.map(r=>{const watched=prefs.watches.find(w=>w.id===r.id||(r.duplicateConfirmed&&(r.duplicateAlternatives||[]).some(a=>a.id===w.id)));const age=Math.floor((Date.now()-Date.parse(r.lastSeen))/86400000);return {...r,isFavorite:fav.has(r.id)||(r.duplicateConfirmed&&(r.duplicateAlternatives||[]).some(a=>fav.has(a.id))),isWatched:!!watched,watchId:watched?.id,freshness:{stale:age>7,ageDays:age>=0?age:null,lastChecked:r.lastSeen}};});
    return {items:rows.slice((page-1)*pageSize,page*pageSize),total:rows.length,page,pageSize,rawTotal,collapsedCount:filters.dedupe==='false'?0:suppressed.size,analysisTruncated:!!result.analysisTruncated,comparisonDate:plan.date,currencyComparison:compared.currencyComparison};
  }
  function detailWithFx(id){const detail=store.detail(id);if(!detail)return null;const history=fx.convertHistory(detail.history);return {...detail,history,historicalFxPending:history.filter(h=>h.priceUAH===null).length};}
  function notifyChanges(before,after,priorMatches=new Map()){
    const old=new Map(before.map(r=>[r.id,r]));const events=[];
    for(const row of after){const prior=old.get(row.id);if(prior&&prior.currency===row.currency&&prior.price>row.price)events.push({type:'pricedrop',message:`Цена снизилась: ${row.title} · ${prior.price} → ${row.price} ${row.currency}`,listingId:row.id,eventKey:`drop:${row.id}:${row.lastSeen}:${row.price}:${row.currency}`});}
    for(const search of preferences.get().savedSearches){
      const previous=priorMatches.get(search.id)||new Set(old.keys());
      const matches=allFiltered(search.filters).filter(r=>!previous.has(r.id));
      if(matches.length)events.push({type:'newmatch',message:`«${search.name}»: новых подходящих объявлений — ${matches.length}.`,listingId:matches[0].id,eventKey:`search:${search.id}:`+crypto.createHash('sha256').update(matches.map(r=>r.id).sort().join(',')).digest('hex')});
    }
    for(const group of analyze(after).groups){
      if(!duplicateTools.isConfirmedGroup(group))continue;
      const chosen=group.items.find(r=>r.id===group.representativeId);const previous=group.items.map(r=>old.get(r.id)).filter(r=>r&&r.currency===chosen.currency);
      if(previous.length&&chosen.price<Math.min(...previous.map(r=>r.price)))events.push({type:'cheaperduplicate',message:`Найден более дешёвый вариант: ${chosen.title} · ${chosen.price} ${chosen.currency}.`,listingId:chosen.id,eventKey:`duplicate:${group.id}:${chosen.price}:${chosen.lastSeen}`});
    }
    return preferences.notify(events.slice(-200).map(e=>({...e,message:e.message.slice(0,900)})));
  }
  function ingestRecords(records){
    const before=allFiltered();const priorMatches=new Map(preferences.get().savedSearches.map(s=>[s.id,new Set(allFiltered(s.filters).map(r=>r.id))]));const result=store.ingest(records);groupCache.clear();
    if(result.inserted){try{result.notifications=notifyChanges(before,allFiltered(),priorMatches).added;}catch{result.warning='Данные сохранены, но уведомления не удалось обновить.';}}
    monitor?.evaluate().catch(()=>{});
    return result;
  }
  async function scanWeb(spec){
    if(webState.running)throw error('Сканирование сайтов уже выполняется.',409);
    webState.running=true;webState.lastError=null;
    const task=(async()=>{
      try{const result=await webScanner.scan(spec);const imported=result.records.length?ingestRecords(result.records):{inserted:0,updatedListings:0};
        const clean={...result,records:undefined,recordsFound:result.records.length,recordsImported:imported.inserted,ingested:imported};webState.lastResult=clean;webState.lastRun=new Date().toISOString();return clean;
      }catch(e){webState.lastError=safeError(e);throw e;}finally{webState.running=false;}
    })();activeScan=task;try{return await task;}finally{if(activeScan===task)activeScan=null;}
  }
  async function searchSource(data,source){
    if(!data||typeof data!=='object'||Array.isArray(data))throw error('Выберите город для поиска в интернете.');
    const kind=data.kind==='all'||data.kind===undefined?'both':data.kind;
    if(!['both','apartment','house'].includes(kind))throw error('Выберите квартиры, дома или оба типа.');
    const mode=data.mode===undefined?'refresh':data.mode;
    if(!['refresh','more'].includes(mode))throw error('Режим поиска: refresh или more.');
    if(typeof data.city!=='string'||!data.city.trim()||data.city.length>150)throw error('Выберите город для поиска в интернете.');
    let resolved;try{resolved=catalog.resolveSearch(data.city,kind);}catch(e){throw error(e.message||'Этот город пока не подключён к онлайн-поиску.');}
    const kinds=(kind==='both'?['apartment','house']:[kind]).filter(type=>!source.kinds||source.kinds.includes(type));const plan=[];const previous=new Map();
    for(const type of kinds){
      const saved=onlineCursors.get(cursorKey(resolved.cityName,type,source.id))||{cityName:resolved.cityName,kind:type,sourceId:source.id,nextPage:1,hasMore:true,retryable:false,limitReached:false};previous.set(type,{...saved});
      if(mode==='more'&&!saved.hasMore)continue;
      const first=mode==='refresh'?1:saved.nextPage;
      for(let page=first;page<first+2&&page<=10000;page++){
        const single=sourceRegistry.resolvePage(source.id,resolved.cityName,type,page);
        if(plan.some(item=>item.url===single.url))break;
        plan.push({kind:type,page,url:single.url,sourceId:source.id,sourceName:source.name});
      }
    }
    const urls=plan.map(page=>page.url);
    if(urls.length>5)throw error('Слишком много источников для одного поиска.');
    const result=urls.length?await scanWeb({...resolved,urls,maxPages:urls.length,followLinks:false}):{recordsFound:0,recordsImported:0,ingested:{received:0,inserted:0,updatedListings:0},pagesVisited:0,pagesAttempted:0,errors:[],unsupportedPages:[],pageResults:[],notices:[]};
    const reported=Array.isArray(result.pageResults)?result.pageResults:[];
    const metadata=new Map(reported.filter(row=>row&&typeof row.url==='string').map(row=>[row.url,row]));
    const bySeed=new Map(reported.filter(row=>row&&Number.isSafeInteger(row.seedIndex)&&row.seedIndex>=0&&row.seedIndex<urls.length).map(row=>[row.seedIndex,row]));
    const cleanEmpty=result.recordsFound===0&&!result.errors?.length&&!result.unsupportedPages?.length&&result.pagesVisited===urls.length;
    const pages=plan.map((page,index)=>{
      const row=bySeed.get(index)||metadata.get(page.url);const failed=(result.errors||[]).find(item=>item&&typeof item==='object'&&item.url===page.url);const unsupported=(result.unsupportedPages||[]).find(item=>item&&typeof item==='object'&&item.url===page.url);
      let status=row&&['ok','empty','unsupported','error'].includes(row.status)?row.status:failed?'error':unsupported?'unsupported':cleanEmpty?'empty':'unknown';
      const recordsFound=Number.isSafeInteger(row?.recordsFound)&&row.recordsFound>=0?row.recordsFound:status==='empty'?0:null;
      if(status==='ok'&&recordsFound===0)status='empty';
      return {...page,status,recordsFound,...(row?.httpStatus?{httpStatus:row.httpStatus}:{}),...(row?.message||failed?.message||failed?.error||unsupported?.reason?{message:row?.message||failed?.message||failed?.error||unsupported?.reason}:{})};
    });
    const changes=new Map();
    for(const type of kinds){
      const typePages=pages.filter(page=>page.kind===type);const saved=previous.get(type);
      if(!typePages.length){changes.set(type,saved);continue;}
      let nextPage=typePages[0].page,hasMore=true,retryable=false,advanced=false;
      for(const page of typePages){
        if(page.status==='ok'&&page.recordsFound>0){nextPage=page.page+1;advanced=true;continue;}
        if(page.status==='empty'&&!typePages.some(later=>later.page>page.page&&later.status==='ok'&&later.recordsFound>0)){nextPage=page.page;hasMore=false;break;}
        retryable=true;break;
      }
      if(mode==='refresh'&&saved.nextPage>nextPage){
        nextPage=saved.nextPage;
        // Refresh updates the first pages; it never discards deeper pagination progress.
        if(advanced)hasMore=true;else if(!retryable)hasMore=saved.hasMore;
      }
      const limitReached=nextPage>10000;if(limitReached)hasMore=false;
      changes.set(type,{cityName:resolved.cityName,kind:type,sourceId:source.id,sourceName:source.name,nextPage,hasMore,retryable,limitReached});
    }
    const nextPages=Object.fromEntries([...changes].map(([type,cursor])=>[type,cursor.nextPage]));
    const hasMore=[...changes.values()].some(cursor=>cursor.hasMore),retryable=[...changes.values()].some(cursor=>cursor.retryable);
    const pending=[...changes.values()].filter(cursor=>cursor.hasMore).map(cursor=>cursor.nextPage);
    const search={cityName:resolved.cityName,regionName:resolved.regionName,kind,source:source.name,sourceId:source.id,mode,pagesPerType:2,pages,firstPage:pages.length?Math.min(...pages.map(page=>page.page)):null,nextPages,nextPage:pending.length?Math.min(...pending):null,hasMore,canLoadMore:hasMore,retryable,limitReached:[...changes.values()].some(cursor=>cursor.limitReached),sections:[...changes.values()].map(cursor=>({...cursor}))};
    const oldSearch=onlineSearch;for(const [type,cursor]of changes)onlineCursors.set(cursorKey(resolved.cityName,type,source.id),cursor);
    onlineSearch={...search,pages:undefined};
    try{saveConfig();}catch(e){onlineSearch=oldSearch;for(const [type,cursor]of previous)onlineCursors.set(cursorKey(resolved.cityName,type,source.id),cursor);throw e;}
    result.search=search;
    result.coverage=`Проверяется очередная порция страниц ${source.name}. Это часть предложений источника, полный рынок не охвачен.`;
    result.outcome=!urls.length?'complete':result.recordsFound>0?'loaded':result.errors?.length?'source-error':result.unsupportedPages?.length?'unsupported':'no-listings';
    result.success=result.outcome==='loaded'||result.outcome==='complete';
    if(retryable)result.notices=[...(result.notices||[]),'Часть страниц не подтвердила доступные данные. Их номер сохранён для повторной проверки; отсутствие разметки или ошибка не считаются концом объявлений.'];
    result.cityListings=store.list({city:resolved.cityName,region:resolved.regionName,...(kind!=='both'?{kind}:{}),page:1,pageSize:1}).total;
    return result;
  }
  async function searchOnline(data){
    if(searchProgress.running)throw error('Поиск по площадкам уже выполняется. Дождитесь его завершения.',409);
    if(!data||typeof data!=='object'||Array.isArray(data))throw error('Выберите город для поиска.');
    const kind=data.kind==='all'||data.kind===undefined?'both':data.kind,mode=data.mode??'refresh';
    if(!['both','apartment','house'].includes(kind))throw error('Выберите квартиры, дома или оба типа.');
    if(!['refresh','more'].includes(mode))throw error('Режим поиска: refresh или more.');
    let place;try{place=catalog.resolveSearch(data.city,kind);}catch(e){throw error(e.message||'Город пока не подключён.');}
    const allSources=sourceRegistry.getSources(place.cityName);
    if(data.sources!==undefined&&(!Array.isArray(data.sources)||!data.sources.length||data.sources.length>10||data.sources.some(id=>typeof id!=='string'||!allSources.some(source=>source.id===id))))throw error('Выберите подключённые площадки из каталога.');
    const sources=allSources.filter(source=>(data.sources===undefined||data.sources.includes(source.id))&&source.available!==false&&(!source.kinds||kind==='both'||source.kinds.includes(kind)));
    if(!sources.length)throw error('Для выбранного города и типа жилья нет подключённых площадок.');
    searchProgress.running=true;searchProgress.completedSources=0;searchProgress.sourceCount=sources.length;
    const operation=(async()=>{
      const combined=await require('./lib/multi-source.cjs').runSources({sources,progress:searchProgress,isClosed:()=>closed,safeError,scan:source=>searchSource({...data,city:place.cityName,kind,mode},source)});
      combined.search={...combined.search,cityName:place.cityName,regionName:place.regionName,kind,mode};
      combined.cityListings=store.list({city:place.cityName,region:place.regionName,...(kind!=='both'?{kind}:{}),page:1,pageSize:1}).total;
      onlineSearch={...combined.search,pages:undefined,sources:combined.sources.map(({pages,sections,...source})=>source)};saveConfig();
      webState.lastResult=combined;webState.lastRun=new Date().toISOString();webState.lastError=combined.success?null:combined.errors.map(issue=>issue.message||issue.error||'Ошибка источника').join('; ').slice(0,600)||'Источники не дали поддерживаемых объявлений.';
      return combined;
    })();
    activeSearch=operation;
    try{return await operation;}finally{searchProgress.running=false;searchProgress.currentSource=null;if(activeSearch===operation)activeSearch=null;}
  }
  function safeError(e){
    let m=String(e?.message||'Неизвестная ошибка.').slice(0,600);
    if(apiKey)m=m.split(apiKey).join('[ключ скрыт]');
    return m.replace(/api_key=[^\s&"']+/gi,'api_key=[скрыт]');
  }
  const monitor=options.monitor||require('./lib/monitor.cjs').createPriceMonitor({store,preferences,fx,getRia,webScanner,ingest:ingestRecords,notify:options.notifier||showWindowsNotifications,intervalMinutes:()=>settings.intervalMinutes,stateFile:path.join(dataDir,'monitor.json'),allListings:()=>allFiltered()});
  function status(){return {appId:APP_ID,appVersion:APP_VERSION,apiVersion:API_VERSION,desktop:options.desktop===true||process.env.DOMTRACK_DESKTOP==='1',instanceId,dataId,serverPid:process.pid,...store.stats(),onlineSearch,searchProgress:{...searchProgress},apiKeyConfigured:!!apiKey,keyStorage:{stored:keyStored,available:process.platform==='win32',error:keyStorageError},collector:{...collector,settings:{...settings},configured:!!apiKey,quota:quota()},web:{...webState},monitor:monitor.status(),unreadNotifications:preferences.get().notifications.filter(n=>!n.read).length,coverage:{sources:connectedSources(settings.cityName).filter(source=>source.available!==false).map(source=>source.id),additionalApi:'domria',historicalFrom:'first-observation',allInternet:false}};}

  const server=http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('X-Frame-Options','DENY');res.setHeader('Cross-Origin-Resource-Policy','same-origin');
    const address=server.address();const port=address&&typeof address==='object'?address.port:8789;
    const hosts=new Set([`127.0.0.1:${port}`,`localhost:${port}`]);
    try{
      if(!hosts.has(req.headers.host))throw error('Недопустимый Host. Используйте адрес 127.0.0.1.',403);
      if(req.headers.origin&&!new Set([`http://127.0.0.1:${port}`,`http://localhost:${port}`]).has(req.headers.origin))throw error('Запрос другого сайта отклонён.',403);
      const url=new URL(req.url,`http://127.0.0.1:${port}`);
      if(url.pathname.startsWith('/api/')){
        if(req.headers['x-app-token']!==token)throw error('Перезагрузите страницу приложения: сеанс не подтверждён.',403);
        if(req.method==='GET'&&url.pathname==='/api/status')return send(res,200,status());
        if(req.method==='GET'&&url.pathname==='/api/catalog'){
          const city=url.searchParams.get('city')||settings.cityName;let sources=[];try{sources=sourceRegistry.getSources(city);}catch{}
          return send(res,200,{...catalog.getCatalog(),sources,coverage:'Поиск по подключённым открытым площадкам. Поддержка показана для выбранного города; результаты и ошибки каждого источника отображаются отдельно. Полный интернет не охвачен.'});
        }
        if(req.method==='POST'&&url.pathname==='/api/search')return send(res,200,await searchOnline(await body(req)));
        if(req.method==='GET'&&url.pathname==='/api/listings')return send(res,200,await listResponse(Object.fromEntries(url.searchParams)));
        if(req.method==='GET'&&/^\/api\/listings\/[^/]+\/insights$/.test(url.pathname)){
          const id=decodeURIComponent(url.pathname.split('/')[3]);const d=store.detail(id);if(!d)throw error('Объявление не найдено.',404);return send(res,200,analyzeListing(d.listing,allFiltered(),preferences.get().duplicateRules));
        }
        if(req.method==='GET'&&/^\/api\/listings\/[^/]+$/.test(url.pathname)){
          const d=detailWithFx(decodeURIComponent(url.pathname.split('/').pop()));if(!d)throw error('Объявление не найдено.',404);return send(res,200,d);
        }
        if(req.method==='GET'&&url.pathname==='/api/cities')return send(res,200,{cities:store.cities()});
        if(req.method==='GET'&&url.pathname==='/api/export')return send(res,200,store.exportData());
        if(req.method==='POST'&&url.pathname==='/api/import')return send(res,200,ingestRecords(readImport(await body(req))));
        if(req.method==='POST'&&url.pathname==='/api/demo'){await body(req);return send(res,200,ingestRecords(demoRecords()));}
        if(req.method==='GET'&&url.pathname==='/api/duplicates')return send(res,200,analyze(allFiltered()));
        if(req.method==='GET'&&url.pathname==='/api/summary'){
          const day=url.searchParams.get('day')||kyivDate();if(!/^\d{4}-\d{2}-\d{2}$/.test(day))throw error('Дата сводки: YYYY-MM-DD.');
          return send(res,200,buildDailySummary(allFiltered(),{day,notifications:preferences.get().notifications,records:store.exportData().records}));
        }
        if(req.method==='GET'&&url.pathname.startsWith('/api/groups/')){
          const id=decodeURIComponent(url.pathname.split('/').pop());if(!/^dup-[a-f0-9]{20}$/.test(id))throw error('Некорректный ID группы.',400);
          let group=groupCache.get(id);if(!group){analyze(allFiltered());group=groupCache.get(id);}if(!group)throw error('Группа изменилась. Обновите список клонов.',404);
          const history=group.items.flatMap(r=>(detailWithFx(r.id)?.history||[]).map(h=>({...h,listingId:r.id,source:r.source}))).sort((a,b)=>a.observedAt.localeCompare(b.observedAt));
          return send(res,200,{group,history});
        }
        if(req.method==='GET'&&url.pathname==='/api/fx')return send(res,200,fx.status(url.searchParams.has('date')?validateDate(url.searchParams.get('date')):undefined));
        if(req.method==='POST'&&url.pathname==='/api/fx/refresh'){const data=await body(req);const quote=await fx.refresh(data.date||kyivDate());groupCache.clear();return send(res,200,quote);}
        if(req.method==='POST'&&url.pathname==='/api/fx/history'){
          const data=await body(req),d=store.detail(data.id);if(!d)throw error('Объявление не найдено.',404);return send(res,200,{listing:d.listing,...await fx.loadHistory(d.history)});
        }
        if(req.method==='GET'&&url.pathname==='/api/preferences')return send(res,200,preferences.get());
        if(req.method==='POST'&&url.pathname==='/api/preferences'){
          const data=await body(req);
          if(data.watch?.id&&data.watch.active&&!data.watch.currency){const d=store.detail(data.watch.id);if(d)data.watch.currency=d.listing.currency;}
          const result=preferences.update(data,{hasListing:id=>!!store.detail(id),validateFilters:filters=>{planCurrency(filters,fx.status().requestedDate);store.list({...filters,page:1,pageSize:1});}});
          groupCache.clear();if(data.watch||data.removeWatch)monitor.schedule();return send(res,200,result);
        }
        if(req.method==='GET'&&url.pathname==='/api/watch/status')return send(res,200,monitor.status());
        if(req.method==='POST'&&url.pathname==='/api/watch/check'){const data=await body(req);return send(res,200,await monitor.check({id:data.id}));}
        if(req.method==='POST'&&url.pathname==='/api/web/scan')return send(res,200,await scanWeb(await body(req)));
        if(req.method==='POST'&&url.pathname==='/api/shutdown'){
          const data=await body(req);if(data.serverPid!==process.pid)throw error('PID приложения не совпадает.',403);send(res,200,{message:'Приложение завершает работу.'});setTimeout(()=>closeApp().catch(()=>{}),50);return;
        }
        if(req.method==='POST'&&url.pathname==='/api/settings'){
          if(collector.running)throw error('Настройки нельзя менять во время сбора.',409);
          const data=await body(req);
          const next=data.collector?validateSettings({...settings,...data.collector}):settings;
          if(data.apiKey!==undefined){
            if(typeof data.apiKey!=='string'||data.apiKey.length>500||/[\r\n]/.test(data.apiKey))throw error('Некорректный API-ключ.');
            const nextKey=data.apiKey.trim();
            try{if(nextKey)await keyVault.save(nextKey);else await keyVault.remove();}catch{throw error('Не удалось обновить защищённое хранилище. Текущий ключ не изменён.',500);}
            apiKey=nextKey;keyStored=!!nextKey;keyStorageError=null;ria=null;cache.regions=null;cache.cities.clear();
          }
          if(next.monthlyBudget!==settings.monthlyBudget)ria=null;
          settings=next;collector.settings=settings;saveConfig();schedule();return send(res,200,{message:'Настройки сохранены. API-ключ защищён хранилищем Windows и загрузится при следующем запуске.',...status()});
        }
        if(req.method==='GET'&&url.pathname==='/api/ria/regions'){
          if(!cache.regions)cache.regions=await getRia().regions();return send(res,200,{regions:cache.regions});
        }
        if(req.method==='GET'&&url.pathname==='/api/ria/cities'){
          const id=positive(url.searchParams.get('stateId'),'Область ID',1,10000000);
          if(!cache.cities.has(id))cache.cities.set(id,await getRia().cities(id));return send(res,200,{cities:cache.cities.get(id)});
        }
        if(req.method==='POST'&&url.pathname==='/api/collect'){await body(req);return send(res,200,await collect());}
        throw error('Такого API-метода нет.',404);
      }
      if(req.method!=='GET'&&req.method!=='HEAD')throw error('Метод не поддерживается.',405);
      const files={'/':['index.html','text/html; charset=utf-8'],'/index.html':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','text/javascript; charset=utf-8'],'/styles.css':['styles.css','text/css; charset=utf-8'],'/favicon.ico':['app.ico','image/x-icon'],'/app-icon.svg':['app-icon.svg','image/svg+xml']};
      const file=files[url.pathname];if(!file)throw error('Страница не найдена.',404);
      let content=fs.readFileSync(path.join(PUBLIC,file[0]));
      if(file[0]==='index.html')content=Buffer.from(content.toString('utf8').replaceAll('__APP_TOKEN__',token));
      res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-src https://www.openstreetmap.org; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
      res.writeHead(200,{'Content-Type':file[1],'Cache-Control':'no-store'});res.end(req.method==='HEAD'?undefined:content);
    }catch(e){if(!res.headersSent)send(res,Number.isInteger(e.statusCode)?e.statusCode:500,{error:safeError(e)});else res.end();}
  });
  server.requestTimeout=180000;server.headersTimeout=10000;server.maxHeadersCount=50;
  function publishEndpoint(port){
    const temporary=instancePath+'.tmp-'+instanceId;
    try{fs.writeFileSync(temporary,JSON.stringify({appId:APP_ID,appVersion:APP_VERSION,apiVersion:API_VERSION,port,serverPid:process.pid,instanceId,dataId}),{mode:0o600,flag:'wx'});fs.renameSync(temporary,instancePath);publishedPort=port;}
    finally{try{fs.unlinkSync(temporary);}catch{}}
  }
  function removeOwnEndpoint(){
    if(publishedPort===null)return;
    try{
      const stat=fs.statSync(instancePath);if(!stat.isFile()||stat.size>4096)return;
      const current=JSON.parse(fs.readFileSync(instancePath,'utf8'));
      if(current.appId===APP_ID&&current.serverPid===process.pid&&current.instanceId===instanceId&&current.port===publishedPort)fs.unlinkSync(instancePath);
    }catch{}
  }
  function closeApp(){
    if(closing)return closing;
    closed=true;clearTimeout(timer);
    closing=(async()=>{await monitor.close();await Promise.allSettled([activeCollect,activeScan,activeSearch].filter(Boolean));await new Promise(resolve=>server.close(resolve));removeOwnEndpoint();store.close();preferences.close?.();})();
    return closing;
  }
  return {server,store,token,dataDir,status,collect,
    async start(port=options.port??0){await initializeKey();return new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>{server.off('error',reject);const address=server.address();try{publishEndpoint(address.port);}catch(e){server.close(()=>reject(e));return;}schedule();monitor.schedule();resolve(address);});});},
    close:closeApp
  };
}

if(require.main===module){
  const major=Number(process.versions.node.split('.')[0]);if(major<24){console.error('Требуется Node.js 24 или новее: https://nodejs.org/');process.exit(1);}
  const app=createApp();const port=Number(process.env.DOMTRACK_PORT??0);
  if(!Number.isInteger(port)||(port!==0&&port<1024)||port>65535){console.error('Некорректный DOMTRACK_PORT.');process.exit(1);}
  app.start(port).then(address=>{
    const url=`http://127.0.0.1:${address.port}`;console.log(`DomTrack UA — ${url}\nДанные: ${app.dataDir}\nОстановка: Ctrl+C. Сохранённый API-ключ защищён Windows DPAPI.`);
    if(process.argv.includes('--open')&&process.platform==='win32')require('node:child_process').spawn('rundll32.exe',['url.dll,FileProtocolHandler',url],{stdio:'ignore',windowsHide:true,detached:true}).unref();
  }).catch(e=>{console.error(e.code==='EADDRINUSE'?'Указанный порт занят. Запустите приложение с автоматическим выбором порта.':e.message);process.exitCode=1;app.store.close();});
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>app.close().then(()=>process.exit(0)));
}
module.exports={createApp,validateSettings};

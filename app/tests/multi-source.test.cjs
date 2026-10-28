'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {createApp}=require('../server.cjs'),catalog=require('../lib/catalog.cjs');
const sources=[{id:'rieltor',name:'Rieltor.ua',url:'https://rieltor.ua/',available:true,kinds:['apartment','house']},{id:'fixture2',name:'Вторая тестовая площадка',url:'https://example.org/',available:true,kinds:['apartment','house']}];
const registry={getSources:city=>{catalog.resolveSearch(city);return sources.map(source=>({...source}));},resolvePage:(id,city,kind,page)=>{
 const place=catalog.resolveSearch(city,kind);assert.ok(sources.some(source=>source.id===id));
 return id==='rieltor'?catalog.resolvePage(city,kind,page):{...place,url:`https://example.org/${kind}/?page=${page}`};
}};
function response(spec){
 const records=[],pageResults=[];
 for(const [seedIndex,href]of spec.urls.entries()){
  const url=new URL(href),page=Number(url.searchParams.get('page')||1),kind=url.pathname.includes('houses-sale')||url.pathname.includes('/house/')?'house':'apartment';
  records.push({source:'web:'+url.hostname,externalId:`${kind}-${page}`,url:`https://${url.hostname}/object/${kind}-${page}`,title:`Тестове житло ${kind} ${page}`,city:spec.cityName,region:spec.regionName,deal:'sale',kind,price:50000,currency:'USD',observedAt:'2026-10-20T10:00:00Z'});
  pageResults.push({seedIndex,url:url.origin+url.pathname,status:'ok',recordsFound:1});
 }
 return{records,pageResults,pagesVisited:spec.urls.length,pagesAttempted:spec.urls.length,errors:[],unsupportedPages:[],notices:[]};
}
async function setup(t,scan,configure){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'domtrack-multi-'));if(configure)configure(dir);
 const app=createApp({dataDir:dir,sourceRegistry:registry,webScanner:{scan},keyVault:{load:async()=>null},notifier:()=>{},monitor:{status:()=>({}),schedule(){},close:async()=>{},evaluate:async()=>{}}});
 const address=await app.start(0),url=`http://127.0.0.1:${address.port}`;
 t.after(async()=>{await app.close();assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir())+path.sep));fs.rmSync(dir,{recursive:true,force:true});});
 const req=(endpoint,payload)=>fetch(url+endpoint,{method:payload===undefined?'GET':'POST',headers:{'X-App-Token':app.token,'Content-Type':'application/json'},body:payload===undefined?undefined:JSON.stringify(payload)});
 return {app,dir,req};
}
test('one search scans both sources and exposes their actual results and independent next pages',async t=>{
 const calls=[];const {req}=await setup(t,async spec=>{calls.push(spec);return response(spec);});
 const result=await req('/api/search',{city:'Запорожье',kind:'both'}).then(r=>r.json());
 assert.equal(calls.length,2);assert.equal(result.recordsFound,8);assert.equal(result.cityListings,8);assert.equal(result.partial,false);
 assert.deepEqual(result.sources.map(source=>[source.id,source.recordsFound,source.pagesVisited]),[['rieltor',4,4],['fixture2',4,4]]);
 assert.deepEqual(result.search.nextPages,{'rieltor:apartment':3,'rieltor:house':3,'fixture2:apartment':3,'fixture2:house':3});
 const next=await req('/api/search',{city:'Запорожье',kind:'both',mode:'more'}).then(r=>r.json());
 assert.equal(next.cityListings,16);assert.ok(calls[2].urls[0].includes('page=3'));assert.ok(calls[3].urls[0].includes('page=3'));
 const status=await req('/api/status').then(r=>r.json());assert.equal(status.searchProgress.running,false);assert.equal(status.onlineSearch.sources.length,2);
});
test('a failed source does not suppress another source or advance its own unconfirmed cursor',async t=>{
 let fail=true;const calls=[];const {req}=await setup(t,async spec=>{calls.push(spec);if(fail&&spec.urls[0].startsWith('https://rieltor.ua/'))throw Error('Fixture unavailable');return response(spec);});
 const result=await req('/api/search',{city:'Запорожье',kind:'both'}).then(r=>r.json());
 assert.equal(result.success,true);assert.equal(result.partial,true);assert.equal(result.recordsFound,4);assert.equal(result.sources[0].outcome,'source-error');assert.equal(result.sources[1].outcome,'loaded');
 fail=false;const next=await req('/api/search',{city:'Запорожье',kind:'both',mode:'more'}).then(r=>r.json());
 assert.ok(!calls[2].urls[0].includes('page='));assert.ok(calls[3].urls[0].includes('page=3'));assert.equal(next.cityListings,12);assert.equal(next.partial,false);
});
test('legacy Rieltor pagination migrates without sharing its cursor with another provider',async t=>{
 const calls=[];const {req}=await setup(t,async spec=>{calls.push(spec);return response(spec);},dir=>fs.writeFileSync(path.join(dir,'settings.json'),JSON.stringify({onlineCursors:[{cityName:'Запоріжжя',kind:'apartment',nextPage:7,hasMore:true}]})));
 await req('/api/search',{city:'Запорожье',kind:'apartment',mode:'more'});
 assert.ok(calls[0].urls[0].includes('page=7'));assert.ok(calls[1].urls[0].includes('page=1'));
});
test('source selection accepts only registry IDs and never client-supplied routes',async t=>{
 const calls=[];const {req}=await setup(t,async spec=>{calls.push(spec);return response(spec);});
 assert.equal((await req('/api/search',{city:'Запорожье',sources:['https://127.0.0.1/']})).status,400);assert.equal(calls.length,0);
 const result=await req('/api/search',{city:'Запорожье',kind:'apartment',sources:['fixture2'],urls:['http://127.0.0.1/'],maxPages:999,followLinks:true}).then(r=>r.json());
 assert.equal(calls.length,1);assert.equal(result.sources.length,1);assert.ok(calls[0].urls.every(url=>url.startsWith('https://example.org/apartment/')));assert.equal(calls[0].followLinks,false);assert.equal(calls[0].maxPages,2);
});
test('concurrent searches are rejected while source progress remains observable',async t=>{
 let release;const gate=new Promise(resolve=>{release=resolve;});let entered;const began=new Promise(resolve=>{entered=resolve;});let first=true;
 const {req}=await setup(t,async spec=>{if(first){first=false;entered();await gate;}return response(spec);});
 const pending=req('/api/search',{city:'Запорожье',kind:'apartment'});await began;
 try{const status=await req('/api/status').then(r=>r.json());assert.equal(status.searchProgress.running,true);assert.equal(status.searchProgress.currentSource,'Rieltor.ua');assert.equal(status.searchProgress.sourceCount,2);assert.equal((await req('/api/search',{city:'Запорожье'})).status,409);}finally{release();}
 assert.equal((await pending).status,200);
});

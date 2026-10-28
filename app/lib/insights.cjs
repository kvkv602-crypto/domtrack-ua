'use strict';

const {analyzeDuplicates,isConfirmedGroup}=require('./duplicates.cjs');
const DAY_MS=86400000;
const finite=n=>typeof n==='number'&&Number.isFinite(n);
const normalize=v=>typeof v==='string'?v.normalize('NFKC').toLocaleLowerCase('uk').replace(/[’‘ʼ`´]/gu,"'").replace(/[‐‑‒–—]/gu,'-').replace(/\s+/gu,' ').trim():'';
const words=v=>normalize(v).match(/[\p{L}]+(?:'[\p{L}]+)?/gu)||[];
const TITLE_STOP=new Set(('продажа продаж продам продається продается квартира квартиру квартири квартиры квартир будинок будинку будинки дом дома кімнат кімнати кімната комната комнаты комнат кімн кв метр метри метров площа площадь общая загальна просторная простора светлая світла новая нова новый новий ремонт ремонтом власник собственник ріелтор риелтор застройщик забудовник біля возле поруч рядом вулиця улица проспект поверх этаж поверхів этажей будинок').split(' '));
function bad(message){throw Object.assign(new Error(message),{statusCode:400});}
function milliseconds(value){if(typeof value!=='string'&&typeof value!=='number'&&!(value instanceof Date))return null;const n=new Date(value).getTime();return Number.isFinite(n)?n:null;}
function clock(now){const n=milliseconds(now===undefined?Date.now():now);if(n===null)bad('Некорректное текущее время.');return n;}
function dateKyiv(value){const n=milliseconds(value);if(n===null)return null;const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Kyiv',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(n));const p=t=>parts.find(v=>v.type===t)?.value;return `${p('year')}-${p('month')}-${p('day')}`;}
function validateDay(day){if(typeof day!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(day))bad('Дата сводки должна быть YYYY-MM-DD.');const n=milliseconds(day+'T00:00:00Z');if(n===null||new Date(n).toISOString().slice(0,10)!==day||day<'1900-01-01')bad('Некорректная дата сводки.');return day;}
function canonicalUrl(value){try{if(typeof value!=='string'||!value)return null;const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)return null;for(const name of [...u.searchParams.keys()])if(/^utm_/iu.test(name)||/^(?:gclid|fbclid|msclkid|yclid|_ga)$/iu.test(name))u.searchParams.delete(name);u.searchParams.sort();return u.href;}catch{return null;}}
function identity(item){return JSON.stringify([normalize(item.source),item.externalId===undefined||item.externalId===null||item.externalId===''?'url':'id',item.externalId===undefined||item.externalId===null||item.externalId===''?canonicalUrl(item.url):String(item.externalId)]);}
function samePlace(a,b){return !!normalize(a.city)&&normalize(a.city)===normalize(b.city)&&normalize(a.region)===normalize(b.region)&&a.kind===b.kind;}
function validPriceArea(item){return finite(item.price)&&item.price>0&&finite(item.area)&&item.area>0&&typeof item.currency==='string';}

function freshness(target,now,staleDays){
  const seen=milliseconds(target.lastSeen);
  if(seen===null||seen>now+300000)return {stale:null,ageDays:null,lastChecked:null,thresholdDays:staleDays,label:'Дата последней успешной проверки неизвестна'};
  const days=Math.max(0,(now-seen)/DAY_MS);
  return {stale:days>=staleDays,ageDays:Math.round(days*10)/10,lastChecked:new Date(seen).toISOString(),thresholdDays:staleDays,label:days>=staleDays?'Давно не проверялось':'Недавно проверено'};
}

function compare(target,allItems,{sameGroups,differentPairs}){
  const targetPricePerM2=validPriceArea(target)?target.price/target.area:null;
  const methodLabel='Сравнение запрашиваемых цен объявлений, а не подтверждённых сделок';
  const result={count:0,medianPricePerM2:null,currency:target.currency||null,targetPricePerM2,differencePercent:null,items:[],methodLabel,minimumSample:3,districtMatched:!!normalize(target.district)};
  if(targetPricePerM2===null)return {...result,reason:'Недостаточно данных: нужны цена, валюта и площадь объекта.'};
  const candidates=allItems.filter(item=>item&&samePlace(target,item)&&item.currency===target.currency&&validPriceArea(item)&&item.status!=='inactive'&&item.period!== 'month'&&item.period!=='day'&&item.deal!=='rent'&&item.area>=target.area*.8&&item.area<=target.area*1.2&&(!normalize(target.district)||normalize(item.district)===normalize(target.district))&&(!finite(target.rooms)||finite(item.rooms)&&item.rooms===target.rooms));
  const targetIdentity=identity(target);
  const unique=new Map();
  for(const item of [target,...candidates]){
    const key=identity(item);
    const prior=unique.get(key);
    if(!prior||(milliseconds(item.lastSeen)||0)>(milliseconds(prior.lastSeen)||0))unique.set(key,item);
  }
  const originals=[...unique.values()];
  const deduped=analyzeDuplicates(originals,{sameGroups,differentPairs});
  const excluded=new Set([targetIdentity]);
  // Similarity alone does not prove that two offers describe the same property.
  // Keep original offers unless provenance or the user's rule confirms the group.
  for(const group of deduped.groups.filter(isConfirmedGroup)){
    if(group.items.some(item=>identity(item)===targetIdentity)){
      for(const item of group.items)excluded.add(identity(item));
    }else{
      const representative=group.items.find(item=>item.id===group.representativeId)||group.items[0];
      const retained=identity(representative);
      for(const item of group.items)if(identity(item)!==retained)excluded.add(identity(item));
    }
  }
  const eligible=originals.filter(item=>!excluded.has(identity(item))).map(item=>({...item,pricePerM2:item.price/item.area}));
  result.count=eligible.length;
  result.items=eligible.sort((a,b)=>Math.abs(a.area-target.area)-Math.abs(b.area-target.area)||String(a.id).localeCompare(String(b.id))).slice(0,10);
  if(result.count<3)return {...result,reason:`Недостаточно похожих предложений: найдено ${result.count}, нужно минимум 3.`};
  const values=eligible.map(item=>item.pricePerM2).sort((a,b)=>a-b),middle=Math.floor(values.length/2);
  result.medianPricePerM2=values.length%2?values[middle]:(values[middle-1]+values[middle])/2;
  result.differencePercent=result.medianPricePerM2>0?(targetPricePerM2/result.medianPricePerM2-1)*100:null;
  result.reason=normalize(target.district)?'Тот же город, область, район, тип жилья и валюта; площадь ±20%; комнаты совпадают, если известны.':'Тот же город, область, тип жилья и валюта; площадь ±20%; район объекта не задан.';
  return result;
}

function parsedAddress(value){
  let address=normalize(value);if(!address||address.length>2000)return null;
  let apartment=null,conflict=false;
  address=address.replace(/(?:^|[\s,;])(?:кв(?:артира)?|apt|apartment)\.?\s*(?:№\s*)?(\d+[\p{L}]?)(?=$|[\s,;])/giu,(_,n)=>{if(apartment!==null&&apartment!==n)conflict=true;apartment=n;return ' ';});
  if(conflict)return null;
  address=address.replace(/(?:^|[\s,;])(?:вулиця|вул|улица|ул|street)\.?\s*/giu,' street ').replace(/(?:^|[\s,;])(?:будинок|буд|дом|house)\.?\s*/giu,' ').replace(/[^\p{L}\p{N}'/-]+/gu,' ').replace(/\s+/gu,' ').trim();
  if(!/\d/u.test(address)||!/[\p{L}]{2}/u.test(address))return null;
  return {key:address,apartment};
}
function distinctiveTitle(target,old){
  const ignored=new Set([...TITLE_STOP,...words(target.city),...words(target.region),...words(target.address),...words(old.address)]);
  const left=new Set(words(target.title).filter(w=>w.length>=4&&!ignored.has(w))),right=new Set(words(old.title).filter(w=>w.length>=4&&!ignored.has(w)));
  const common=[...left].filter(w=>right.has(w)).length;
  return common>=4&&common/(left.size+right.size-common)>=.85;
}
function relistings(target,allItems){
  const first=milliseconds(target.firstSeen),address=parsedAddress(target.address);
  if(first===null||!address||!finite(target.area)||!finite(target.rooms)||(target.kind==='apartment'&&!finite(target.floor)))return [];
  const same=identity(target),url=canonicalUrl(target.url),possible=[];
  for(const old of allItems){
    if(!old||identity(old)===same||!samePlace(target,old)||old.deal==='rent')continue;
    const last=milliseconds(old.lastSeen),oldAddress=parsedAddress(old.address);
    if(last===null||last>=first||first-last>365*DAY_MS||!oldAddress||oldAddress.key!==address.key)continue;
    if(url&&canonicalUrl(old.url)===url)continue;
    if(!finite(old.rooms)||old.rooms!==target.rooms||!finite(old.area)||Math.abs(old.area-target.area)>Math.max(.5,Math.min(old.area,target.area)*.005)+1e-8)continue;
    if(target.kind==='apartment'&&(!finite(old.floor)||old.floor!==target.floor))continue;
    if(finite(old.floor)&&finite(target.floor)&&old.floor!==target.floor)continue;
    if(finite(old.floorsTotal)&&finite(target.floorsTotal)&&old.floorsTotal!==target.floorsTotal)continue;
    if(address.apartment&&oldAddress.apartment&&address.apartment!==oldAddress.apartment)continue;
    const unit=address.apartment!==null&&address.apartment===oldAddress.apartment;
    if(!unit&&!distinctiveTitle(target,old))continue;
    possible.push({id:old.id,source:old.source,url:old.url,title:old.title,lastSeen:old.lastSeen,firstSeen:old.firstSeen,price:old.price,currency:old.currency,confidence:'possible',reason:`Совпадают адрес, комнаты, площадь${target.kind==='apartment'?' и этаж':''}; ${unit?'совпадает номер квартиры':'сильно совпадает содержательный заголовок'}. Другое объявление наблюдалось до первого появления текущего. Снятие с публикации не подтверждено.`});
  }
  return possible.sort((a,b)=>(milliseconds(b.lastSeen)||0)-(milliseconds(a.lastSeen)||0)).slice(0,20);
}

function analyzeListing(target,allItems,{now=Date.now(),staleDays=7,sameGroups=[],differentPairs=[]}={}){
  if(!target||typeof target!=='object'||Array.isArray(target)||!Array.isArray(allItems)||allItems.length>50000)bad('Ожидается объект объявления и массив до 50 000 объявлений.');
  if(!finite(staleDays)||staleDays<=0||staleDays>3650)bad('Порог устаревания должен быть от 0 до 3650 дней.');
  return {freshness:freshness(target,clock(now),staleDays),comparables:compare(target,allItems,{sameGroups,differentPairs}),possibleRelistings:relistings(target,allItems)};
}

function buildDailySummary(items,{day,notifications=[],records,now=Date.now()}={}){
  if(!Array.isArray(items)||items.length>50000||!Array.isArray(notifications)||(records!==undefined&&!Array.isArray(records)))bad('Некорректные данные дневной сводки.');
  const current=clock(now);day=validateDay(day||dateKyiv(current));
  const newlySeen=new Set(items.filter(r=>milliseconds(r?.firstSeen)!==null&&milliseconds(r.firstSeen)<=current&&dateKyiv(r.firstSeen)===day).map(identity));
  let priceDrops=null,priceDropEvents=null;
  if(records!==undefined){
    const histories=new Map();
    for(const record of records){
      const time=milliseconds(record?.observedAt);
      if(time===null||time>current||!finite(record?.price)||record.price<0||typeof record.currency!=='string')continue;
      const key=identity(record);if(!histories.has(key))histories.set(key,[]);histories.get(key).push(record);
    }
    const dropped=new Set();priceDropEvents=0;
    for(const [key,history] of histories){
      history.sort((a,b)=>milliseconds(a.observedAt)-milliseconds(b.observedAt));let prior=null,lastTimestamp=null;
      for(const row of history){
        const timestamp=milliseconds(row.observedAt);if(timestamp===lastTimestamp)continue;lastTimestamp=timestamp;
        if(prior&&row.currency===prior.currency&&(row.period||'total')===(prior.period||'total')&&row.price<prior.price&&dateKyiv(row.observedAt)===day){dropped.add(key);priceDropEvents++;}
        prior=row;
      }
    }
    priceDrops=dropped.size;
  }
  const targetKeys=new Set(notifications.filter(n=>n?.type==='targetprice'&&milliseconds(n.createdAt)!==null&&milliseconds(n.createdAt)<=current&&dateKyiv(n.createdAt)===day).map(n=>n.id??JSON.stringify([n.listingId,n.createdAt,n.message])));
  const result={day,timeZone:'Europe/Kyiv',newListings:newlySeen.size,priceDrops,priceDropEvents,targetsReached:targetKeys.size,priceDropHistoryAvailable:records!==undefined};
  result.text=`Сводка за ${day}: новых объявлений — ${result.newListings}; ${priceDrops===null?'снижения цен не подсчитаны: нет истории наблюдений':`объектов со снижением цены — ${priceDrops} (${priceDropEvents} изменений)`}; достигнутых порогов — ${result.targetsReached}. Это результаты наблюдений, а не сведения о заключённых сделках.`;
  return result;
}

module.exports={analyzeListing,buildDailySummary,dateKyiv};

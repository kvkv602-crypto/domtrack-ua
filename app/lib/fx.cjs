'use strict';

const fs=require('node:fs');
const ENDPOINT='https://bank.gov.ua/NBUStatService/v1/statdirectory/exchangenew?json';
function normalizeRates(rows){
  if(!Array.isArray(rows))throw new Error('НБУ вернул неожиданный формат курсов.');
  const rates={UAH:1};let asOf=null;
  for(const cc of ['USD','EUR']){
    const row=rows.find(r=>r.cc===cc);
    if(!row||typeof row.rate!=='number'||!Number.isFinite(row.rate)||row.rate<=0||row.rate>1000000)throw new Error(`Не найден корректный курс ${cc}.`);
    const match=/^(\d{2})\.(\d{2})\.(\d{4})$/.exec(row.exchangedate||'');
    if(!match)throw new Error('Не указана дата курса НБУ.');
    const [,d,m,y]=match;const iso=`${y}-${m}-${d}`;
    const parsed=new Date(iso+'T00:00:00Z');
    if(!Number.isFinite(parsed.getTime())||parsed.toISOString().slice(0,10)!==iso)throw new Error('Некорректная дата курса НБУ.');
    if(asOf&&asOf!==iso)throw new Error('Даты курсов валют не совпадают.');
    asOf=iso;rates[cc]=row.rate;
  }
  return {rates,asOf,source:'НБУ',sourceUrl:ENDPOINT,fetchedAt:new Date().toISOString()};
}
function kyivDate(value=new Date()){
  const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Kyiv',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(value));
  const part=t=>parts.find(i=>i.type===t).value;return `${part('year')}-${part('month')}-${part('day')}`;
}
function validateDate(value){
  if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value))throw Object.assign(new Error('Дата курса: YYYY-MM-DD.'),{statusCode:400});
  const d=new Date(value+'T00:00:00Z');
  if(!Number.isFinite(d.getTime())||d.toISOString().slice(0,10)!==value||value<'1996-09-02'||value>kyivDate())throw Object.assign(new Error('Укажите существующую дату с 02.09.1996 по сегодня.'),{statusCode:400});
  return value;
}
function createFxStore({file,fetchImpl=fetch,now=Date.now,failureCooldownMs=60000}={}){
  let selectedDate=kyivDate();const records=new Map(),pending=new Map(),failures=new Map();let lastRequestAt=0;
  if(file){try{const data=JSON.parse(fs.readFileSync(file,'utf8'));for(const r of data.entries||[])if(r&&r.requestedDate===r.asOf&&r.rates?.UAH===1&&['USD','EUR'].every(c=>Number.isFinite(r.rates[c])&&r.rates[c]>0&&r.rates[c]<1000000)){try{validateDate(r.requestedDate);records.set(r.requestedDate,r);}catch{}}}catch{}}
  function persist(){if(file){fs.writeFileSync(file+'.tmp',JSON.stringify({version:1,entries:[...records.values()]},null,2));fs.renameSync(file+'.tmp',file);}}
  function status(date=selectedDate){const r=records.get(date),failure=failures.get(date);return r?{...r,usable:true,stale:false}:{rates:{UAH:1},asOf:null,requestedDate:date,source:'НБУ',usable:false,stale:true,...(failure?{lastError:failure.message,retryAt:failure.retryAt}:{})};}
  function checkFailure(date,force){const failure=failures.get(date);if(!force&&failure&&failure.retryAt>now())throw Object.assign(new Error(failure.message),{code:'FX_RETRY_WAIT',retryAt:failure.retryAt,statusCode:503});}
  async function getForDate(date,{force=false}={}){
    validateDate(date);if(!force&&records.has(date))return status(date);if(pending.has(date))return pending.get(date);checkFailure(date,force);
    while(pending.size>=2){await Promise.race([...pending.values()].map(p=>p.catch(()=>null)));if(pending.has(date))return pending.get(date);if(!force&&records.has(date))return status(date);}
    const operation=(async()=>{
      const wait=Math.max(0,lastRequestAt+300-now());lastRequestAt=now()+wait;
      if(wait)await new Promise(resolve=>setTimeout(resolve,wait));
      const url=ENDPOINT+'&date='+date.replaceAll('-','');
      const r=await fetchImpl(url,{redirect:'error',signal:AbortSignal.timeout(10000),headers:{Accept:'application/json','User-Agent':'DomTrackUA/1.0'}});
      if(!r.ok)throw new Error(`НБУ: HTTP ${r.status}. Курс за ${date} не обновлён.`);
      let bytes=0;const chunks=[];
      for await(const part of r.body){bytes+=part.byteLength;if(bytes>256*1024)throw new Error('Ответ НБУ слишком большой.');chunks.push(Buffer.from(part));}
      let rows;try{rows=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw new Error('Ответ НБУ не является JSON.');}
      const result={...normalizeRates(rows),requestedDate:date,sourceUrl:url};
      if(result.asOf!==date)throw new Error(`НБУ вернул курс за ${result.asOf}, запрошено ${date}. Цена не пересчитана.`);
      records.set(date,result);failures.delete(date);persist();return status(date);
    })();
    pending.set(date,operation);try{return await operation;}catch(error){const reason=String(error?.message||'Сетевая ошибка.').slice(0,300),message=`Курс НБУ на ${date} недоступен: ${reason} Повторная автоматическая попытка возможна через минуту.`;failures.set(date,{message,retryAt:now()+Math.max(1000,failureCooldownMs)});if(failures.size>120)failures.delete(failures.keys().next().value);throw Object.assign(new Error(message),{cause:error,retryAt:failures.get(date).retryAt});}finally{pending.delete(date);}
  }
  async function refresh(date=kyivDate(),options={}){validateDate(date);const result=await getForDate(date,options);selectedDate=date;return result;}
  function convertHistory(history){return history.map(h=>{
    const date=kyivDate(h.observedAt),quote=status(date),rate=h.currency==='UAH'?1:quote.usable?quote.rates[h.currency]:null;
    return {...h,priceUAH:Number.isFinite(rate)?Math.round(h.price*rate*100)/100:null,fxRate:rate,fxDate:Number.isFinite(rate)?date:null};
  });}
  async function loadHistory(history){
    const dates=[...new Set(history.filter(h=>h.currency!=='UAH').map(h=>kyivDate(h.observedAt)))];
    const missing=dates.filter(d=>!records.has(d)),errors=[];
    for(let i=0;i<Math.min(missing.length,30);i+=2)await Promise.all(missing.slice(i,i+2).map(d=>getForDate(d).catch(e=>errors.push({date:d,message:e.message}))));
    return {history:convertHistory(history),historicalFxPending:dates.filter(d=>!records.has(d)).length,fxErrors:errors};
  }
  return {status,refresh,getForDate,convertHistory,loadHistory};
}
module.exports={normalizeRates,createFxStore,ENDPOINT,kyivDate,validateDate};

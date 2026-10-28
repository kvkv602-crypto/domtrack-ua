'use strict';

const aliases = {
  source: ['source','источник','джерело'], externalId: ['externalid','id','idобъявления','idоголошення'],
  url: ['url','link','ссылка','посилання'], title: ['title','название','заголовок','назва'],
  city: ['city','город','місто'], region: ['region','область','регион','регіон'],
  kind: ['kind','type','тип','типобъекта','типобєкта'], deal: ['deal','сделка','операция','операція'],
  price: ['price','цена','ціна'], currency: ['currency','валюта'],
  area: ['area','площадь','площа','площадьм2','площам2'], rooms: ['rooms','комнаты','кімнати','комнат','кімнат'],
  floor: ['floor','этаж','поверх'], floorsTotal: ['floorstotal','floors','этажность','этажей','поверховість','поверхів'],
  address: ['address','адрес','адреса'], observedAt: ['observedat','date','дата','датанаблюдения','датаспостереження'],
  district: ['district','район'], latitude: ['latitude','широта'], longitude: ['longitude','долгота','довгота'],
  contactPhone: ['contactphone','phone','телефон','номер'], contactEmail: ['contactemail','email','пошта'], contactUrl: ['contacturl','связь','контакт'], sellerName: ['sellername','имяпродавца','імяпродавця'],
  status: ['status','статус'], period: ['period','период','період'],
  sellerType: ['sellertype','продавец','продавець','типпродавца','типпродавця']
};
const cleanHeader = s => String(s).normalize('NFKC').toLowerCase().replace(/[\s_\-.'’ʼ()/²]/g,'').replace(/м2$/,'м2');
const headerMap = new Map(Object.entries(aliases).flatMap(([k,values])=>values.map(v=>[cleanHeader(v),k])));
function bad(message) { const e=new Error(message); e.statusCode=400; throw e; }
function parseNumber(v, field) {
  if (v === '' || v == null) return undefined;
  const value=String(v).trim().replace(/[\s\u00a0\u202f]/g,'').replace(',','.');
  if (!/^-?\d+(?:\.\d+)?$/.test(value)) bad(`Некорректное число в поле «${field}»: ${String(v).slice(0,40)}`);
  const n=Number(value); if(!Number.isFinite(n)) bad(`Некорректное число: ${field}`); return n;
}
function parseCSV(text) {
  if (typeof text!=='string' || Buffer.byteLength(text,'utf8')>16*1024*1024) bad('CSV должен быть текстом размером до 16 МБ.');
  text=text.replace(/^\uFEFF/,'');
  const first=text.split(/\r?\n/,1)[0];
  let quoted=false, commas=0, semis=0;
  for(let i=0;i<first.length;i++) { if(first[i]==='"') quoted=!quoted; else if(!quoted) {if(first[i]===',')commas++;if(first[i]===';')semis++;} }
  const sep=semis>commas?';':',';
  const rows=[]; let row=[], value='', inside=false, closed=false;
  for(let i=0;i<text.length;i++) {
    const c=text[i];
    if(inside) {if(c==='"') {if(text[i+1]==='"'){value+='"';i++;}else{inside=false;closed=true;}}else value+=c;continue;}
    if(c==='"') {if(value || closed) bad('Кавычки CSV должны начинаться в начале поля.');inside=true;continue;}
    if(c===sep) {row.push(value);value='';closed=false;continue;}
    if(c==='\r'||c==='\n') {if(c==='\r'&&text[i+1]==='\n')i++;row.push(value);if(row.some(v=>v!==''))rows.push(row);row=[];value='';closed=false;continue;}
    if(closed) {if(c===' '||c==='\t')continue;bad('Лишние символы после закрывающей кавычки CSV.');}
    value+=c;
  }
  if(inside)bad('В CSV не закрыта кавычка.');
  row.push(value);if(row.some(v=>v!==''))rows.push(row);
  if(rows.length<2)bad('CSV должен содержать заголовки и хотя бы одну строку данных.');
  if(rows.length>10001)bad('Не более 10 000 строк за один импорт.');
  const columns=rows.shift().map(h=>headerMap.get(cleanHeader(h))||null);
  const found=columns.filter(Boolean); if(new Set(found).size!==found.length)bad('В CSV повторяются одинаковые столбцы.');
  for(const k of ['city','price','currency','kind'])if(!found.includes(k))bad(`В CSV отсутствует обязательный столбец ${k}.`);
  return rows.map((cells,i)=>{
    if(cells.length!==columns.length)bad(`Строка CSV ${i+2}: число полей не совпадает с заголовком.`);
    const record={source:'import',deal:'sale',period:'total'};
    columns.forEach((key,j)=>{if(key && cells[j].trim()!=='')record[key]=cells[j].trim();});
    for(const key of ['price','area','rooms','floor','floorsTotal','latitude','longitude'])if(key in record)record[key]=parseNumber(record[key],key);
    const kind=String(record.kind).toLowerCase();record.kind=({квартира:'apartment',дом:'house',будинок:'house'})[kind]||kind;
    const currency=String(record.currency).toUpperCase();record.currency=({'$':'USD','€':'EUR','ГРН':'UAH','₴':'UAH'})[currency]||currency;
    if(record.sellerType){const seller=String(record.sellerType).toLowerCase();record.sellerType=({собственник:'owner',хозяин:'owner',власник:'owner',застройщик:'developer',забудовник:'developer',риелтор:'agent',риэлтор:'agent',ріелтор:'agent',невідомо:'unknown',неизвестно:'unknown'})[seller]||seller;}
    if(record.deal && !['sale','продажа','продаж'].includes(String(record.deal).toLowerCase()))bad(`Строка ${i+2}: эта версия предназначена только для продажи.`);
    if(record.period&&record.period!=='total')bad(`Строка ${i+2}: цена должна быть за весь объект (period=total).`);
    record.deal='sale';record.period='total';return record;
  });
}
function readImport(payload) {
  if(!payload || typeof payload!=='object' || Array.isArray(payload))bad('Ожидается JSON-объект с records или csv.');
  if(typeof payload.csv==='string')return parseCSV(payload.csv);
  if(payload.format && (payload.format!=='domtrack-ua'||payload.version!==1))bad('Неподдерживаемый формат резервной копии.');
  if(!Array.isArray(payload.records)||!payload.records.length)bad('records должен содержать хотя бы одно наблюдение.');
  if(payload.records.length>50000)bad('Не более 50 000 наблюдений за один JSON-импорт.');
  return payload.records.map((r,i)=>{
    if(!r || typeof r!=='object'||Array.isArray(r))bad(`Запись ${i+1}: ожидается объект.`);
    if(r.deal && r.deal!=='sale')bad(`Запись ${i+1}: поддерживается только продажа.`);
    if(r.period && r.period!=='total')bad(`Запись ${i+1}: цена должна быть за весь объект (period=total).`);
    return {source:'import',...r,deal:'sale',period:'total'};
  });
}
module.exports={parseCSV,readImport};

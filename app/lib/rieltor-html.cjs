'use strict';

const crypto=require('node:crypto');
const {parse}=require('./vendor/node_modules/parse5');
const {getCatalog,resolveSearch}=require('./catalog.cjs');
const MAX_BYTES=2*1024*1024;
const cities=getCatalog().cities;
const bases=new Map();
for(const city of cities)for(const kind of ['apartment','house'])for(const url of resolveSearch(city.cityName,kind).urls)bases.set(new URL(url).pathname,{city,kind});
const key=value=>String(value||'').normalize('NFKC').replace(/\s+/g,' ').trim().toLocaleLowerCase('uk');
function attr(node,name){return(node?.attrs||[]).find(item=>item.name===name)?.value||'';}
function has(node,name){return attr(node,'class').split(/\s+/).includes(name);}
function hidden(node){
  if(!node)return true;
  if((node.attrs||[]).some(item=>item.name==='hidden'))return true;
  if(attr(node,'aria-hidden').toLowerCase()==='true')return true;
  if(/(?:display\s*:\s*none|visibility\s*:\s*hidden)/i.test(attr(node,'style')))return true;
  return attr(node,'class').split(/\s+/).some(name=>['hide','hidden','-hidden','-hide','d-none','sr-only','visually-hidden'].includes(name));
}
function visible(node){for(let current=node;current;current=current.parentNode)if(hidden(current))return false;return true;}
function descendants(node){
  const result=[],queue=node?[node]:[];
  while(queue.length){const current=queue.pop();if(['script','style','template','noscript'].includes(current.tagName)||hidden(current))continue;result.push(current);if(result.length>100000)throw Error('Слишком сложная HTML-страница.');for(let i=(current.childNodes||[]).length-1;i>=0;i--)queue.push(current.childNodes[i]);}
  return result;
}
function text(node){return descendants(node).filter(item=>item.nodeName==='#text').map(item=>item.value||'').join(' ').replace(/[\u0000-\u001f\u007f]/g,' ').replace(/\s+/g,' ').trim();}
function first(node,name){return descendants(node).find(item=>has(item,name));}
function clean(value,max=200){return String(value||'').replace(/[\u0000-\u001f\u007f<>]/g,' ').replace(/\s+/g,' ').trim().slice(0,max);}
function knownCity(value){const normalized=key(value);return cities.find(city=>[city.cityName,...city.aliases].some(alias=>key(alias)===normalized));}
function catalogLocation(value){
  try{const url=new URL(value);if(url.protocol!=='https:'||url.hostname!=='rieltor.ua'||url.port||url.username||url.password||url.hash)return null;const location=bases.get(url.pathname);if(!location)return null;const entries=[...url.searchParams];if(entries.some(([name,number])=>name!=='page'||!/^\d{1,5}$/.test(number)||Number(number)<1||Number(number)>10000)||entries.length>1)return null;return{...location,url};}catch{return null;}
}
function detailLocation(value,page,kind){
  try{const url=new URL(value,page);if(url.protocol!=='https:'||url.origin!=='https://rieltor.ua'||url.username||url.password||url.port)return null;const match=/^\/(?:[a-z0-9-]+\/)?(flats|houses)-sale\/view\/([1-9]\d{0,11})\/$/.exec(url.pathname);if(!match||(match[1]==='flats'?'apartment':'house')!==kind)return null;url.search='';url.hash='';return{url:url.href,id:match[2]};}catch{return null;}
}
function wholePrice(node){
  const value=text(node);
  if(/(?:\/|\bза\b|\bper\b)\s*(?:м[²2]|m[²2]|міс|месяц|день|доба|month|day)/i.test(value))return null;
  const match=/^(\d[\d\s\u00a0\u202f]*(?:[.,]\d{1,2})?)\s*(\$|€|грн\.?|USD|UAH|EUR)$/i.exec(value);
  if(!match)return null;const price=Number(match[1].replace(/[\s\u00a0\u202f]/g,'').replace(',','.'));
  if(!Number.isFinite(price)||price<=0||price>1e12)return null;
  const unit=match[2].toUpperCase();return{price,currency:unit==='$'?'USD':unit==='€'?'EUR':/^ГРН/.test(unit)?'UAH':unit};
}
function visibleLocation(card,page){
  const region=first(card,'catalog-card-region');if(!region)return null;
  const links=descendants(region).filter(node=>node.tagName==='a'&&visible(node));
  const locality=links.find(node=>{try{const url=new URL(attr(node,'href'),page);return url.origin==='https://rieltor.ua'&&/^\/(?:[a-z0-9-]+\/)?(?:flats|houses)-sale\/$/.test(url.pathname);}catch{return false;}});
  if(!locality)return null;
  const city=clean(text(locality).replace(/^(?:м\.|місто|г\.)\s*/i,''),120);if(!city||/^(?:вся|усі|все|україна|украина)$/i.test(city))return null;
  const known=knownCity(city);
  const district=links.filter(node=>node!==locality).map(node=>clean(text(node),150)).find(value=>/(?:р-н|район)/i.test(value));
  return{city,region:known?.regionName||'',district,regionFromCatalog:!!known};
}
function streetForIdentity(address,city){const parts=address.split(',').map(value=>value.trim()).filter(Boolean);if(key(parts[0])===key(city))parts.shift();return parts[0]||'';}
function sellerType(card){
  const candidates=descendants(card).filter(node=>has(node,'catalog-card-author-subtitle')||has(node,'catalog-card-additional')||has(node,'-owner')).map(node=>text(node));
  if(candidates.some(value=>/^(?:власник|собственник|від власника|от собственника)$/iu.test(value)))return'owner';
  if(candidates.some(value=>/^(?:забудовник|застройщик)(?:\s|$)/iu.test(value)))return'developer';
  if(candidates.some(value=>/^(?:рієлтор|риелтор|агент(?:ство)?)(?:\s|$)/iu.test(value)))return'agent';
  return'unknown';
}
function extractRieltorHtml(html,pageUrl,context={}){
  const location=catalogLocation(pageUrl);if(!location)return{supported:false,records:[],reasons:[],notices:[]};
  if(typeof html!=='string'||Buffer.byteLength(html,'utf8')>MAX_BYTES)return{supported:true,records:[],reasons:['HTML-страница превышает допустимый размер.'],notices:[]};
  const document=parse(html);const nodes=descendants(document);
  const containers=nodes.filter(node=>has(node,'catalog-items-container'));
  const cards=containers.flatMap(container=>descendants(container).filter(node=>has(node,'catalog-card')&&visible(node))).slice(0,200);
  const records=new Map(),conflicts=new Set(),reasons=new Set(),notices=new Set();
  for(const card of cards){
    const media=descendants(card).filter(node=>node.tagName==='a'&&has(node,'catalog-card-media'));
    const details=media.map(node=>detailLocation(attr(node,'href'),pageUrl,location.kind)).filter(Boolean);
    if(details.length!==1){reasons.add('Карточка без однозначной исходной ссылки пропущена.');continue;}
    const detail=details[0],declared=attr(card,'data-catalog-item-id');
    if(declared&&declared!==detail.id){reasons.add('ID карточки не совпадает с ID исходного объявления.');continue;}
    const priceNodes=descendants(card).filter(node=>has(node,'catalog-card-price-title')&&visible(node));
    const prices=priceNodes.map(wholePrice);
    if(!prices.length||prices.some(price=>!price)){reasons.add('Нет однозначной видимой полной цены продажи; цена за м² и пересчитанная цена не подставлены.');continue;}
    if(new Set(prices.map(price=>`${price.price}:${price.currency}`)).size!==1){reasons.add('Противоречащие видимые полные цены карточки не импортированы.');continue;}
    const price=prices[0];
    const source=visibleLocation(card,pageUrl);if(!source){reasons.add('В карточке не указан однозначный населённый пункт.');continue;}
    const address=clean(text(first(card,'catalog-card-address')),500);
    const street=streetForIdentity(address,source.city);
    // Keep the existing JSON-LD identity formula; title/building formatting is not an ID.
    const externalId=crypto.createHash('sha256').update(JSON.stringify([detail.url,'',source.city,street,location.kind])).digest('hex').slice(0,24);
    const observedAt=new Date(context.observedAt||Date.now()).toISOString();
    const record={source:'web:rieltor.ua',externalId,url:detail.url,title:address||`${location.kind==='house'?'Будинок':'Квартира'} · ${source.city}`,city:source.city,region:source.region,kind:location.kind,deal:'sale',period:'total',...price,sellerType:sellerType(card),observedAt,status:'active'};
    if(address)record.address=address;if(source.district)record.district=source.district;
    if(source.regionFromCatalog)notices.add('Область HTML-карточек определена по справочнику подключённых городов; для неизвестных населённых пунктов область не подставлялась.');
    for(const row of descendants(card).filter(node=>has(node,'catalog-card-details-row'))){
      const value=text(row);let match;
      if((match=/^(\d{1,3})\s*(?:кімнат(?:а|и)?|комнат(?:а|ы)?)$/iu.exec(value))){const rooms=Number(match[1]);if(rooms>0&&rooms<=100)record.rooms=rooms;}
      else if((match=/^(\d+(?:[.,]\d+)?)\s*(?:\/\s*\d+(?:[.,]\d+)?\s*){0,2}\s*м[²2]$/iu.exec(value))){const area=Number(match[1].replace(',','.'));if(area>0&&area<=100000)record.area=area;}
      else if((match=/^(?:поверх|этаж)\s*(\d{1,3})\s*(?:з|із|из|\/)\s*(\d{1,3})$/iu.exec(value))){const floor=Number(match[1]),total=Number(match[2]);if(total>0&&total<=200&&floor<=total){record.floor=floor;record.floorsTotal=total;}}
      else if((match=/^(\d{1,3})\s*(?:поверх(?:и|ів)?|этаж(?:а|ей)?)$/iu.exec(value))){const total=Number(match[1]);if(total>0&&total<=200)record.floorsTotal=total;}
    }
    if(conflicts.has(detail.id))continue;
    const existing=records.get(detail.id);
    if(existing&&(existing.price!==record.price||existing.currency!==record.currency||existing.city!==record.city||existing.address!==record.address)){records.delete(detail.id);conflicts.add(detail.id);reasons.add('Противоречащие карточки одного ID не импортированы.');continue;}
    records.set(detail.id,record);
  }
  if(!cards.length)reasons.add('На странице не найдены открытые карточки каталога Rieltor.');
  return{supported:true,records:[...records.values()],reasons:[...reasons],notices:[...notices]};
}
module.exports={extractRieltorHtml,catalogLocation};

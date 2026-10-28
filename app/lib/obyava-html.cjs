'use strict';
const {publicCatalogLocation,cityFor}=require('./source-registry.cjs');
const {attr,has,walk,visible,text,clean,first,unambiguousPrice,parsed,addUnique,parameters}=require('./public-html.cjs');
function detail(value,page){try{const url=new URL(value,page);if(url.origin!=='https://obyava.ua'||url.username||url.password||url.port)return null;const match=/^\/ru\/[a-z0-9-]+-([1-9]\d{0,11})\.html$/.exec(url.pathname);if(!match)return null;url.search='';url.hash='';return{url:url.href,id:match[1]};}catch{return null;}}
function extractObyavaHtml(html,pageUrl,context={}){
 const location=publicCatalogLocation(pageUrl,'obyava');if(!location)return{supported:false,records:[],reasons:[],notices:[]};
 const document=parsed(html);if(!document)return{supported:true,records:[],reasons:['HTML-страница превышает допустимый размер.'],notices:[]};
 const containers=walk(document).filter(node=>has(node,'js-classifieds-list'));
 const cards=containers.flatMap(container=>walk(container).filter(node=>has(node,'single-item')&&visible(node))).slice(0,200);
 const records=new Map(),conflicts=new Set(),reasons=new Set();
 for(const card of cards){
  const links=walk(card).filter(node=>node.tagName==='a'&&has(node,'single-item__title')).map(node=>({node,detail:detail(attr(node,'href'),pageUrl)})).filter(item=>item.detail);
  if(!links.length||new Set(links.map(item=>item.detail.url)).size!==1){reasons.add('Карточка OBYAVA без однозначной исходной ссылки пропущена.');continue;}
  const title=clean(text(links[0].node)),identityText=`${title} ${new URL(links[0].detail.url).pathname}`.replace(/[-_/]/g,' ');
  if(!title||/(?:^|\s)(?:сдам|здам|оренда|аренда|сниму|зніму|arenda|orenda|sdam|zdam)(?:\s|$)/iu.test(identityText)){reasons.add('Карточка аренды или двусмысленной продажи/аренды пропущена.');continue;}
  if(/(?:^|\s)(?:част[ьиі]|частин[ауыиі]|дол[яиюі]|половин[ауыиеі]|chast[iaei]|chasti|doli|polovin[auyei]|комнат(?:а|у|ы|е|ой|ою|ам|ами|ах)|кімнат(?:а|у|и|і|ою|ами|ах)|komnat(?:a|u|y|e|oy|am|ami|ah)|kimnat(?:a|u|y|i|oyu)|гостинку|gostinku|общежит\S*|obshchezhit\S*)(?:\s|$)/iu.test(identityText)||/\b1\s+[234]\b/.test(identityText)){reasons.add('Доля жилья либо отдельная комната пропущена: цена не относится к целой квартире или дому.');continue;}
  if(location.kind==='house'&&/(?:\b(?:sel[eoiy]|derevn\S*)\b|(?:^|\s)(?:селі|селе|деревне|кооперативі|кооперативе)(?:\s|$))/iu.test(identityText)){reasons.add('Заголовок дома указывает село или кооператив без однозначного населённого пункта; карточка пропущена.');continue;}
  const price=unambiguousPrice(walk(card).filter(node=>has(node,'single-item__price')&&visible(node)));
  if(!price){reasons.add('Нет однозначной полной цены OBYAVA; договорные цены и цена за м² не подставлены.');continue;}
  const localities=walk(card).filter(node=>has(node,'single-item__location')&&visible(node)).map(node=>clean(text(node),150)).filter(Boolean);
  if(localities.length!==1){reasons.add('OBYAVA не указал однозначный населённый пункт карточки.');continue;}
  const city=localities[0];let known;try{known=cityFor(city);}catch{}
  const source=links[0].detail,record={source:'web:obyava.ua',externalId:source.id,url:source.url,title,city,region:known?.regionName||'',kind:location.kind,deal:'sale',period:'total',...price,sellerType:'unknown',observedAt:new Date(context.observedAt||Date.now()).toISOString(),status:'active'};
  parameters(text(first(card,'single-item__params')).split('|').map(value=>value.trim()),record);
  addUnique(records,conflicts,record,reasons);
 }
 if(!cards.length)reasons.add('Открытые HTML-карточки каталога OBYAVA не найдены.');
 return{supported:true,records:[...records.values()],reasons:[...reasons],notices:['OBYAVA: используется полная котировка в валюте, показанной площадкой; исходная валюта продавца и отсутствие пересчёта сайтом не подтверждены.','OBYAVA: населённый пункт взят из карточки площадки; точное местонахождение следует проверить в исходном объявлении. Скрытые контакты не читаются.']};
}
module.exports={extractObyavaHtml};

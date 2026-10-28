'use strict';
const {publicCatalogLocation,cityFor}=require('./source-registry.cjs');
const {attr,has,walk,visible,text,clean,first,unambiguousPrice,parsed,addUnique,parameters}=require('./public-html.cjs');
function detail(value,page,kind){try{const url=new URL(value,page);if(url.origin!=='https://dom.ria.com'||url.username||url.password||url.port)return null;const match=/^\/uk\/realty-prodaja-(kvartira|dom|taunhaus|chast-doma)-[a-z0-9-]+-([1-9]\d{0,11})\.html$/.exec(url.pathname);if(!match||(match[1]==='kvartira'?'apartment':'house')!==kind)return null;url.search='';url.hash='';return{url:url.href,id:match[2],subtype:match[1]};}catch{return null;}}
function extractDomriaHtml(html,pageUrl,context={}){
 const location=publicCatalogLocation(pageUrl,'domria-web');if(!location)return{supported:false,records:[],reasons:[],notices:[]};
 const document=parsed(html);if(!document)return{supported:true,records:[],reasons:['HTML-страница превышает допустимый размер.'],notices:[]};
 const containers=walk(document).filter(node=>has(node,'search-result-list'));
 const cards=containers.flatMap(container=>walk(container).filter(node=>node.tagName==='section'&&has(node,'realty-item')&&visible(node))).slice(0,200);
 const records=new Map(),conflicts=new Set(),reasons=new Set(),notices=new Set();
 for(const card of cards){
  const links=walk(first(card,'tit')).filter(node=>node.tagName==='a'&&text(node)).map(node=>({node,detail:detail(attr(node,'href'),pageUrl,location.kind)})).filter(item=>item.detail);
  if(!links.length||new Set(links.map(item=>item.detail.url)).size!==1){reasons.add('Карточка DOM.RIA без однозначной ссылки продажи пропущена.');continue;}
  const source=links[0].detail;if(source.subtype==='chast-doma'){notices.add('Части домов DOM.RIA пропущены: их цена не является ценой целого дома.');continue;}
  const price=unambiguousPrice(walk(card).filter(node=>node.tagName==='b'&&attr(node,'data-tm')==='realty-full-price'&&visible(node)));
  if(!price){reasons.add('Нет однозначной видимой полной цены DOM.RIA; цена за м² не подставлена.');continue;}
  // The locality is a plain span. Nearby district/residential-complex anchors
  // share mb-5/i-block classes, so they must not be used as the city.
  const cities=walk(card).filter(node=>has(node,'mb-5')&&has(node,'i-block')&&visible(node)&&(attr(node,'data-level')==='city'||(node.tagName==='span'&&!attr(node,'data-level')&&!has(node,'u-line')))).map(node=>clean(text(node),120)).filter(Boolean);
  if(cities.length!==1){reasons.add('DOM.RIA не указал однозначный населённый пункт карточки.');continue;}
  const city=cities[0];let known;try{known=cityFor(city);}catch{}
  if(known?.id!==location.id||!new RegExp(`/realty-prodaja-${source.subtype}-zaporoje-`).test(new URL(source.url).pathname)){reasons.add('Населённый пункт DOM.RIA и проверенный городской URL не согласованы; карточка пропущена.');continue;}
  const address=clean(text(links[0].node));
  const record={source:'web:dom.ria.com',externalId:source.id,url:source.url,title:source.subtype==='taunhaus'?`Таунхаус · ${address}`:address||`${location.kind==='house'?'Будинок':'Квартира'} · ${city}`,address,city,region:known?.regionName||'',kind:location.kind,deal:'sale',period:'total',...price,sellerType:'unknown',observedAt:new Date(context.observedAt||Date.now()).toISOString(),status:'active'};
  const districts=walk(card).filter(node=>has(node,'mb-5')&&has(node,'i-block')&&(attr(node,'data-level')==='area'||/rayon-/.test(attr(node,'href')))).map(node=>clean(text(node),150)).filter(Boolean);if(districts.length)record.district=districts.join(', ');
  parameters(walk(card).filter(node=>has(node,'realty-char')).map(text),record);
  const badges=walk(card).filter(node=>has(node,'label-item')||has(node,'label-border')).map(text);
  if(badges.some(value=>/^(?:від власника|власник|от собственника|собственник)$/iu.test(value)))record.sellerType='owner';
  if(badges.some(value=>/^(?:забудовник|застройщик)$/iu.test(value)))record.sellerType='developer';
  addUnique(records,conflicts,record,reasons);
 }
 if(!cards.length)reasons.add('Открытые HTML-карточки каталога DOM.RIA не найдены.');
 notices.add('DOM.RIA: прочитаны открытые HTML-карточки без API-ключа; телефоны, скрытые данные и вход не используются.');
 return{supported:true,records:[...records.values()],reasons:[...reasons],notices:[...notices]};
}
module.exports={extractDomriaHtml};

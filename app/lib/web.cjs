'use strict';

const http = require('node:http');
const https = require('node:https');
const dns = require('node:dns').promises;
const net = require('node:net');
const crypto = require('node:crypto');

const USER_AGENT = 'DomTrackUA/1.0';
const MAX_BYTES = 2 * 1024 * 1024;
const TIMEOUT_MS = 15000;
const MIN_DELAY_MS = 2000;
const IPV4_DENIED = new net.BlockList();
for (const [address, prefix] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4]]) IPV4_DENIED.addSubnet(address, prefix, 'ipv4');
const IPV6_GLOBAL = new net.BlockList();
IPV6_GLOBAL.addSubnet('2000::', 3, 'ipv6');
const IPV6_DENIED = new net.BlockList();
for (const [address, prefix] of [['2001::', 23], ['2001:db8::', 32], ['2002::', 16], ['3fff::', 20]]) IPV6_DENIED.addSubnet(address, prefix, 'ipv6');

class WebScannerError extends Error {
  constructor(message, code = 'WEB_SCAN', statusCode = 400) { super(message); this.name = 'WebScannerError'; this.code = code; this.statusCode = statusCode; }
}
function isPublicAddress(address) {
  if (typeof address !== 'string' || address.includes('%')) return false;
  const family = net.isIP(address);
  if (family === 4) return !IPV4_DENIED.check(address, 'ipv4');
  return family === 6 && IPV6_GLOBAL.check(address, 'ipv6') && !IPV6_DENIED.check(address, 'ipv6');
}
function safeUrl(value) {
  if (typeof value !== 'string' || value.length > 2048 || /[\u0000-\u0020\\]/.test(value)) throw new WebScannerError('Нужен публичный URL HTTP или HTTPS.', 'WEB_URL');
  let url;
  try { url = new URL(value); } catch { throw new WebScannerError('Некорректный URL.', 'WEB_URL'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new WebScannerError('Допустимы только HTTP/HTTPS без логина и пароля в URL.', 'WEB_URL');
  if (url.port && !['80', '443'].includes(url.port)) throw new WebScannerError('Для публичного сканирования разрешены только порты 80 и 443.', 'WEB_URL');
  const hostname = url.hostname.replace(/^\[|\]$/g, '').toLowerCase().replace(/\.$/, '');
  if (!hostname || hostname === 'localhost' || /\.(?:localhost|local|internal|home|lan|invalid|test)$/.test(hostname)) throw new WebScannerError('Локальные и внутренние адреса запрещены.', 'WEB_SSRF');
  if (net.isIP(hostname) && !isPublicAddress(hostname)) throw new WebScannerError('Непубличные и зарезервированные IP-адреса запрещены.', 'WEB_SSRF');
  url.hash = '';
  return url;
}
function displayUrl(value) {
  try { const u = new URL(String(value)); return `${u.protocol}//${u.host}${u.pathname}`.slice(0, 300); }
  catch { return '(некорректный URL)'; }
}
function bounded(task, ms, message = 'Время ожидания сайта истекло.') {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new WebScannerError(message, 'WEB_TIMEOUT', 504)), ms); });
  return Promise.race([Promise.resolve().then(task), timeout]).finally(() => clearTimeout(timer));
}
async function resolvePublic(url, lookupImpl) {
  const hostname = url.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (net.isIP(hostname)) return { address: hostname, family: net.isIP(hostname) };
  let addresses;
  try { addresses = await bounded(() => lookupImpl(hostname, { all: true, verbatim: true }), TIMEOUT_MS, 'Не удалось вовремя проверить DNS сайта.'); }
  catch (error) { if (error instanceof WebScannerError) throw error; throw new WebScannerError('Не удалось проверить публичный DNS сайта.', 'WEB_DNS', 502); }
  if (!Array.isArray(addresses) || !addresses.length || addresses.some(item => !item || !isPublicAddress(item.address) || (item.family && Number(item.family) !== net.isIP(item.address)))) throw new WebScannerError('DNS сайта содержит непубличный или неподдерживаемый адрес.', 'WEB_SSRF');
  const first = addresses[0];
  return { address: first.address, family: net.isIP(first.address) };
}

/** Native transport pins the validated address, keeping the original Host/TLS name. */
function pinnedRequest(url, target) {
  return new Promise((resolve, reject) => {
    const transport = url.protocol === 'https:' ? https : http;
    let done = false;
    let request;
    const finish = (error, value) => { if (done) return; done = true; clearTimeout(timer); if (error) reject(error); else resolve(value); };
    const timer = setTimeout(() => { request?.destroy(); finish(new WebScannerError('Время ожидания сайта истекло.', 'WEB_TIMEOUT', 504)); }, TIMEOUT_MS);
    request = transport.request(url, {
      method: 'GET', agent: false, autoSelectFamily: false,
      headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,application/xhtml+xml,text/plain;q=0.9', 'Accept-Encoding': 'identity' },
      lookup: (_hostname, options, callback) => options?.all ? callback(null, [{ address: target.address, family: target.family }]) : callback(null, target.address, target.family)
    }, response => {
      const encoding = String(response.headers['content-encoding'] || 'identity').toLowerCase();
      if (encoding !== 'identity') { response.destroy(); finish(new WebScannerError('Сайт вернул сжатый ответ, который этот ограниченный сканер не обрабатывает.', 'WEB_ENCODING', 502)); return; }
      const declared = Number(response.headers['content-length']);
      if (Number.isFinite(declared) && declared > MAX_BYTES) { response.destroy(); finish(new WebScannerError('Страница превышает 2 МиБ.', 'WEB_SIZE', 502)); return; }
      const chunks = [];
      let bytes = 0;
      response.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > MAX_BYTES) { response.destroy(); finish(new WebScannerError('Страница превышает 2 МиБ.', 'WEB_SIZE', 502)); return; }
        chunks.push(chunk);
      });
      response.on('end', () => finish(null, { status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString('utf8') }));
      response.on('error', () => finish(new WebScannerError('Не удалось прочитать ответ сайта.', 'WEB_NETWORK', 502)));
    });
    request.on('error', () => finish(new WebScannerError('Не удалось подключиться к публичному сайту.', 'WEB_NETWORK', 502)));
    request.end();
  });
}
function header(response, name) {
  if (typeof response.headers?.get === 'function') return response.headers.get(name) || '';
  const value = response.headers?.[name.toLowerCase()] || response.headers?.[name] || '';
  return Array.isArray(value) ? value.join(', ') : String(value);
}
function unreserved(value) {
  return value.replace(/%([a-f\d]{2})/gi, (whole, hex) => { const character = String.fromCharCode(parseInt(hex, 16)); return /[a-z\d._~-]/i.test(character) ? character : `%${hex.toUpperCase()}`; });
}
function parseRobots(body) {
  const groups = [];
  let group = { agents: [], rules: [], crawlDelay: 0 };
  const push = () => { if (group.agents.length) groups.push(group); group = { agents: [], rules: [], crawlDelay: 0 }; };
  for (const rawLine of String(body).split(/\r?\n/)) {
    const line = rawLine.replace(/#.*$/, '').trim();
    if (!line) { if (group.rules.length || group.crawlDelay) push(); continue; }
    const colon = line.indexOf(':');
    if (colon < 0) continue;
    const key = line.slice(0, colon).trim().toLowerCase();
    const value = line.slice(colon + 1).trim();
    if (key === 'user-agent' && value) { if (group.rules.length || group.crawlDelay) push(); group.agents.push(value.toLowerCase()); }
    else if (group.agents.length && ['allow', 'disallow'].includes(key) && value) group.rules.push({ allow: key === 'allow', pattern: unreserved(value) });
    else if (group.agents.length && key === 'crawl-delay' && /^\d+(?:\.\d+)?$/.test(value)) group.crawlDelay = Number(value) * 1000;
  }
  push();
  const product = USER_AGENT.toLowerCase();
  let selected = groups.filter(g => g.agents.some(a => a !== '*' && product.startsWith(a)));
  if (selected.length) {
    const longest = Math.max(...selected.flatMap(g => g.agents.filter(a => a !== '*' && product.startsWith(a)).map(a => a.length)));
    selected = selected.filter(g => g.agents.some(a => a.length === longest && product.startsWith(a)));
  } else selected = groups.filter(g => g.agents.includes('*'));
  return { rules: selected.flatMap(g => g.rules), crawlDelay: Math.max(0, ...selected.map(g => g.crawlDelay)) };
}
function robotsAllows(policy, url) {
  const path = unreserved(url.pathname + url.search);
  let winner = null;
  for (const rule of policy.rules) {
    const end = rule.pattern.endsWith('$');
    const plain = end ? rule.pattern.slice(0, -1) : rule.pattern;
    const parts = plain.split('*');
    let matches = path.startsWith(parts[0]);
    let cursor = parts[0].length;
    if (matches && parts.length === 1) matches = !end || path === plain;
    if (matches && parts.length > 1) {
      for (let i = 1; i < parts.length - 1; i++) {
        const position = path.indexOf(parts[i], cursor);
        if (position < 0) { matches = false; break; }
        cursor = position + parts[i].length;
      }
      const last = parts[parts.length - 1];
      if (matches) matches = end ? path.endsWith(last) && path.length - last.length >= cursor : path.indexOf(last, cursor) >= 0;
    }
    const length = plain.replace(/\*/g, '').length;
    if (matches && (!winner || length > winner.length || (length === winner.length && rule.allow))) winner = { allow: rule.allow, length };
  }
  return !winner || winner.allow;
}
function decodeEntities(value) {
  return String(value).replace(/&(?:amp|quot|apos|lt|gt|nbsp|#\d+|#x[a-f\d]+);/gi, token => {
    const simple = { '&amp;': '&', '&quot;': '"', '&apos;': "'", '&lt;': '<', '&gt;': '>', '&nbsp;': ' ' };
    if (simple[token.toLowerCase()] !== undefined) return simple[token.toLowerCase()];
    const hex = /^&#x/i.test(token);
    const code = parseInt(token.slice(hex ? 3 : 2, -1), hex ? 16 : 10);
    return Number.isInteger(code) && code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : '';
  });
}
function clean(value, max = 180) { return typeof value === 'string' || typeof value === 'number' ? decodeEntities(String(value).slice(0, max * 4)).replace(/<[^>]*>/g, ' ').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max) : ''; }
function number(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value.trim())) return null;
  const result = Number(value.trim()); return Number.isFinite(result) ? result : null;
}
function types(value) { return (Array.isArray(value?.['@type']) ? value['@type'] : [value?.['@type']]).filter(item => typeof item === 'string').map(item => item.split(/[\/#]/).pop()); }
function attribute(attributes, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|\\s)${escaped}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i').exec(attributes);
  return match ? decodeEntities(match[1] ?? match[2] ?? match[3]) : '';
}
function parseJsonLd(html) {
  const values = []; let invalid = 0;
  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
    if (!/^application\/ld\+json(?:\s*;|$)/i.test(attribute(match[1], 'type').trim())) continue;
    try { values.push(JSON.parse(match[2].replace(/^\s*<!--|-->\s*$/g, '').trim())); }
    catch { invalid++; }
  }
  return { values, invalid };
}
function allNodes(values) {
  const nodes = []; const visited = new WeakSet();
  const visit = (value, depth) => {
    if (!value || typeof value !== 'object' || depth > 20 || nodes.length >= 3000 || visited.has(value)) return;
    visited.add(value);
    if (Array.isArray(value)) { for (const item of value) visit(item, depth + 1); return; }
    nodes.push(value);
    for (const child of Object.values(value)) visit(child, depth + 1);
  };
  for (const value of values) visit(value, 0);
  return nodes;
}
function named(value) { return typeof value === 'string' ? value : typeof value?.name === 'string' ? value.name : ''; }
function explicitKind(node) {
  const all = [...types(node), ...(Array.isArray(node?.additionalType) ? node.additionalType : [node?.additionalType]).filter(x => typeof x === 'string').map(x => x.split(/[\/#]/).pop())];
  return all.includes('Apartment') ? 'apartment' : all.some(t => ['House', 'SingleFamilyResidence'].includes(t)) ? 'house' : null;
}
function sourceSellerType(seller) {
  const all = types(seller);
  if (all.some(t => ['RealEstateAgent', 'RealEstateAgency'].includes(t))) return 'agent';
  if (all.some(t => ['PropertyDeveloper', 'RealEstateDeveloper'].includes(t))) return 'developer';
  if (all.some(t => ['PropertyOwner', 'Owner'].includes(t))) return 'owner';
  return 'unknown';
}
function publicPhone(value) {
  if (typeof value !== 'string' || /[\r\n]/.test(value)) return null;
  const phone = value.replace(/^tel:/i, '').trim();
  const digits = phone.replace(/\D/g, '');
  return phone.length >= 5 && phone.length <= 40 && /^\+?[\d ()-]+$/.test(phone) && digits.length >= 5 && digits.length <= 20 ? phone : null;
}
function publicEmail(value) {
  if (typeof value !== 'string' || /[\r\n]/.test(value)) return null;
  const email = value.replace(/^mailto:/i, '').trim();
  if (email.length > 254 || !/^[a-z\d._%+-]+@[a-z\d](?:[a-z\d-]*[a-z\d])?(?:\.[a-z\d](?:[a-z\d-]*[a-z\d])?)+$/i.test(email)) return null;
  return email;
}
function publicName(value) {
  return clean(value, 150).replace(/[<>]/g, '').trim() || null;
}
function publicContacts(node, property, offer, page, resolve) {
  const flatten = value => (Array.isArray(value) ? value : [value]).slice(0, 20).map(resolve).filter(item => item && typeof item === 'object' && !Array.isArray(item));
  const sellers = [offer.seller, node.seller, property.seller, node.broker].flatMap(flatten);
  const points = [node.contactPoint, property.contactPoint, offer.contactPoint, ...sellers.map(seller => seller.contactPoint)].flatMap(flatten);
  const sources = [...sellers, ...points, property, node];
  const choose = (field, validate) => {
    for (const source of sources) for (const value of (Array.isArray(source[field]) ? source[field] : [source[field]]).slice(0, 20)) {
      const valid = validate(value);
      if (valid) return valid;
    }
    return null;
  };
  const contactPhone = choose('telephone', publicPhone);
  const contactEmail = choose('email', publicEmail);
  let contactUrl = null;
  for (const source of [...sellers, ...points]) {
    const value = typeof source.url === 'object' ? source.url?.['@id'] : source.url;
    if (typeof value !== 'string' || value.startsWith('#')) continue;
    try { contactUrl = safeUrl(new URL(value, page).href).href; break; } catch { /* Contact URLs never bypass URL validation. */ }
  }
  let sellerName = null;
  for (const seller of sellers) {
    sellerName = publicName(seller.name) || publicName([seller.givenName, seller.familyName].filter(part => typeof part === 'string').join(' '));
    if (sellerName) break;
  }
  return { ...(contactPhone ? { contactPhone } : {}), ...(contactEmail ? { contactEmail } : {}), ...(contactUrl ? { contactUrl } : {}), ...(sellerName ? { sellerName } : {}) };
}
function publishedIdentifier(value) {
  const raw = typeof value === 'object' && value !== null ? value.value : value;
  if (!['string', 'number'].includes(typeof raw) || (typeof raw === 'number' && !Number.isFinite(raw))) return '';
  const id = String(raw).trim();
  return id.length > 0 && id.length <= 160 && clean(id, 160) === id ? id : '';
}
function publishedGraphId(value, page) {
  if (typeof value !== 'string' || !value.trim() || value.length > 2048 || value.startsWith('_:') || /[\u0000-\u0020<>]/.test(value)) return '';
  try { return new URL(value, page).href; } catch { return ''; }
}
function extractJsonLdRecords(html, pageUrl, context = {}) {
  const parsed = parseJsonLd(html);
  const nodes = allNodes(parsed.values);
  const byId = new Map();
  for (const node of nodes) if (typeof node['@id'] === 'string' && (!byId.has(node['@id']) || Object.keys(node).length > Object.keys(byId.get(node['@id'])).length)) byId.set(node['@id'], node);
  const resolve = value => value && typeof value === 'object' && typeof value['@id'] === 'string' ? byId.get(value['@id']) || value : value;
  const page = safeUrl(pageUrl);
  const titleMatch = /<title\b[^>]*>([\s\S]*?)<\/title\s*>/i.exec(html);
  const pageTitle = clean(titleMatch?.[1] || '');
  const records = []; const seen = new Set(); const reasons = new Set();
  const entityInfo = new Map();
  for (const node of nodes) {
    const nodeTypes = types(node);
    if (!nodeTypes.some(t => ['Apartment', 'House', 'SingleFamilyResidence', 'RealEstateListing'].includes(t))) continue;
    const property = nodeTypes.includes('RealEstateListing') ? resolve(node.mainEntity || node.about || node.itemOffered) || node : node;
    if (!explicitKind(property) && !explicitKind(node)) continue;
    const entity = resolve(property);
    const info = entityInfo.get(entity) || { ownUrl: '' };
    const offers = resolve(node.offers || property.offers);
    const offer = resolve(Array.isArray(offers) ? offers.find(o => number(resolve(o)?.price) !== null) : offers);
    if (!info.ownUrl) for (const candidate of [node.url, property.url, offer?.url]) {
      if (typeof candidate !== 'string') continue;
      try { const validated = safeUrl(new URL(candidate, page).href); if (validated.origin === page.origin) { info.ownUrl = validated.href; break; } } catch { /* An invalid URL cannot prove a listing's identity. */ }
    }
    entityInfo.set(entity, info);
  }
  const ownUrlCounts = new Map();
  for (const info of entityInfo.values()) if (info.ownUrl) ownUrlCounts.set(info.ownUrl, (ownUrlCounts.get(info.ownUrl) || 0) + 1);
  let contextLocations = 0;
  for (const node of nodes) {
    const nodeTypes = types(node);
    if (!nodeTypes.some(t => ['Apartment', 'House', 'SingleFamilyResidence', 'RealEstateListing'].includes(t))) continue;
    const property = nodeTypes.includes('RealEstateListing') ? resolve(node.mainEntity || node.about || node.itemOffered) || node : node;
    const kind = explicitKind(property) || explicitKind(node);
    if (!kind) { reasons.add('В JSON-LD не указан тип жилья.'); continue; }
    const offers = resolve(node.offers || property.offers);
    const offer = resolve(Array.isArray(offers) ? offers.find(o => number(resolve(o)?.price) !== null) : offers);
    if (!offer || typeof offer !== 'object' || types(offer).includes('AggregateOffer')) { reasons.add('Нет индивидуальной цены объекта в offers.'); continue; }
    const specification = Array.isArray(offer.priceSpecification) ? offer.priceSpecification : [offer.priceSpecification];
    if (specification.some(spec => spec && (spec.unitCode || spec.unitText || spec.referenceQuantity || spec.billingDuration || spec.billingIncrement || types(spec).includes('UnitPriceSpecification')))) { reasons.add('Цена за единицу или период не принята как полная цена продажи.'); continue; }
    const saleText = clean([node.name, property.name, node.category, property.category, offer.category, pageTitle].filter(x => typeof x === 'string').join(' '), 1000);
    if (/(?:\/|\bper\b|\bза\b)\s*(?:m[²2]|м[²2]|кв\.?\s*м|square\s*m)/i.test(saleText)) { reasons.add('Цена за квадратный метр не принята как цена всего объекта.'); continue; }
    const business = clean(offer.businessFunction || node.businessFunction, 200).split(/[\/#]/).pop();
    if (/Lease|Rent/i.test(business) || /(?:\brent\b|\brental\b|аренд|оренд|посуточ|подобов)/i.test(saleText) || property.leaseLength || node.leaseLength || offer.leaseLength) { reasons.add('Аренда не включается в продажи.'); continue; }
    if (!/^(?:Sell|ForSale)$/i.test(business) && !/(?:\bfor sale\b|\bsale\b|продаж|прода[еє]т|продам)/i.test(saleText)) { reasons.add('Вид сделки не подтверждён как продажа.'); continue; }
    const price = number(offer.price);
    const rawCurrency = clean(offer.priceCurrency, 8).toUpperCase();
    if (price === null || price <= 0 || !['USD', 'UAH', 'EUR'].includes(rawCurrency)) { reasons.add('Нет исходной полной цены в USD, UAH или EUR.'); continue; }
    const address = resolve(property.address || node.address) || {};
    const countryValue = typeof address.addressCountry === 'object' ? address.addressCountry?.name || address.addressCountry?.identifier || address.addressCountry?.['@id'] : address.addressCountry;
    const country = clean(countryValue, 100).split(/[\/#]/).pop().toLowerCase();
    if (country && !['ua', 'ukr', 'ukraine', 'україна', 'украина'].includes(country)) { reasons.add('Адрес объекта находится вне Украины.'); continue; }
    const sourceCity = clean(named(address.addressLocality), 120);
    const sourceRegion = clean(named(address.addressRegion), 120);
    const city = sourceCity || clean(context.cityName, 120);
    const region = sourceRegion || clean(context.regionName, 120);
    if (!city) { reasons.add('Город не указан в данных и настройках.'); continue; }
    if (!sourceCity || (!sourceRegion && region)) contextLocations++;
    const identityInfo = entityInfo.get(resolve(property)) || { ownUrl: '' };
    const url = identityInfo.ownUrl || page.href;
    const title = clean(node.name || property.name || pageTitle, 180) || `${kind === 'house' ? 'Будинок' : 'Квартира'} · ${city}`;
    const identifier = publishedIdentifier(property.identifier || node.identifier);
    const graphId = publishedGraphId(property['@id'] || node['@id'], page);
    if (entityInfo.size > 1 && !identifier && !graphId && (!identityInfo.ownUrl || ownUrlCounts.get(identityInfo.ownUrl) !== 1)) {
      reasons.add('В каталоге несколько объектов без собственных уникальных URL или устойчивых ID. Их цены не объединены и не импортированы.');
      continue;
    }
    const streetAddress = clean(typeof address === 'string' ? address : address.streetAddress, 200);
    const stableIdentity = identifier || crypto.createHash('sha256').update(JSON.stringify([url, graphId ? property['@id'] || node['@id'] || '' : '', city, streetAddress, kind])).digest('hex').slice(0, 24);
    if (seen.has(stableIdentity)) continue;
    seen.add(stableIdentity);
    const availability = clean(offer.availability, 100).split(/[\/#]/).pop();
    const record = { source: `web:${page.hostname}`, externalId: stableIdentity, url, title, city, region, kind, deal: 'sale', period: 'total', price, currency: rawCurrency, sellerType: sourceSellerType(resolve(offer.seller || node.seller || property.seller || node.broker)), observedAt: new Date(context.observedAt || Date.now()).toISOString(), status: ['SoldOut', 'OutOfStock', 'Discontinued'].includes(availability) ? 'inactive' : 'active', ...publicContacts(node, property, offer, page, resolve) };
    if (streetAddress) record.address = streetAddress;
    const floorSize = resolve(property.floorSize);
    const area = number(floorSize?.value);
    if (area > 0 && ['MTK', 'M2', 'M²', 'SQM', 'М²', 'КВ.М'].includes(clean(floorSize?.unitCode || floorSize?.unitText, 20).toUpperCase())) record.area = area;
    for (const [field, target] of [['numberOfRooms', 'rooms'], ['floorLevel', 'floor'], ['numberOfFloors', 'floorsTotal']]) {
      const value = number(typeof property[field] === 'object' ? property[field]?.value : property[field]);
      if (Number.isSafeInteger(value) && value >= 0 && value <= 500) record[target] = value;
    }
    records.push(record);
    if (records.length >= 200) { reasons.add('Достигнут предел 200 объектов на страницу.'); break; }
  }
  const hasHousingJsonLd=nodes.some(node=>types(node).some(type=>['Apartment','House','SingleFamilyResidence','RealEstateListing'].includes(type)));
  return { records, contextLocations, reasons: [...reasons], hasJsonLd: parsed.values.length > 0, hasHousingJsonLd, invalidJsonLd: parsed.invalid };
}

function createWebScanner(options = {}) {
  if (options.fetchImpl && !options.requestImpl) throw new WebScannerError('Обычный fetch не обеспечивает закрепление DNS. Для тестов используйте requestImpl.', 'WEB_TRANSPORT');
  const requestImpl = options.requestImpl || pinnedRequest;
  const lookupImpl = options.lookupImpl || dns.lookup.bind(dns);
  const sleep = options.sleepImpl || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const now = options.now || Date.now;
  const hostTimes = new Map();
  let scanRunning = false;
  async function load(url, crawlDelay = 0) {
    const target = await resolvePublic(url, lookupImpl);
    const hostname = url.hostname;
    const delay = Math.max(MIN_DELAY_MS, crawlDelay);
    const previous = hostTimes.get(hostname);
    if (previous !== undefined) {
      const wait = Math.max(0, delay - (now() - previous));
      if (wait) await sleep(wait);
    }
    hostTimes.set(hostname, now());
    let response;
    try { response = await bounded(() => requestImpl(url, { ...target, userAgent: USER_AGENT, timeoutMs: TIMEOUT_MS, maxBytes: MAX_BYTES }), TIMEOUT_MS); }
    catch (error) { if (error instanceof WebScannerError) throw error; throw new WebScannerError('Не удалось получить публичную страницу сайта.', 'WEB_NETWORK', 502); }
    if (!response || !Number.isInteger(response.status) || typeof response.body !== 'string') throw new WebScannerError('Транспорт вернул некорректный ответ.', 'WEB_RESPONSE', 502);
    if (Buffer.byteLength(response.body, 'utf8') > MAX_BYTES) throw new WebScannerError('Страница превышает 2 МиБ.', 'WEB_SIZE', 502);
    if (response.status >= 300 && response.status < 400) throw new WebScannerError('Перенаправление не выполнено: используйте конечный публичный URL сайта.', 'WEB_REDIRECT', 502);
    return response;
  }
  async function scan(spec = {}) {
    if (scanRunning) throw new WebScannerError('Сканирование уже выполняется.', 'WEB_BUSY', 409);
    if (!Array.isArray(spec.urls) || spec.urls.length < 1 || spec.urls.length > 5) throw new WebScannerError('Укажите от 1 до 5 стартовых URL.', 'WEB_SETTINGS');
    const maxPages = spec.maxPages === undefined ? 10 : Number(spec.maxPages);
    if (!Number.isInteger(maxPages) || maxPages < 1 || maxPages > 20) throw new WebScannerError('За один запуск можно проверить от 1 до 20 страниц.', 'WEB_SETTINGS');
    scanRunning = true;
    const result = { records: [], pagesVisited: 0, pagesAttempted: 0, urlsDiscovered: 0, errors: [], unsupportedPages: [], pageResults: [], scope: 'configured-seeds', notices: [] };
    try {
      const queue = []; const known = new Set(); const allowedOrigins = new Set(); const robots = new Map(); const recordKeys = new Set();
      const notice = message => { if (!result.notices.includes(message)) result.notices.push(message); };
      for (const [seedIndex,seed] of spec.urls.entries()) {
        try { const url = safeUrl(seed); if (!known.has(url.href)) { queue.push({ url, depth: 0, seedIndex }); known.add(url.href); allowedOrigins.add(url.origin); } }
        catch (error) { const message = error instanceof WebScannerError ? error.message : 'Некорректный стартовый URL.'; result.errors.push({ url: displayUrl(seed), message }); result.pageResults.push({ url: displayUrl(seed), seedIndex, status: 'error', recordsFound: 0, message }); }
      }
      async function policyFor(url) {
        if (robots.has(url.origin)) return robots.get(url.origin);
        const response = await load(new URL('/robots.txt', url.origin));
        let policy;
        if (response.status === 404) policy = { rules: [], crawlDelay: 0 };
        else if (response.status === 200 && !/text\/html/i.test(header(response, 'content-type')) && !/^\s*(?:<!doctype|<html)/i.test(response.body)) policy = parseRobots(response.body);
        else throw new WebScannerError('robots.txt недоступен или запрещает надёжную проверку; сайт пропущен.', 'WEB_ROBOTS');
        if (policy.crawlDelay > 60000) throw new WebScannerError('robots.txt требует слишком большой интервал; сайт пропущен.', 'WEB_ROBOTS');
        robots.set(url.origin, policy);
        return policy;
      }
      while (queue.length && result.pagesAttempted < maxPages) {
        const item = queue.shift();
        if (!allowedOrigins.has(item.url.origin)) continue;
        result.pagesAttempted++;
        let httpStatus = null;
        try {
          const policy = await policyFor(item.url);
          if (!robotsAllows(policy, item.url)) throw new WebScannerError('Доступ к странице запрещён robots.txt.', 'WEB_ROBOTS');
          const response = await load(item.url, policy.crawlDelay);
          httpStatus = response.status;
          result.pagesVisited++;
          if (response.status < 200 || response.status >= 300) throw new WebScannerError(`Сайт вернул HTTP ${response.status}.`, 'WEB_HTTP', 502);
          const contentType = header(response, 'content-type');
          if ((contentType && !/(?:text\/html|application\/xhtml\+xml)/i.test(contentType)) || !response.body.trim()) {
            result.unsupportedPages.push({ url: item.url.href, reason: 'Страница не является открытым HTML-документом.' });
            result.pageResults.push({ url: displayUrl(item.url.href), seedIndex:item.seedIndex, status: 'unsupported', recordsFound: 0, httpStatus, message: 'Страница не является открытым HTML-документом.' });
            continue;
          }
          const context={cityName:spec.cityName,regionName:spec.regionName,observedAt:new Date(now()).toISOString()};
          let extracted = extractJsonLdRecords(response.body, item.url.href, context);
          const publicAdapter=item.url.hostname==='dom.ria.com'?require('./domria-html.cjs').extractDomriaHtml:item.url.hostname==='obyava.ua'?require('./obyava-html.cjs').extractObyavaHtml:null;
          const publicResult=publicAdapter?.(response.body,item.url.href,context);
          if(publicResult?.supported){
            extracted={...extracted,records:publicResult.records,contextLocations:0,reasons:publicResult.reasons};
            for(const message of [...publicResult.notices,...publicResult.reasons])notice(message);
          }else if(!extracted.records.length&&!extracted.hasHousingJsonLd){
            const fallback=require('./rieltor-html.cjs').extractRieltorHtml(response.body,item.url.href,context);
            if(fallback.supported){
              if(fallback.records.length){extracted={...extracted,records:fallback.records,contextLocations:0};notice('Карточки Rieltor без JSON-LD прочитаны из видимого HTML; скрытые телефоны и пересчитанные цены не используются.');}
              else extracted.reasons.push(...fallback.reasons);
              for(const message of fallback.notices)notice(message);
            }
          }
          result.pageResults.push({ url: displayUrl(item.url.href), seedIndex:item.seedIndex, status: extracted.records.length ? 'ok' : 'unsupported', recordsFound: extracted.records.length, httpStatus });
          for (const record of extracted.records) {
            const key = `${record.source}:${record.externalId}`;
            if (recordKeys.has(key)) continue;
            recordKeys.add(key); result.records.push(record);
          }
          if (extracted.contextLocations) notice('Для части объектов город или область взяты из ваших настроек: источник не указал их в JSON-LD.');
          if (!extracted.records.length) result.unsupportedPages.push({ url: item.url.href, reason: extracted.reasons.join(' ') || (extracted.invalidJsonLd ? 'JSON-LD повреждён.' : extracted.hasJsonLd ? 'Нет подходящих объявлений жилья с полной ценой продажи.' : 'Нет поддерживаемой JSON-LD-разметки. JavaScript, вход и CAPTCHA не обходятся.') });
          if (spec.followLinks === true && item.depth < 2 && result.urlsDiscovered < 100) {
            for (const match of response.body.matchAll(/<a\b([^>]*)>/gi)) {
              const href = attribute(match[1], 'href');
              if (!href || href.startsWith('#')) continue;
              try {
                const next = safeUrl(new URL(href, item.url).href);
                if (next.origin !== item.url.origin || !allowedOrigins.has(next.origin) || known.has(next.href) || /\.(?:jpe?g|png|gif|webp|svg|pdf|zip|mp4|mp3|css|js)(?:$|\?)/i.test(next.pathname)) continue;
                known.add(next.href); result.urlsDiscovered++;
                queue.push({ url: next, depth: item.depth + 1 });
                if (result.urlsDiscovered >= 100) break;
              } catch { /* Invalid or nonpublic discovered URLs are never requested. */ }
            }
          }
        } catch (error) { const message = error instanceof WebScannerError ? error.message : 'Страница не обработана.'; result.errors.push({ url: displayUrl(item.url.href), message }); result.pageResults.push({ url: displayUrl(item.url.href), seedIndex:item.seedIndex, status: 'error', recordsFound: 0, httpStatus, message }); }
      }
      if (queue.length) notice('Достигнут лимит страниц или ссылок. Результат относится только к проверенным страницам заданных сайтов.');
      notice('Сканирование охватывает только заданные сайты с подходящей открытой разметкой и не гарантирует полноту объявлений или отсутствие дублей.');
      return result;
    } finally { scanRunning = false; }
  }
  return { scan };
}

module.exports = { createWebScanner, WebScannerError, isPublicAddress, safeUrl, parseRobots, robotsAllows, extractJsonLdRecords };

'use strict';

const { createHash } = require('node:crypto');

const normalize = (value) => typeof value === 'string' ? value.normalize('NFKC').toLocaleLowerCase('uk').replace(/[’‘ʼ`´]/gu, "'").replace(/[‐‑‒–—]/gu, '-').replace(/\s+/gu, ' ').trim() : '';
const words = (value) => normalize(value).match(/[\p{L}]+(?:'[\p{L}]+)?/gu) || [];
const finite = (value) => typeof value === 'number' && Number.isFinite(value);
const knownInteger = (value) => finite(value) && Number.isInteger(value);
const STOP = new Set(('продаж продажа продам продається продается продаю купити купить квартира квартири квартиру квартир квартиры будинок будинку будинки дом дома кімн кімната кімнати кімнат кімнатний кімнатна кімнатна кімнатної комната комнаты комнат комнатная комнатный м² кв метр метри метров площа площадь загальна общая загальної общей житлова жилая житловий жилой нова новий новая новый світла светлая простора просторная затишна уютная чудова отличная ремонт ремонтом ремонта стан состояние від от без для біля возле поруч рядом з із зі с со на та и й в у до або або жк поверх этаж поверхів этажей поверховість этажность ціна цена власник собственник агент ріелтор риелтор застройщик забудовник').split(' '));
const EXTRA_FIELDS = new Set(['duplicateCount', 'duplicateGroupId', 'duplicateAlternatives', 'duplicateConfidence', 'duplicateReasons', 'comparisonCurrency', 'comparisonPrice', 'comparisonAsOf']);
const MAX_PAIR_CHECKS = 250000;

function cleanItem(item) {
  return Object.fromEntries(Object.entries(item).filter(([name]) => !EXTRA_FIELDS.has(name)));
}

function canonicalUrl(value, preserveFragment = false) {
  if (typeof value !== 'string' || !value) return null;
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
    if (!preserveFragment) url.hash = '';
    for (const name of [...url.searchParams.keys()]) if (/^utm_/iu.test(name) || /^(?:gclid|fbclid|msclkid|yclid|_ga)$/iu.test(name)) url.searchParams.delete(name);
    url.searchParams.sort();
    return url.href;
  } catch { return null; }
}

/** Only explicit confirmation or matching original-ad provenance can justify hiding an offer. */
function isConfirmedGroup(group) {
  if (!group || typeof group !== 'object' || !Array.isArray(group.items) || group.items.length < 2) return false;
  if (group.confidence === 'user-confirmed') return true;
  // Preserve hash routes as well as business query parameters when proving identity.
  const urls = group.items.map((item) => canonicalUrl(item?.url, true));
  if (urls[0] === null || !urls.every((url) => url === urls[0])) return false;
  const ids = group.items.map((item) => item?.externalId === undefined || item.externalId === null ? null : typeof item.externalId === 'string' && item.externalId.trim() ? item.externalId.trim() : false);
  return ids[0] !== false && ids.every((id) => id === ids[0]);
}

function houseNumber(value) {
  return normalize(String(value)).replace(/\s+/gu, '').replace(/-(?=[a-zа-яіїєґ]$)/iu, '');
}

/** Require a street name and a recognisable building number; a street alone is insufficient. */
function addressEvidence(item) {
  let text = normalize(item.address);
  if (!text || text.length > 2000) return null;
  let apartmentNo = null;
  let inconsistent = false;
  const explicit = item.apartmentNo ?? item.apartmentNumber ?? item.flatNumber;
  if ((typeof explicit === 'string' || typeof explicit === 'number') && /^\d+[a-zа-яіїєґ]?$/iu.test(String(explicit).trim())) apartmentNo = houseNumber(explicit);
  const apartmentPattern = /(?:^|[\s,;])(?:кв(?:артира)?|квартира|apt|apartment)\.?\s*(?:№\s*)?(\d+[a-zа-яіїєґ]?)(?=$|[\s,;])/giu;
  text = text.replace(apartmentPattern, (_, number) => {
    const found = houseNumber(number);
    if (apartmentNo !== null && apartmentNo !== found) inconsistent = true;
    apartmentNo = found;
    return ' ';
  });
  if (inconsistent) return null;
  let corpus = '';
  text = text.replace(/(?:^|[\s,;])(?:корпус|корп|блок|block)\.?\s*([\da-zа-яіїєґ]+)(?=$|[\s,;])/giu, (_, value) => { corpus = houseNumber(value); return ' '; });
  text = text.replace(/[\s,;]+$/gu, '');
  let number = null;
  const marked = /(?:^|[\s,;])(?:будинок|буд|дом|д|house|bldg)\.?\s*(?:№\s*)?(\d+[a-zа-яіїєґ]?(?:[-/]\d+[a-zа-яіїєґ]?|-[a-zа-яіїєґ])?)(?=$|[\s,;])/iu.exec(text);
  if (marked) {
    number = houseNumber(marked[1]);
    text = text.slice(0, marked.index) + ' ' + text.slice(marked.index + marked[0].length);
  } else {
    const ending = /(?:^|[\s,;])(?:№\s*)?(\d+[a-zа-яіїєґ]?(?:[-/]\d+[a-zа-яіїєґ]?|-[a-zа-яіїєґ])?)$/iu.exec(text);
    if (!ending) return null;
    number = houseNumber(ending[1]);
    text = text.slice(0, ending.index);
    // An unlabelled final pair of numbers could be building+flat. Do not guess.
    if (/(?:^|[\s,;])\d+[a-zа-яіїєґ]?\s*[,;]?$/iu.test(text)) return null;
  }
  const street = normalize(text)
    .replace(/(?:^|[\s,;])(?:вулиця|вул|улица|ул|street|str)\.?\s*/giu, ' street ')
    .replace(/(?:^|[\s,;])(?:проспект|просп|пр-т|avenue)\.?\s*/giu, ' avenue ')
    .replace(/(?:^|[\s,;])(?:провулок|пров|переулок|пер|lane)\.?\s*/giu, ' lane ')
    .replace(/(?:^|[\s,;])(?:бульвар|бул|б-р|boulevard)\.?\s*/giu, ' boulevard ')
    .replace(/(?:^|[\s,;])(?:площа|площадь|пл|square)\.?\s*/giu, ' square ')
    .replace(/[^\p{L}\p{N}'-]+/gu, ' ').trim().replace(/\s+/gu, ' ');
  const streetWords = words(street).filter((word) => !['street', 'avenue', 'lane', 'boulevard', 'square'].includes(word));
  if (!number || streetWords.length === 0) return null;
  return { key: JSON.stringify([street, number, corpus]), apartmentNo, words: new Set(words(street)) };
}

function titleEvidence(item, address) {
  const ignored = new Set([...STOP, ...words(item.city), ...words(item.region), ...address.words]);
  return new Set(words(item.title).filter((word) => word.length >= 4 && !ignored.has(word)));
}

function strongTitle(a, b) {
  if (a.size < 4 || b.size < 4) return false;
  const common = [...a].filter((word) => b.has(word)).length;
  return common >= 4 && common / (a.size + b.size - common) >= 0.85;
}

function validDate(value) {
  if (typeof value !== 'string' || value.length > 40 || !/^\d{4}-\d{2}-\d{2}(?:T.*)?$/u.test(value)) return false;
  const [year, month, day] = value.slice(0, 10).split('-').map(Number);
  return year >= 1900 && month >= 1 && month <= 12 && day >= 1 && day <= new Date(Date.UTC(year, month, 0)).getUTCDate() && Number.isFinite(Date.parse(value));
}

function error(message) { const result = new Error(message); result.statusCode = 400; return result; }

function rateOptions(rates, asOf) {
  if (rates === undefined || rates === null) return null;
  if (typeof rates !== 'object' || Array.isArray(rates)) throw error('Курсы валют должны быть объектом.');
  if (!validDate(asOf)) throw error('Для пересчёта цен укажите дату курсов asOf.');
  const result = { UAH: 1 };
  for (const currency of ['UAH', 'USD', 'EUR']) {
    if (!Object.hasOwn(rates, currency)) continue;
    const value = rates[currency];
    if (!finite(value) || value <= 0 || value > 1e9 || currency === 'UAH' && value !== 1) throw error(`Некорректный курс ${currency}.`);
    result[currency] = value;
  }
  return { rates: result, asOf };
}

function pairKey(left, right) {
  if (!Number.isSafeInteger(left) || left <= 0 || !Number.isSafeInteger(right) || right <= 0 || left === right) return null;
  return left < right ? `${left}:${right}` : `${right}:${left}`;
}

function ruleOptions(sameGroups, differentPairs) {
  const same = new Set(); const different = new Set(); const neighbours = new Map();
  const validate = (values, minimum, maximum) => {
    if (!Array.isArray(values) || values.length < minimum || values.length > maximum || values.some((value) => !Number.isSafeInteger(value) || value <= 0) || new Set(values).size !== values.length) throw error('Недопустимые ID в ручном правиле дублей.');
    return values;
  };
  if (!Array.isArray(sameGroups) || sameGroups.length > 100 || !Array.isArray(differentPairs) || differentPairs.length > 1000) throw error('Слишком много ручных правил дублей.');
  for (const pair of differentPairs) { validate(pair, 2, 2); different.add(pairKey(pair[0], pair[1])); }
  for (const group of sameGroups) {
    validate(group, 2, 20);
    for (let left = 0; left < group.length; left++) for (let right = left + 1; right < group.length; right++) {
      same.add(pairKey(group[left], group[right]));
      for (const [from, to] of [[group[left], group[right]], [group[right], group[left]]]) {
        if (!neighbours.has(from)) neighbours.set(from, new Set());
        neighbours.get(from).add(to);
      }
    }
  }
  return { same, different, neighbours };
}

function pairEvidence(a, b, rules) {
  const left = a.item; const right = b.item;
  const key = pairKey(left.id, right.id);
  if (key !== null && rules.different.has(key)) return null;
  if (a.scope === null || a.scope !== b.scope) return null;
  if (key !== null && rules.same.has(key)) return ['Пользователь подтвердил один объект'];
  if (a.bucket !== b.bucket || !a.address || !b.address || a.address.key !== b.address.key) return null;
  if (!knownInteger(left.rooms) || !knownInteger(right.rooms) || left.rooms !== right.rooms) return null;
  if (left.kind === 'apartment' && (!knownInteger(left.floor) || !knownInteger(right.floor) || left.floor !== right.floor)) return null;
  if (knownInteger(left.floor) && knownInteger(right.floor) && left.floor !== right.floor) return null;
  if (knownInteger(left.floorsTotal) && knownInteger(right.floorsTotal) && left.floorsTotal !== right.floorsTotal) return null;
  if (a.address.apartmentNo !== null && b.address.apartmentNo !== null && a.address.apartmentNo !== b.address.apartmentNo) return null;
  if (!finite(left.area) || !finite(right.area) || left.area <= 0 || right.area <= 0) return null;
  if (Math.abs(left.area - right.area) > Math.max(0.5, Math.min(left.area, right.area) * 0.005) + 1e-8) return null;
  const sameApartment = a.address.apartmentNo !== null && a.address.apartmentNo === b.address.apartmentNo;
  const sameUrl = a.url !== null && a.url === b.url;
  const sameTitle = strongTitle(a.titleWords, b.titleWords);
  if (!sameApartment && !sameUrl && !sameTitle) return null;
  return [
    'Совпадают город, область и тип жилья', 'Совпадает адрес с номером дома',
    'Совпадает количество комнат', ...(left.kind === 'apartment' ? ['Совпадает этаж'] : []),
    'Площадь отличается не более чем на 0,5 м² или 0,5%',
    ...(sameApartment ? ['Совпадает номер квартиры'] : []),
    ...(sameUrl ? ['Совпадает исходная ссылка'] : []),
    ...(sameTitle ? ['Сильно совпадают содержательные слова заголовка'] : [])
  ];
}

function analyzeDuplicates(items, { rates, asOf, sameGroups = [], differentPairs = [] } = {}) {
  if (!Array.isArray(items) || items.length > 50000) throw error('Ожидается массив до 50000 объявлений.');
  const fx = rateOptions(rates, asOf);
  const rules = ruleOptions(sameGroups, differentPairs);
  const entries = items.map((original, index) => {
    if (!original || typeof original !== 'object' || Array.isArray(original)) throw error('Каждое объявление должно быть объектом.');
    const item = cleanItem(original);
    const address = addressEvidence(item);
    const eligible = normalize(item.city) && typeof item.region === 'string' && ['apartment', 'house'].includes(item.kind) && typeof item.currency === 'string' && finite(item.price) && item.price >= 0;
    const converted = fx !== null && Object.hasOwn(fx.rates, item.currency);
    const comparisonPrice = finite(item.price) ? converted ? item.price * fx.rates[item.currency] : item.price : null;
    const comparisonCurrency = converted ? 'UAH' : item.currency;
    const scope = eligible ? JSON.stringify([normalize(item.city), normalize(item.region), item.kind, item.deal ?? 'sale', item.period ?? 'total', converted ? 'fx-UAH' : item.currency]) : null;
    const bucket = eligible ? JSON.stringify([normalize(item.city), normalize(item.region), item.kind, item.deal ?? 'sale', item.status ?? 'active', item.period ?? 'total', converted ? 'fx-UAH' : item.currency, address?.key ?? null, item.rooms, item.kind === 'apartment' ? item.floor : null]) : null;
    return { item, index, address, titleWords: address ? titleEvidence(item, address) : new Set(), url: canonicalUrl(item.url), bucket, scope, converted, comparisonPrice, comparisonCurrency };
  });
  const buckets = new Map();
  const clusters = [];
  const clusterReasons = new Map();
  const clusterById = new Map();
  let comparisonCount = 0;
  let analysisTruncated = false;
  for (const entry of entries) {
    if (analysisTruncated || entry.scope === null) { clusters.push([entry]); continue; }
    let automatic = entry.address ? buckets.get(entry.bucket) : null;
    if (entry.address && !automatic) { automatic = []; buckets.set(entry.bucket, automatic); }
    const possible = new Set(automatic || []);
    for (const neighbour of rules.neighbours.get(entry.item.id) || []) {
      const candidate = clusterById.get(neighbour);
      if (candidate) possible.add(candidate);
    }
    const candidates = [...possible].sort((left, right) => left[0].index - right[0].index);
    // Every member must match every other member. Similarity is never transitively inferred.
    let cluster = null;
    let matchedReasons = [];
    for (const candidate of candidates) {
      const reasons = [];
      let matches = true;
      for (const member of candidate) {
        if (comparisonCount >= MAX_PAIR_CHECKS) { analysisTruncated = true; matches = false; break; }
        comparisonCount++;
        const evidence = pairEvidence(entry, member, rules);
        if (evidence === null) { matches = false; break; }
        reasons.push(...evidence);
      }
      if (analysisTruncated) break;
      if (matches) { cluster = candidate; matchedReasons = reasons; break; }
    }
    if (cluster) {
      cluster.push(entry);
      const reasons = clusterReasons.get(cluster);
      for (const reason of matchedReasons) reasons.add(reason);
    } else {
      cluster = [entry]; clusters.push(cluster);
      clusterReasons.set(cluster, new Set());
    }
    if (automatic && !automatic.includes(cluster)) automatic.push(cluster);
    if (Number.isSafeInteger(entry.item.id)) clusterById.set(entry.item.id, cluster);
  }
  const groups = [];
  const representatives = [];
  let candidateCount = 0;
  let collapsedCount = 0;
  for (const cluster of clusters) {
    if (cluster.length === 1) {
      const entry = cluster[0];
      representatives.push({ index: entry.index, item: { ...entry.item, duplicateCount: 1, comparisonCurrency: entry.comparisonCurrency, comparisonPrice: entry.comparisonPrice, ...(entry.converted ? { comparisonAsOf: fx.asOf } : {}) } });
      continue;
    }
    const identity = cluster.map(({ item }) => JSON.stringify([item.source ?? '', item.externalId ?? '', canonicalUrl(item.url), item.id ?? ''])).sort().join('\n');
    const id = 'dup-' + createHash('sha256').update(identity).digest('hex').slice(0, 20);
    const ranked = [...cluster].sort((a, b) => a.comparisonPrice - b.comparisonPrice || (Date.parse(b.item.lastSeen || b.item.observedAt || '') || 0) - (Date.parse(a.item.lastSeen || a.item.observedAt || '') || 0) || a.index - b.index);
    const chosen = ranked[0];
    const reasons = new Set(clusterReasons.get(cluster));
    if (chosen.converted) reasons.add(`Для сравнения использованы курсы к UAH на ${fx.asOf}`);
    const allItems = cluster.map((entry) => ({ ...entry.item }));
    const reasonList = [...reasons];
    let allUserConfirmed = true;
    for (let left = 0; left < cluster.length && allUserConfirmed; left++) for (let right = left + 1; right < cluster.length; right++) {
      if (!rules.same.has(pairKey(cluster[left].item.id, cluster[right].item.id))) { allUserConfirmed = false; break; }
    }
    const confidence = allUserConfirmed ? 'user-confirmed' : 'high';
    const group = { id, confidence, label: allUserConfirmed ? 'Подтверждено пользователем' : 'Вероятный дубль', reasons: reasonList, items: allItems, representativeId: chosen.item.id };
    groups.push(group);
    representatives.push({ index: cluster[0].index, item: {
      ...chosen.item, duplicateCount: cluster.length, duplicateGroupId: id,
      duplicateAlternatives: cluster.filter((entry) => entry !== chosen).map((entry) => ({ ...entry.item })),
      duplicateConfidence: confidence, duplicateReasons: reasonList,
      comparisonCurrency: chosen.comparisonCurrency, comparisonPrice: chosen.comparisonPrice,
      ...(chosen.converted ? { comparisonAsOf: fx.asOf } : {})
    } });
    candidateCount += cluster.length;
    collapsedCount += cluster.length - 1;
  }
  representatives.sort((a, b) => a.index - b.index);
  return { items: representatives.map((entry) => entry.item), groups, candidateCount, collapsedCount, ...(analysisTruncated ? { analysisTruncated: true } : {}) };
}

module.exports = { analyzeDuplicates, isConfirmedGroup };

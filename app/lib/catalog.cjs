'use strict';

// Prefixes: public Rieltor city selector and directory links, checked 2026-10-28.
// Category paths and page=2 come from the site's navigation and pagination.
const ORIGIN = 'https://rieltor.ua';
const CITY_ROWS = [
  ['kyiv', 'Київ', 'Київська', '', ['Киев', 'Kyiv', 'Kiev']],
  ['odesa', 'Одеса', 'Одеська', 'odessa', ['Одесса', 'Odesa', 'Odessa']],
  ['kharkiv', 'Харків', 'Харківська', 'harkov', ['Харьков', 'Kharkiv', 'Kharkov']],
  ['lviv', 'Львів', 'Львівська', 'lvov', ['Львов', 'Lviv', 'Lvov']],
  ['dnipro', 'Дніпро', 'Дніпропетровська', 'dnepr', ['Днепр', 'Дніпропетровськ', 'Днепропетровск', 'Dnipro', 'Dnepr']],
  ['ivano-frankivsk', 'Івано-Франківськ', 'Івано-Франківська', 'ivano-frankovsk', ['Ивано-Франковск', 'Ivano-Frankivsk']],
  ['khmelnytskyi', 'Хмельницький', 'Хмельницька', 'hmelnitskij', ['Хмельницкий', 'Khmelnytskyi']],
  ['vinnytsia', 'Вінниця', 'Вінницька', 'vinnitsa', ['Винница', 'Vinnytsia', 'Vinnitsa']],
  ['rivne', 'Рівне', 'Рівненська', 'rovno', ['Ровно', 'Rivne']],
  ['uzhhorod', 'Ужгород', 'Закарпатська', 'uzhgorod', ['Uzhhorod', 'Uzhgorod']],
  ['zaporizhzhia', 'Запоріжжя', 'Запорізька', 'zaporozhje', ['Запорожье', 'Zaporizhzhia']],
  ['poltava', 'Полтава', 'Полтавська', 'poltava', ['Poltava']],
  ['ternopil', 'Тернопіль', 'Тернопільська', 'ternopol', ['Тернополь', 'Ternopil']],
  ['sumy', 'Суми', 'Сумська', 'sumy', ['Сумы', 'Sumy']],
  ['chernihiv', 'Чернігів', 'Чернігівська', 'chernigov', ['Чернигов', 'Chernihiv']],
  ['mykolaiv', 'Миколаїв', 'Миколаївська', 'nikolaev', ['Николаев', 'Mykolaiv']],
  ['chernivtsi', 'Чернівці', 'Чернівецька', 'chernovtsy', ['Черновцы', 'Chernivtsi']],
  ['zhytomyr', 'Житомир', 'Житомирська', 'zhitomir', ['Zhytomyr']],
  ['cherkasy', 'Черкаси', 'Черкаська', 'cherkassy', ['Черкассы', 'Cherkasy']],
  ['kropyvnytskyi', 'Кропивницький', 'Кіровоградська', 'kropyvnytskyi', ['Кропивницкий', 'Кіровоград', 'Кировоград', 'Kropyvnytskyi']],
  ['lutsk', 'Луцьк', 'Волинська', 'lutsk', ['Луцк', 'Lutsk']],
  ['kherson', 'Херсон', 'Херсонська', 'kherson', ['Kherson']],
  ['irpin', 'Ірпінь', 'Київська', 'irpen', ['Ирпень', 'Irpin']],
  ['bucha', 'Буча', 'Київська', 'bucha-239', ['Bucha']]
];

class CatalogError extends Error {
  constructor(message, code = 'CATALOG_SETTINGS') { super(message); this.name = 'CatalogError'; this.code = code; this.statusCode = 400; }
}
function normalize(value) {
  return String(value).normalize('NFKC').replace(/[’‘ʼ`´]/gu, "'").replace(/[‐‑‒–—]/gu, '-').replace(/\s+/gu, ' ').trim().toLocaleLowerCase('uk');
}
function route(row, category) {
  return `${ORIGIN}${row[3] ? `/${row[3]}` : ''}/${category}-sale/`;
}
const byName = new Map();
for (const row of CITY_ROWS) for (const alias of [row[0], row[1], ...row[4]]) byName.set(normalize(alias), row);

function getCatalog() {
  return {
    source: { id: 'rieltor', name: 'Rieltor.ua', url: `${ORIGIN}/`, directories: [`${ORIGIN}/cities/`, `${ORIGIN}/cities/houses-sale/`] },
    verifiedOn: '2026-10-28',
    cities: CITY_ROWS.map(row => ({ id: row[0], cityName: row[1], regionName: row[2], aliases: [...row[4]], kinds: ['apartment', 'house'] })),
    defaultPagesPerKind: 2,
    coverage: 'Бесплатный поиск по открытым страницам Rieltor.ua. Поддерживаются перечисленные города; поиск загружает очередные порции страниц выбранного типа. Это выборка одного источника, а не все объявления города или интернета.'
  };
}

function resolveSearch(city, kind = 'both') {
  if (typeof city !== 'string' || !city.trim() || city.length > 150) throw new CatalogError('Выберите город для поиска в интернете.', 'CATALOG_CITY_REQUIRED');
  const row = byName.get(normalize(city));
  if (!row) throw new CatalogError('Этот город пока не поддерживается быстрым поиском. Выберите город из списка либо добавьте ссылки в разделе «Источники».', 'CATALOG_CITY_UNSUPPORTED');
  if (!['apartment', 'house', 'both', 'all'].includes(kind)) throw new CatalogError('Выберите квартиры, дома или оба типа.', 'CATALOG_KIND');
  const categories = kind === 'apartment' ? ['flats'] : kind === 'house' ? ['houses'] : ['flats', 'houses'];
  const urls = categories.map(category => route(row, category));
  return { urls, cityName: row[1], regionName: row[2], maxPages: urls.length, followLinks: false };
}

// Pagination uses only a verified city/category route; caller URLs are never accepted.
function resolvePage(city, kind, page) {
  if (!['apartment', 'house'].includes(kind)) throw new CatalogError('Для страницы выберите один тип жилья.', 'CATALOG_KIND');
  if (!Number.isSafeInteger(page) || page < 1 || page > 10000) throw new CatalogError('Номер страницы должен быть от 1 до 10000.', 'CATALOG_PAGE');
  const search = resolveSearch(city, kind);
  const url = new URL(search.urls[0]);
  if (page > 1) url.searchParams.set('page', String(page));
  return { ...search, kind, page, url: url.href };
}

module.exports = { getCatalog, resolveSearch, resolvePage, CatalogError };

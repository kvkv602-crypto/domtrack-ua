'use strict';

(() => {
  const $ = (selector, context = document) => context.querySelector(selector);
  const $$ = (selector, context = document) => [...context.querySelectorAll(selector)];
  const token = $('meta[name="app-token"]')?.content || '';
  const state = { page: 1, pageSize: 50, total: 0, status: null, cities: [], riaRegions: [], riaCities: [], fx: null, preferences: { favorites: [], notes: {}, notifications: [], savedSearches: [], watches: [], duplicateRules: {} }, watchStatus: null, watchItem: null, editRecord: null, noteDrafts: {}, controller: null, toastTimer: null, filters: new URLSearchParams(), detailId: null };
  const formatter = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 });
  const rateFormatter = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6 });
  const integerFormatter = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
  state.catalog = null; state.onlineResult = null; state.onlineError = ''; state.searching = false; state.initializedCity = false;
  state.searchMode = 'refresh'; state.autoRefreshStarted = false;

  function cityKey(value) { return String(value || '').normalize('NFKC').toLocaleLowerCase('uk').replace(/\s+/g, ' ').trim(); }
  function catalogCity(value) { const key = cityKey(value); return state.catalog?.cities?.find(item => [item.id, item.cityName, ...(item.aliases || [])].some(name => cityKey(name) === key)); }
  function renderCityOptions() {
    const entries = new Map();
    for (const item of state.catalog?.cities || []) entries.set(cityKey(item.cityName), { city: item.cityName, region: item.regionName, label: 'Онлайн-поиск без ключа' });
    for (const item of state.cities) { const existing = entries.get(cityKey(item.city)); entries.set(cityKey(item.city), { ...item, label: [existing?.label, `${item.count || 0} в базе`].filter(Boolean).join(' · ') }); }
    $('#cities-list').replaceChildren(...[...entries.values()].sort((a,b) => a.city.localeCompare(b.city, 'uk')).map(item => { const option = el('option'); option.value = item.city; option.label = [item.region, item.label].filter(Boolean).join(' · '); return option; }));
    const regions = [...new Set([...entries.values()].map(item => item.region).filter(Boolean))].sort();
    $('#regions-list').replaceChildren(...regions.map(region => { const option = el('option'); option.value = region; return option; }));
  }
  async function loadCatalog(force = false) {
    const city = $('#filter-city').value.trim();
    if (state.catalog && !force && state.catalogCity === city) return;
    try { state.catalog = await api(`/api/catalog${city ? `?city=${encodeURIComponent(city)}` : ''}`); state.catalogCity = city; renderCityOptions(); renderConnectedSources(); $('#city-search-hint').textContent = `Онлайн-поиск: ${state.catalog.cities.length} города из списка. Подключённые для города площадки показаны справа и в «Источниках».`; }
    catch (error) { $('#city-search-hint').textContent = `Каталог онлайн-поиска недоступен: ${error.message}`; }
  }
  function renderConnectedSources() {
    const container = $('#connected-source-list'); const sources = state.catalog?.sources || [];
    container.replaceChildren(...sources.map(source => { const row = el('div', 'source-result'); const name = el('strong', '', source.name); const status = el('span', '', source.available === false ? source.reason || 'Для выбранного города не подключён' : 'Подключён · без API-ключа'); row.append(name, status); return row; }));
    $('#footer-sources').textContent = `Площадки автоматического поиска: ${sources.map(source => source.name).join(', ') || 'каталог загружается'}. Доступ и поддержка зависят от города.`;
  }
  function renderSourceResults(result) {
    const container = $('#online-source-results'); const sources = result?.sources || [];
    const labels = { loaded: 'Получены объявления', 'source-error': 'Ошибка доступа', unsupported: 'Данные не прочитаны', 'no-listings': 'Нет доступных данных', complete: 'Страницы загружены', unavailable: 'Не подключён для города' };
    container.replaceChildren(...sources.map(source => {
      const issues = [...(source.errors || []), ...(source.unsupportedPages || [])];
      const row = el('div', `source-result ${source.outcome === 'loaded' ? 'loaded' : ''}`); row.append(el('strong', '', source.name));
      const detail = [issues.length && source.success ? 'Частично' : labels[source.outcome] || source.outcome, `Объявлений: ${source.recordsFound || 0}`, `Страниц: ${source.pagesVisited || 0}`].filter(Boolean);
      row.append(el('span', '', detail.join(' · ')));
      if (source.reason || issues.length) row.append(el('small', '', source.reason || issues.map(error => error.message || error.error || error.reason || error).join('; ')));
      return row;
    }));
  }
  function renderOnlineResult() {
    const panel = $('.online-search-panel'); const result = state.onlineResult; const status = $('#online-search-status'); const diagnostics = $('#online-search-diagnostics');
    const currentCity = catalogCity($('#filter-city').value); const more = $('#load-more-online');
    const progress = result?.search || state.status?.onlineSearch;
    const selectedKind = $('#filter-kind').value === 'all' ? 'both' : $('#filter-kind').value;
    const sameProgress = currentCity && cityKey(currentCity.cityName) === cityKey(progress?.cityName) && selectedKind === progress?.kind;
    more.hidden = !currentCity || (sameProgress && progress?.canLoadMore === false); more.disabled = state.searching;
    more.textContent = sameProgress && progress?.retryable ? 'Повторить незагруженные страницы' : 'Загрузить следующие страницы';
    $('#show-city-results').hidden = true; panel.classList.toggle('search-error', !!state.onlineError || (!!result && !result.success));
    if (state.searching) { $('#online-search-title').textContent = 'Проверяем подключённые площадки…'; const progress = state.status?.searchProgress; status.textContent = `${state.searchMode === 'more' ? 'Загружаем следующие страницы.' : 'Обновляем первые страницы.'}${progress?.running ? ` Сейчас: ${progress.currentSource || 'подготовка'} · завершено источников ${progress.completedSources || 0} из ${progress.sourceCount || 0}.` : ''} Ранее загруженные объявления и история сохраняются.`; diagnostics.textContent = ''; return; }
    if (state.onlineError) { $('#online-search-title').textContent = 'Поиск не выполнен'; status.textContent = state.onlineError; diagnostics.textContent = 'Сохранённые объявления и настройки не удалены.'; return; }
    if (!result) { $('#online-search-title').textContent = 'Поиск по подключённым площадкам'; status.textContent = 'Выберите город и нажмите «Найти на площадках». «Показать сохранённые» работает с вашей базой.'; diagnostics.textContent = state.catalog?.coverage || ''; renderSourceResults(null); return; }
    renderSourceResults(result);
    const city = result.search?.cityName || ''; const current = catalogCity(state.filters.get('city'))?.cityName || state.filters.get('city'); const sameCity = cityKey(current) === cityKey(city);
    $('#online-search-title').textContent = result.success ? `${result.partial ? 'Поиск завершён частично' : 'Поиск выполнен'} · ${city}` : `Площадки не дали доступных объявлений · ${city}`;
    status.textContent = `Проверено страниц: ${result.pagesVisited || 0} · Получено объявлений: ${result.recordsFound || 0} · Сохранено наблюдений: ${result.ingested?.inserted || 0}${sameCity ? ` · Подходит под фильтры: ${state.total}` : ''}.`;
    const notes = [result.coverage];
    if (Array.isArray(result.search?.pages)) { const labels = { apartment: 'квартиры', house: 'дома' }; const parts = [...new Set(result.search.pages.map(page => page.sourceName || result.search.source))].flatMap(source => ['apartment','house'].map(kind => { const pages = result.search.pages.filter(p => p.kind === kind && (p.sourceName || result.search.source) === source).map(p => p.page); return pages.length ? `${source} · ${labels[kind]}: ${pages.join(', ')}` : ''; })).filter(Boolean); if (parts.length) notes.push(`Страницы прохода — ${parts.join('; ')}.`); }
    if (result.search?.hasMore) notes.push('Для продолжения нажмите «Загрузить следующие страницы».');
    for (const issue of [...(result.errors || []), ...(result.unsupportedPages || []), ...(result.notices || [])].slice(0, 5)) { const text = typeof issue === 'string' ? issue : [issue.message || issue.error || issue.reason, issue.url].filter(Boolean).join(' — '); if (text) notes.push(text); }
    if (sameCity && result.recordsFound > 0 && state.total === 0) { notes.push('Данные получены, но объявления не проходят ваши фильтры. Неизвестные комнаты, этаж или тип продавца могут исключаться; у первого наблюдения ещё нет снижения цены.'); $('#show-city-results').hidden = false; }
    if (!result.success && !(result.errors?.length || result.unsupportedPages?.length)) notes.push('Это не подтверждает отсутствие жилья в городе. Попробуйте позже или добавьте другой открытый источник.');
    diagnostics.textContent = notes.filter(Boolean).join(' ');
  }
  async function searchInternet(mode = 'refresh') {
    if (state.searching) return;
    try {
      collectFilters(); const city = $('#filter-city').value.trim(); if (!city) { $('#filter-city').focus(); throw new Error('Выберите город для поиска в интернете.'); }
      state.searching = true; state.searchMode = mode; state.onlineError = ''; showNotice(''); $('#online-search-button').disabled = true; $('#empty-online').disabled = true; $('#online-search-button').textContent = 'Ищем объявления…'; renderOnlineResult();
      state.searchPoll = setInterval(() => { loadStatus().then(renderOnlineResult).catch(() => {}); }, 3000);
      const result = await api('/api/search', { method: 'POST', body: JSON.stringify({ city, kind: $('#filter-kind').value, mode }) }); state.onlineResult = result;
      if (cityKey($('#filter-city').value) === cityKey(city)) { $('#filter-city').value = result.search.cityName; $('#filter-region').value = result.search.regionName; }
      collectFilters(); await refreshAll();
    } catch (error) { state.onlineError = error.message; showNotice(error.message, true); }
    finally { clearInterval(state.searchPoll); state.searching = false; $('#online-search-button').disabled = false; $('#empty-online').disabled = false; $('#online-search-button').textContent = 'Найти на площадках'; renderOnlineResult(); }
  }

  function el(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = String(text); return node; }
  function number(value) { return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) ? Number(value) : null; }
  function amount(value) { const n = number(value); return n === null ? 'Не указана' : formatter.format(n); }
  function rateAmount(value) { const n = number(value); return n === null ? 'Не указан' : rateFormatter.format(n); }
  function date(value, withTime = false) { if (!value) return 'Не указана'; const d = new Date(value); return Number.isNaN(d.getTime()) ? 'Не указана' : new Intl.DateTimeFormat('ru-RU', withTime ? { timeZone: 'Europe/Kyiv', day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' } : { timeZone: 'Europe/Kyiv', day: '2-digit', month: 'short', year: 'numeric' }).format(d); }
  function calendarDate(value) { const text = String(value || ''); return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text.split('-').reverse().join('.') : text.slice(0, 30); }
  function safeUrl(value) { try { const url = new URL(String(value)); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null; } catch { return null; } }
  function openDialog(id) { const dialog = $(id); if (!dialog.open) dialog.showModal(); }
  function showToast(message) { clearTimeout(state.toastTimer); $('#toast').textContent = message; $('#toast').hidden = false; state.toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 6000); }
  function showNotice(message, isError = false) { const node = $('#notice'); node.textContent = message; node.hidden = !message; node.classList.toggle('error', isError); }
  function formError(form, message) { const node = $('.form-error', form); node.textContent = message || ''; node.hidden = !message; }
  function ingestionMessage(result) { const bits = []; if (Number.isFinite(result.inserted)) bits.push(`Новых: ${result.inserted}`); if (Number.isFinite(result.updatedListings)) bits.push(`Обновлено: ${result.updatedListings}`); if (Number.isFinite(result.duplicates)) bits.push(`Повторов: ${result.duplicates}`); if (Number.isFinite(result.skipped) && result.skipped) bits.push(`Пропущено: ${result.skipped}`); return bits.length ? bits.join(' · ') : 'Данные обработаны.'; }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {}); headers.set('X-App-Token', token);
    if (options.body !== undefined) headers.set('Content-Type', 'application/json');
    const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(typeof result.error === 'string' ? result.error : `Ошибка запроса (${response.status}).`);
    return result;
  }

  function contactBlock(item, compact = false) {
    const section = el('section', `contact-block${compact ? ' compact' : ''}`); if (!compact) section.append(el('h3', '', 'Контакт продавца'));
    if (item.sellerName) section.append(el('p', 'seller-name', item.sellerName));
    const actions = el('div', 'contact-actions'); const phone = typeof item.contactPhone === 'string' ? item.contactPhone.trim() : ''; const normalizedPhone = phone.replace(/[ ()-]/g, ''); const validPhone = /^[+\d ()-]{5,40}$/.test(phone) && /^\+?\d{5,20}$/.test(normalizedPhone); const feedback = el('p', 'contact-feedback'); feedback.setAttribute('role', 'status');
    if (validPhone) { actions.append(el('span', 'contact-value', phone)); const tel = el('a', 'text-button', 'Позвонить'); tel.href = `tel:${normalizedPhone}`; actions.append(tel); const copy = el('button', 'text-button', 'Копировать номер'); copy.type = 'button'; copy.addEventListener('click', async () => { if (!navigator.clipboard?.writeText) { feedback.textContent = `Номер для ручного копирования: ${phone}`; return; } try { await navigator.clipboard.writeText(phone); feedback.textContent = 'Номер скопирован.'; } catch { feedback.textContent = `Номер для ручного копирования: ${phone}`; } }); actions.append(copy); }
    const email = typeof item.contactEmail === 'string' ? item.contactEmail.trim() : ''; if (email.length <= 250 && /^[^\s@<>\r\n]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email) && !/[\r\n]/.test(email)) { const mail = el('a', 'text-button', compact ? 'Email продавца' : email); mail.href = `mailto:${encodeURIComponent(email)}`; actions.append(mail); }
    const contactUrl = safeUrl(item.contactUrl); if (contactUrl) { const a = el('a', 'text-button', 'Страница контакта ↗'); a.href = contactUrl; a.target = '_blank'; a.rel = 'noopener noreferrer'; actions.append(a); }
    if (!actions.children.length) { section.append(el('p', 'contact-empty', 'Контакт не передан источником')); if (!compact) { const original = safeUrl(item.url); if (original) { const a = el('a', 'text-button', 'Посмотреть контакт в исходном объявлении ↗'); a.href = original; a.target = '_blank'; a.rel = 'noopener noreferrer'; section.append(a); } } }
    else section.append(actions, feedback); return section;
  }

  function openAdd(item = null) {
    const form = $('#add-form'); form.reset(); state.editRecord = item ? { ...item } : null; formError(form, '');
    for (const field of [...form.elements]) if (field instanceof HTMLInputElement || field instanceof HTMLSelectElement || field instanceof HTMLTextAreaElement) field.disabled = false;
    $('#add-title').textContent = item ? 'Записать новую цену' : 'Добавить объявление'; $('.dialog-intro', $('#add-dialog')).textContent = item ? 'Сохранится новое наблюдение этого же объявления. Источник, исходный ID и параметры объекта останутся прежними.' : 'Повторно добавляйте ту же ссылку, чтобы сохранять историю цены. Только продажа квартир и домов.';
    if (item) { for (const field of [...form.elements]) if (field.name && !['observedAt', 'price'].includes(field.name)) { if (item[field.name] !== undefined && item[field.name] !== null) field.value = item[field.name]; field.disabled = true; } form.elements.price.value = number(item.price) === null ? '' : item.price; }
    openDialog('#add-dialog');
  }

  async function correctDuplicates(payload, status) {
    status.textContent = 'Сохраняем исправление…'; applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify(payload) })); await loadListings(); status.textContent = 'Исправление сохранено. Истории исходных объявлений остаются в базе.';
  }
  function duplicateCorrection(group) {
    const items = (Array.isArray(group.items) ? group.items : []).filter(item => Number.isInteger(Number(item.id)) && Number(item.id) > 0); const details = el('details', 'duplicate-correction'); details.append(el('summary', '', 'Подтвердить или исправить совпадение')); if (items.length < 2) { details.append(el('p', 'muted', 'Для исправления нужны как минимум два объявления с ID.')); return details; }
    const status = el('p', 'correction-status'); status.setAttribute('role', 'status'); const same = el('button', 'button secondary', 'Подтвердить: это один объект'); same.type = 'button'; same.addEventListener('click', async () => { same.disabled = true; try { await correctDuplicates({ sameObjects: items.map(item => Number(item.id)) }, status); await openDuplicates(); } catch (error) { status.textContent = error.message; same.disabled = false; } });
    details.append(el('p', 'muted', 'Объединяйте только после проверки исходных объявлений. Любую пару можно отметить как разные объекты.'), same);
    const pair = el('div', 'correction-pair'); const first = el('select'); const second = el('select'); first.setAttribute('aria-label', 'Первое объявление для разделения'); second.setAttribute('aria-label', 'Второе объявление для разделения'); for (const select of [first, second]) for (const item of items) { const option = el('option', '', `#${item.id} · ${item.title || item.source || 'Объявление'}`); option.value = item.id; select.append(option); } second.selectedIndex = 1; pair.append(first, second); const different = el('button', 'button secondary', 'Разные объекты'); different.type = 'button'; different.addEventListener('click', async () => { if (first.value === second.value) { status.textContent = 'Выберите два разных объявления.'; return; } different.disabled = true; try { await correctDuplicates({ differentObjects: [Number(first.value), Number(second.value)] }, status); await openDuplicates(); } catch (error) { status.textContent = error.message; different.disabled = false; } }); details.append(pair, different, status); return details;
  }

  async function loadSummary(day) {
    const content = $('#summary-content'); content.replaceChildren(el('p', 'loading', 'Собираем события за день…'));
    try { const result = await api(`/api/summary${day ? `?${new URLSearchParams({ day })}` : ''}`); if (/^\d{4}-\d{2}-\d{2}$/.test(result.day || '')) $('#summary-date').value = result.day; const stats = el('div', 'summary-stats'); for (const [label, value] of [['Новых объявлений', result.newListings], ['Снижений цены', result.priceDrops], ['Достигнутых порогов', result.targetsReached]]) { const article = el('article'); const count = Array.isArray(value) ? value.length : number(value); article.append(el('span', '', label), el('strong', '', count === null ? '—' : integerFormatter.format(count))); stats.append(article); } content.replaceChildren(stats); if (typeof result.text === 'string') content.append(el('p', 'summary-text', result.text)); content.append(el('p', 'muted', 'Сводка основана на сохранённых наблюдениях приложения за выбранный день.'));
    } catch (error) { content.replaceChildren(el('p', 'form-error', error.message)); }
  }

  function collectFilters() {
    const form = $('#filter-form'); const fd = new FormData(form); const params = new URLSearchParams();
    for (const key of ['city', 'region', 'district', 'kind', 'sellerType', 'priceMin', 'priceMax', 'areaMin', 'areaMax', 'floorMin', 'floorMax', 'floorsTotalMin', 'floorsTotalMax', 'currency']) { const value = String(fd.get(key) || '').trim(); if (value && value !== 'all') params.set(key, value); }
    const rooms = fd.getAll('rooms'); if (rooms.length) params.set('rooms', rooms.join(','));
    for (const key of ['excludeFirst', 'excludeLast', 'favorites']) if (fd.has(key)) params.set(key, 'true');
    params.set('dedupe', fd.has('dedupe') ? 'true' : 'false');
    const fxDate = $('#fx-date').value; if (/^\d{4}-\d{2}-\d{2}$/.test(fxDate)) params.set('fxDate', fxDate);
    for (const [minKey, maxKey, label] of [['priceMin', 'priceMax', 'цены'], ['areaMin', 'areaMax', 'площади'], ['floorMin', 'floorMax', 'этажа'], ['floorsTotalMin', 'floorsTotalMax', 'этажности']]) { if (params.has(minKey) && params.has(maxKey) && Number(params.get(minKey)) > Number(params.get(maxKey))) throw new Error(`Минимальное значение ${label} больше максимального.`); }
    if (!params.has('currency') && (params.has('priceMin') || params.has('priceMax') || ['price-asc', 'price-desc'].includes($('#sort-select').value))) { $('#filter-currency').focus(); throw new Error('Для диапазона или сортировки цены выберите одну валюту: USD, UAH либо EUR.'); }
    const query = $('#search-input').value.trim(); if (query) params.set('q', query);
    params.set('changes', $('#changes-select').value); params.set('sort', $('#sort-select').value);
    state.filters = params; state.page = 1;
  }

  async function loadListings() {
    state.controller?.abort(); const controller = new AbortController(); state.controller = controller;
    const params = new URLSearchParams(state.filters); params.set('page', state.page); params.set('pageSize', '50');
    const container = $('#listing-list'); container.setAttribute('aria-busy', 'true');
    if (!container.children.length) container.append(el('div', 'loading loading-pulse', 'Загружаем объявления…'));
    try {
      const result = await api(`/api/listings?${params}`, { signal: controller.signal });
      if (state.controller !== controller) return;
      const items = Array.isArray(result.items) ? result.items : []; state.total = Number(result.total) || 0; state.page = Number(result.page) || state.page; state.pageSize = Number(result.pageSize) || 50;
      state.currencyComparison = result.currencyComparison || null;
      if (state.currencyComparison) { await loadFx(); if (state.controller !== controller) return; }
      renderCurrencyComparison();
      container.replaceChildren(...items.map(renderCard)); $('#results-count').textContent = integerFormatter.format(state.total);
      const city = state.filters.get('city'); const kind = state.filters.get('kind');
      $('#results-title').firstChild.textContent = city ? `Жильё · ${city} ` : 'Объявления ';
      const collapsed = number(result.collapsedCount); const rawTotal = number(result.rawTotal);
      const groupingNote = state.filters.get('dedupe') !== 'false' && collapsed !== null && collapsed > 0 ? ` Объединено подтверждённых дублей: ${integerFormatter.format(collapsed)}${rawTotal !== null ? ` из ${integerFormatter.format(rawTotal)}` : ''}.` : '';
      $('#results-context').textContent = `${kind === 'house' ? 'Дома' : kind === 'apartment' ? 'Квартиры' : 'Квартиры и дома'} в продаже${city ? ` · ${city}` : ' · все города'} · ${state.total ? `показано ${((state.page - 1) * state.pageSize) + 1}–${Math.min(state.page * state.pageSize, state.total)} из ${integerFormatter.format(state.total)}` : 'нет результатов'}.${groupingNote}`;
      $('#empty-state').hidden = !!items.length;
      const filtered = [...state.filters.entries()].some(([key, value]) => !['sort', 'changes', 'dedupe', 'fxDate'].includes(key) || (key === 'changes' && value !== 'all'));
      $('#empty-title').textContent = Number(state.status?.listingCount) === 0 ? 'Загрузите объявления из интернета' : filtered ? 'В сохранённых данных нет совпадений' : 'Здесь появится ваша подборка';
      $('#empty-copy').textContent = Number(state.status?.listingCount) === 0 ? 'Выберите город и нажмите «Найти в интернете». Для открытого источника ключ не нужен.' : 'Можно загрузить предложения выбранного города из интернета либо изменить фильтры сохранённой подборки.';
      $('#demo-button').hidden = filtered || Number(state.status?.listingCount) > 0;
      $('#demo-button').nextElementSibling.hidden = $('#demo-button').hidden;
      const pages = Math.max(1, Math.ceil(state.total / state.pageSize)); $('#pagination').hidden = pages <= 1; $('#previous-page').disabled = state.page <= 1; $('#next-page').disabled = state.page >= pages; $('#page-label').textContent = `Страница ${state.page} из ${pages}`;
      renderOnlineResult();
    } catch (error) {
      if (error.name === 'AbortError') return;
      container.replaceChildren(el('p', 'loading', 'Не удалось загрузить объявления.')); $('#empty-state').hidden = true; showNotice(error.message, true);
    } finally { if (state.controller === controller) container.removeAttribute('aria-busy'); }
  }

  function renderCard(item) {
    const card = el('article', 'listing-card');
    const main = el('div', 'listing-main'); const location = el('div', 'listing-location');
    location.append(el('span', 'city', item.city || 'Город не указан')); if (item.region) location.append(el('span', '', `· ${item.region}`));
    const source = sourceName(item.source); location.append(el('span', `source-tag${item.source === 'demo' ? ' demo' : ''}`, source));
    const sellerType = ['owner', 'developer', 'agent'].includes(item.sellerType) ? item.sellerType : 'unknown'; location.append(el('span', `source-tag seller-tag ${sellerType}`, sellerLabel(sellerType)));
    if (item.stale === true || item.freshness?.stale === true) location.append(el('span', 'source-tag stale-tag', 'Давно не проверялось'));
    const favorite = !!item.isFavorite || state.preferences.favorites.some(id => String(id) === String(item.id));
    const star = el('button', `favorite-button${favorite ? ' active' : ''}`, favorite ? '★' : '☆'); star.type = 'button'; star.setAttribute('aria-label', favorite ? 'Удалить из избранного' : 'Добавить в избранное'); star.setAttribute('aria-pressed', String(favorite)); star.addEventListener('click', async () => { star.disabled = true; try { const result = await api('/api/preferences', { method: 'POST', body: JSON.stringify({ favorite: { id: item.id, active: !favorite } }) }); applyPreferences(result); await loadListings(); } catch (error) { showNotice(error.message, true); star.disabled = false; } }); location.append(star);
    const heading = el('h3', 'listing-title'); const titleButton = el('button', '', item.title || 'Объявление без заголовка'); titleButton.type = 'button'; titleButton.addEventListener('click', () => openDetails(item.id)); heading.append(titleButton);
    main.append(location, heading); if (item.address || item.district) main.append(el('p', 'listing-address', [item.district, item.address].filter(Boolean).join(' · ')));
    const meta = el('div', 'listing-meta'); meta.append(el('span', '', item.kind === 'house' ? 'Дом' : 'Квартира'));
    if (number(item.rooms) !== null) meta.append(el('span', '', `${amount(item.rooms)} комн.`));
    if (number(item.area) !== null) meta.append(el('span', '', `${amount(item.area)} м²`));
    if (item.kind === 'house') { if (number(item.floorsTotal) !== null) meta.append(el('span', '', `Этажность: ${amount(item.floorsTotal)}`)); }
    else if (number(item.floor) !== null) meta.append(el('span', '', `${amount(item.floor)}${number(item.floorsTotal) !== null ? ` / ${amount(item.floorsTotal)}` : ''} этаж`));
    main.append(meta, contactBlock(item, true));
    if (item.source === 'web:obyava.ua' && item.currency === 'UAH') main.append(el('p', 'muted', 'Гривневая котировка, показанная OBYAVA. Исходная валюта продавца не подтверждена; изменение котировки может быть связано с курсом.'));
    const priceArea = el('div', 'listing-price');
    const shownPrice = number(item.convertedPrice) ?? number(item.price); const shownCurrency = number(item.convertedPrice) !== null ? item.displayCurrency || item.currency : item.currency;
    priceArea.append(priceNode(shownPrice, shownCurrency));
    if (shownCurrency !== item.currency && number(item.convertedPrice) !== null) priceArea.append(el('p', 'original-price', `В объявлении: ${amount(item.price)} ${item.currency} · НБУ на ${calendarDate(item.fxDate)}`));
    if (item.rateUnavailable) priceArea.append(el('p', 'conversion-warning', `Нет курса для ${item.displayCurrency}; показана исходная цена.`));
    if (item.priceRangeUnverified) priceArea.append(el('p', 'conversion-warning', 'Диапазон цены не проверен: нужен курс НБУ.'));
    if (number(item.area) !== null && Number(item.area) > 0 && shownPrice !== null) priceArea.append(el('p', 'unit-price', `${amount(shownPrice / Number(item.area))} ${shownCurrency || ''}/м²`)); appendChange(priceArea, item);
    const bottom = el('div', 'card-bottom'); const observationCount = number(item.observationCount); bottom.append(el('span', '', `ID ${item.id} · Обновлено ${date(item.lastSeen)} · Наблюдений: ${observationCount === null ? '—' : integerFormatter.format(observationCount)}`));
    const links = el('div', 'card-links'); const detail = el('button', 'text-button', 'История цены →'); detail.type = 'button'; detail.addEventListener('click', () => openDetails(item.id)); links.append(detail);
    const watch = watchFor(item.id); const targetButton = el('button', 'text-button', item.isWatched || watch?.active ? 'Изменить порог цены' : 'Следить за ценой'); targetButton.type = 'button'; targetButton.addEventListener('click', () => openTarget(item)); links.append(targetButton);
    if (item.isWatched || watch?.active) location.append(el('span', 'source-tag watch-badge', 'Цена отслеживается'));
    const url = safeUrl(item.url); if (url) { const a = el('a', '', 'Открыть объявление ↗'); a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer'; links.append(a); }
    bottom.append(links); card.append(main, priceArea, bottom);
    if (Number(item.duplicateCount) > 1) {
      const confirmed = confirmedDuplicate(item);
      location.append(el('span', 'source-tag clone-tag', `${confirmed ? 'Ещё' : 'Возможный дубль · ещё'} ${integerFormatter.format(Number(item.duplicateCount) - 1)} предложений`));
      const details = el('details', 'duplicate-details'); details.append(el('summary', '', confirmed ? 'Сравнить подтверждённые дубли' : 'Посмотреть возможные совпадения'));
      details.append(el('p', '', confirmed ? 'Совпадение подтверждено. История каждого объявления сохранена отдельно.' : `Это возможное совпадение. Такие объявления остаются в основном списке и не объединяются по предположению.${confidenceLabel(item.duplicateConfidence) ? ` ${confidenceLabel(item.duplicateConfidence)}.` : ''}`));
      const reasons = reasonText(item.duplicateReasons); if (reasons) details.append(el('p', '', `Почему объединены: ${reasons}.`));
      if (item.duplicateGroupId !== null && item.duplicateGroupId !== undefined) details.append(groupHistoryButton(item.duplicateGroupId));
      if (confirmed && item.comparisonCurrency) details.append(el('p', '', `Валюта сравнения: ${item.comparisonCurrency}. Исходные цены показаны без изменения.`));
      const alternatives = Array.isArray(item.duplicateAlternatives) ? item.duplicateAlternatives : [];
      const list = el('div', 'duplicate-list'); list.append(duplicateItem(item, true));
      for (const alternative of alternatives) if (alternative && String(alternative.id) !== String(item.id)) list.append(duplicateItem(alternative, false));
      details.append(list); card.append(details);
    }
    return card;
  }

  function reasonText(reasons) { return Array.isArray(reasons) ? reasons.filter(value => typeof value === 'string').join('; ') : typeof reasons === 'string' ? reasons : ''; }
  function sourceName(source) {
    if (source === 'demo') return 'Учебный'; if (source === 'manual') return 'Вручную';
    const name = typeof source === 'string' && source.startsWith('web:') ? source.slice(4) : source;
    return state.catalog?.sources?.find(item => item.id === name || (item.url && new URL(item.url).hostname === name))?.name || ({ domria: 'DIM.RIA · API', 'dom.ria.com': 'DIM.RIA · открытые страницы', rieltor: 'Rieltor.ua', 'rieltor.ua': 'Rieltor.ua', lun: 'ЛУН', 'lun.ua': 'ЛУН' })[name] || source || 'Импорт';
  }
  function renderCurrencyComparison() {
    const node = $('#currency-comparison-status'); const comparison = state.currencyComparison;
    node.hidden = !comparison?.currency; node.classList.toggle('warning', !!comparison?.rateUnavailableCount);
    if (!comparison?.currency) { node.textContent = ''; return; }
    node.textContent = [`Валюта сравнения: ${comparison.currency}. Исходная цена сохранена в каждой карточке.`, comparison.date ? `Курс НБУ на ${calendarDate(comparison.date)}.` : '', comparison.warning || '', comparison.unverifiedPriceCount ? `Без проверки диапазона: ${integerFormatter.format(comparison.unverifiedPriceCount)}. Они отмечены в карточках.` : ''].filter(Boolean).join(' ');
  }
  function confirmedDuplicate(group) { return group.confirmed === true || ['user-confirmed', 'confirmed', 'same-source', 'exact'].includes(group.duplicateConfidence || group.confidence); }
  function confidenceLabel(value) {
    const n = number(value); if (n !== null && n >= 0 && n <= 100) return `Оценка сходства: ${amount(n <= 1 ? n * 100 : n)}%`;
    return ({ high: 'Высокое сходство', medium: 'Среднее сходство', low: 'Невысокое сходство' })[value] || '';
  }
  function duplicateItem(item, representative) {
    const row = el('article', `duplicate-item${representative ? ' representative' : ''}`); const main = el('div'); main.append(el('strong', '', item.title || 'Объявление'));
    main.append(el('small', '', [`ID ${item.id}`, item.source === 'demo' ? 'Учебный' : item.source || 'Источник не указан', item.city, item.address, sellerLabel(item.sellerType)].filter(Boolean).join(' · ')));
    const actions = el('div', 'duplicate-item-actions'); const url = safeUrl(item.url); if (url) { const a = el('a', 'text-button', 'Источник ↗'); a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer'; actions.append(a); }
    if (item.id !== null && item.id !== undefined) { const button = el('button', 'text-button', 'История цены'); button.type = 'button'; button.addEventListener('click', () => { if ($('#duplicates-dialog').open) $('#duplicates-dialog').close(); openDetails(item.id); }); actions.append(button); }
    main.append(actions); const price = el('div', 'duplicate-price'); price.append(el('strong', '', `${amount(item.price)} ${item.currency || ''}`)); if (representative) price.append(el('small', '', 'Основное предложение')); row.append(main, price); return row;
  }

  async function openDuplicates() {
    const content = $('#duplicates-content'); content.replaceChildren(el('p', 'loading', 'Сопоставляем сохранённые объявления…')); openDialog('#duplicates-dialog');
    try { const result = await api('/api/duplicates'); const groups = Array.isArray(result.groups) ? result.groups : []; content.replaceChildren(); if (!groups.length) { content.append(el('p', 'chart-message', 'Вероятных дублей пока не найдено. Добавляйте объявления из разных источников: совпадения появятся по мере накопления данных.')); return; }
      content.append(el('p', 'muted', `Групп вероятных совпадений: ${integerFormatter.format(groups.length)}${number(result.candidateCount) !== null ? ` · объявлений-кандидатов: ${integerFormatter.format(Number(result.candidateCount))}` : ''}.`));
      for (const [index, group] of groups.entries()) { const section = el('section', 'duplicate-group'); section.append(el('h3', '', group.label || `Группа ${index + 1}`), el('p', '', confirmedDuplicate(group) ? 'Подтверждённое совпадение' : 'Возможное совпадение: объявления остаются отдельными.')); const confidence = confidenceLabel(group.confidence); if (confidence) section.append(el('p', '', confidence)); const reasons = reasonText(group.reasons); if (reasons) section.append(el('p', '', `Основания: ${reasons}.`)); if (group.id !== null && group.id !== undefined) section.append(groupHistoryButton(group.id)); const list = el('div', 'duplicate-list'); for (const item of Array.isArray(group.items) ? group.items : []) if (item) list.append(duplicateItem(item, confirmedDuplicate(group) && String(item.id) === String(group.representativeId))); section.append(list, duplicateCorrection(group)); content.append(section); }
    } catch (error) { content.replaceChildren(el('p', 'form-error', error.message)); }
  }

  function renderFx(result) {
    state.fx = result; const rates = result?.rates || {}; const usd = number(rates.USD); const eur = number(rates.EUR);
    const valid = result?.usable === true && result?.asOf === result?.requestedDate && usd !== null && usd > 0 && eur !== null && eur > 0;
    if (/^\d{4}-\d{2}-\d{2}$/.test(result?.requestedDate || '') && document.activeElement !== $('#fx-date')) $('#fx-date').value = result.requestedDate;
    const dated = result?.requestedDate || result?.asOf;
    $('#fx-status').textContent = valid ? `НБУ${dated ? ` на ${calendarDate(dated)}` : ''}: 1 USD = ${rateAmount(usd)} ₴ · 1 EUR = ${rateAmount(eur)} ₴. Межвалютное сравнение приблизительное.` : `Курс НБУ${dated ? ` на ${calendarDate(dated)}` : ''} ещё не загружен. Разные валюты без курса не сравниваются.`;
  }
  async function loadFx() { const requestedDate = state.filters.get('fxDate') || $('#fx-date').value; try { renderFx(await api(`/api/fx${requestedDate ? `?date=${encodeURIComponent(requestedDate)}` : ''}`)); } catch { renderFx({ requestedDate }); } }

  function applyPreferences(result) {
    const data = result?.preferences || result || {};
    state.preferences = { favorites: Array.isArray(data.favorites) ? data.favorites : [], notes: data.notes && typeof data.notes === 'object' ? data.notes : {}, notifications: Array.isArray(data.notifications) ? data.notifications : [], savedSearches: Array.isArray(data.savedSearches) ? data.savedSearches : [], watches: Array.isArray(data.watches) ? data.watches : [], duplicateRules: data.duplicateRules && typeof data.duplicateRules === 'object' ? data.duplicateRules : {} };
    const unread = state.preferences.notifications.filter(item => !item.read).length; $('#notifications-count').textContent = unread > 99 ? '99+' : String(unread); $('#notifications-count').hidden = !unread;
  }
  async function loadPreferences() { applyPreferences(await api('/api/preferences')); }
  async function loadWatchStatus() { state.watchStatus = await api('/api/watch/status'); }
  function watchFor(id) { return state.preferences.watches.find(watch => String(watch.id) === String(id)); }
  function openTarget(item) {
    state.watchItem = item; const watch = watchFor(item.id); $('#target-id').value = item.id; $('#target-price').value = number(watch?.targetPrice) === null ? '' : watch.targetPrice; $('#target-currency').value = ['USD', 'UAH', 'EUR'].includes(watch?.currency) ? watch.currency : ['USD', 'UAH', 'EUR'].includes(item.displayCurrency) ? item.displayCurrency : ['USD', 'UAH', 'EUR'].includes(item.currency) ? item.currency : 'USD'; $('#target-active').checked = watch ? !!watch.active : true; $('#target-description').textContent = `${item.title || 'Объявление'}${item.city ? ` · ${item.city}` : ''}. Текущая сохранённая цена: ${amount(item.price)} ${item.currency || ''}.`; formError($('#target-form'), ''); openDialog('#target-dialog');
  }

  function groupHistoryButton(id) { const button = el('button', 'text-button', 'История всей группы →'); button.type = 'button'; button.addEventListener('click', () => { if ($('#duplicates-dialog').open) $('#duplicates-dialog').close(); openGroupHistory(id); }); return button; }
  async function openGroupHistory(id) {
    const content = $('#group-history-content'); content.replaceChildren(el('p', 'loading', 'Загружаем наблюдения всех источников…')); openDialog('#group-history-dialog');
    try { const result = await api(`/api/groups/${encodeURIComponent(id)}`); content.replaceChildren(); if (result.group?.label) content.append(el('h3', '', result.group.label)); content.append(el('p', 'group-history-note', 'Это история вероятно одного объекта по разным источникам. Каждая цена сохранена в исходной валюте. Разные валюты здесь не сравниваются и не объединяются в одну линию.'));
      const wrap = el('div', 'history-table-wrap'); const table = el('table', 'history-table'); table.append(el('caption', '', 'Наблюдения группы')); const head = el('thead'); const row = el('tr'); for (const label of ['Дата', 'Источник', 'Цена', 'Объявление']) { const th = el('th', '', label); th.scope = 'col'; row.append(th); } head.append(row); table.append(head); const body = el('tbody');
      const history = (Array.isArray(result.history) ? result.history : []).slice().sort((a, b) => new Date(b.observedAt) - new Date(a.observedAt));
      for (const record of history) { const tr = el('tr'); const action = el('td'); if (record.listingId !== undefined && record.listingId !== null) { const button = el('button', 'text-button', 'Открыть историю'); button.type = 'button'; button.addEventListener('click', () => { $('#group-history-dialog').close(); openDetails(record.listingId); }); action.append(button); } else action.textContent = '—'; tr.append(el('td', '', date(record.observedAt, true)), el('td', '', record.source === 'demo' ? 'Учебный' : record.source || 'Не указан'), el('td', '', `${amount(record.price)} ${record.currency || ''}`), action); body.append(tr); }
      if (!history.length) { const tr = el('tr'); const cell = el('td', '', 'У группы пока нет сохранённых наблюдений.'); cell.colSpan = 4; tr.append(cell); body.append(tr); } table.append(body); wrap.append(table); content.append(wrap);
    } catch (error) { content.replaceChildren(el('p', 'form-error', error.message)); }
  }

  function renderNotifications() {
    const content = $('#notifications-content'); const notifications = state.preferences.notifications; content.replaceChildren(); $('#notifications-read').disabled = !notifications.some(item => !item.read);
    if (!notifications.length) { content.append(el('p', 'chart-message', 'Событий пока нет. Добавьте объект в избранное или сохраните подборку, затем загружайте новые наблюдения.')); return; }
    for (const notification of notifications.slice().sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))) { const article = el('article', `notification-item${notification.read ? '' : ' unread'}`); article.append(el('p', '', notification.message || 'Новое событие в подборке'), el('small', '', date(notification.createdAt, true))); if (notification.listingId !== null && notification.listingId !== undefined) { const button = el('button', 'text-button', 'Открыть объявление →'); button.type = 'button'; button.addEventListener('click', () => { $('#notifications-dialog').close(); openDetails(notification.listingId); }); article.append(button); } content.append(article); }
  }

  function priceNode(value, currency) { const p = el('div', 'price'); p.append(document.createTextNode(amount(value)), el('span', 'price-currency', currency || '')); return p; }
  function appendChange(container, item) {
    const delta = number(item.changeAmount); const percent = number(item.changePercent);
    if (item.previousCurrency && item.currency && item.previousCurrency !== item.currency) { container.append(el('span', 'change-chip flat', 'Валюта изменилась')); container.append(el('p', 'previous-price', `Ранее: ${amount(item.previousPrice)} ${item.previousCurrency}`)); return; }
    if (delta !== null && delta !== 0) { const down = delta < 0; container.append(el('span', `change-chip${down ? '' : ' up'}`, `${down ? '↓' : '↑'} ${percent !== null ? `${amount(Math.abs(percent))}%` : `${amount(Math.abs(delta))} ${item.currency || ''}`}`)); if (number(item.previousPrice) !== null) container.append(el('p', 'previous-price', `Ранее: ${amount(item.previousPrice)} ${item.previousCurrency || item.currency || ''}`)); }
    else { const count = number(item.observationCount); const unchanged = count > 1 && (delta === 0 || (item.previousPrice == null && number(item.initialPrice) === number(item.price))); const label = unchanged ? 'Цена без изменений' : count === 1 ? 'Первое наблюдение' : 'Нет сопоставимой цены'; container.append(el('span', 'change-chip flat', label)); }
  }

  async function loadCities() { try { const result = await api('/api/cities'); state.cities = Array.isArray(result.cities) ? result.cities : []; renderCityOptions(); } catch { /* Manual city entry remains available. */ } }

  async function loadStatus() {
    const result = await api('/api/status'); state.status = result;
    $('#app-version').textContent = result.appVersion ? `Версия ${result.appVersion}` : 'Старая версия сервера';
    $('#stat-total').textContent = integerFormatter.format(Number(result.listingCount) || 0); $('#stat-changed').textContent = integerFormatter.format(Number(result.changedCount) || 0); $('#stat-down').textContent = integerFormatter.format(Number(result.downCount) || 0);
    $('#stat-latest').textContent = result.lastSeen ? date(result.lastSeen) : 'Пока нет';
    const collector = result.collector || {};
    $('#collector-summary').textContent = result.searchProgress?.running ? `Проверяем ${result.searchProgress.currentSource || 'площадки'}` : result.web?.running ? 'Проверяем открытые страницы' : collector.running ? 'Сейчас проверяем API' : result.web?.lastError || collector.lastError ? 'Есть ошибки источников' : result.web?.lastResult?.partial ? 'Часть площадок недоступна' : result.web?.lastResult?.success === false ? 'Данные источников не получены' : result.web?.lastRun ? 'Поиск завершён · см. отчёт по площадкам' : collector.configured ? 'Дополнительный API настроен' : 'Нажмите «Найти на площадках»';
    if (!state.onlineResult && result.web?.lastResult?.search) state.onlineResult = result.web.lastResult;
    $('#source-state').textContent = collector.running ? 'Проверка выполняется' : result.keyStorage?.error ? 'Сохранённый ключ недоступен' : result.keyStorage?.stored ? 'API-ключ сохранён и защищён Windows' : result.apiKeyConfigured ? 'API-ключ подключён из среды запуска' : 'Нужен API-ключ';
    $('#key-hint').textContent = result.keyStorage?.error || (result.keyStorage?.stored ? 'Сохранённый ключ загружается автоматически. Поле пустое намеренно: вставьте новый ключ, только если хотите заменить текущий.' : result.apiKeyConfigured ? 'Ключ из среды запуска действует сейчас. Чтобы сохранить его в хранилище Windows, вставьте ключ в это поле.' : 'Ключ сохраняется зашифрованным с помощью Windows DPAPI и загружается при следующем запуске. В браузер обратно не выводится.');
    const quota = collector.quota; const monthly = quota?.monthly;
    $('#source-last-run').textContent = [collector.lastRun ? `Последний запуск: ${date(collector.lastRun, true)}.` : 'Проверок ещё не было.', collector.nextRun ? `Следующий: ${date(collector.nextRun, true)}.` : '', quota ? `Локальный учёт: ${quota.used}/${quota.limit} за час${monthly ? `; ${monthly.used}/${monthly.limit} за 30 дней` : ''}.` : '', 'Использование ключа вне DomTrack здесь не учитывается.', collector.lastError ? `Ошибка: ${collector.lastError}` : ''].filter(Boolean).join(' ');
    $('#collect-button').disabled = !!collector.running || !result.apiKeyConfigured;
    $('#load-regions-button').disabled = !result.apiKeyConfigured && !$('#source-key').value.trim();
    $('#load-cities-button').disabled = !result.apiKeyConfigured && !$('#source-key').value.trim();
    return result;
  }

  async function refreshAll() {
    const results = await Promise.allSettled([loadStatus(), loadCities(), loadCatalog(), loadFx(), loadPreferences(), loadWatchStatus()]); if (results[0].status === 'rejected') showNotice(results[0].reason.message, true);
    if (!state.initializedCity) { const form = $('#filter-form'); const saved = state.status?.onlineSearch; const popular = [...state.cities].sort((a,b) => Number(b.count) - Number(a.count))[0]; const selected = saved?.cityName || popular?.city || state.status?.collector?.settings?.cityName; if (!form.elements.city.value && selected) { const city = catalogCity(selected); form.elements.city.value = city?.cityName || selected; form.elements.region.value = city?.regionName || popular?.region || ''; if (saved?.kind) form.elements.kind.value = saved.kind === 'both' ? 'all' : saved.kind; } state.initializedCity = true; collectFilters(); }
    await loadCatalog(); await loadListings();
    if (!state.autoRefreshStarted && state.status?.desktop && Number(state.status.apiVersion) >= 2) { state.autoRefreshStarted = true; if (catalogCity($('#filter-city').value)) setTimeout(() => searchInternet('refresh'), 0); }
  }

  function populateSources() {
    const settings = state.status?.collector?.settings || {}; const form = $('#sources-form');
    for (const name of ['stateId', 'cityId', 'cityName', 'regionName', 'kind', 'intervalMinutes', 'maxListings', 'monthlyBudget']) { if (settings[name] !== undefined && settings[name] !== null) form.elements[name].value = settings[name]; }
    form.elements.enabled.checked = !!settings.enabled; form.elements.apiKey.value = ''; formError(form, '');
  }

  async function ensureSourceKey() {
    const input = $('#source-key'); const apiKey = input.value.trim();
    if (apiKey) { await api('/api/settings', { method: 'POST', body: JSON.stringify({ apiKey }) }); input.value = ''; await loadStatus(); }
    if (!state.status?.apiKeyConfigured) throw new Error('Сначала введите API-ключ DIM.RIA. Он будет сохранён в защищённом хранилище Windows.');
  }

  function directoryOptions(select, items, placeholder) {
    const first = el('option', '', placeholder); first.value = '';
    select.replaceChildren(first, ...items.map(item => { const option = el('option', '', item.name); option.value = String(item.id); return option; })); select.disabled = !items.length;
  }

  async function submitForm(form, action) { const buttons = $$('button', form); const prior = buttons.map(button => button.disabled); buttons.forEach(button => { button.disabled = true; }); formError(form, ''); try { await action(); } catch (error) { formError(form, error.message); } finally { buttons.forEach((button, index) => { button.disabled = prior[index]; }); } }

  async function openDetails(id) {
    state.detailId = id; $('#detail-title').textContent = 'Наблюдения цены'; $('#detail-location').textContent = 'ИСТОРИЯ ОБЪЯВЛЕНИЯ'; $('#detail-content').replaceChildren(el('p', 'loading', 'Загружаем историю…')); openDialog('#detail-dialog');
    try { const result = await api(`/api/listings/${encodeURIComponent(id)}`); if (state.detailId !== id) return; renderDetails(result.listing || {}, Array.isArray(result.history) ? result.history : [], result); } catch (error) { if (state.detailId === id) $('#detail-content').replaceChildren(el('p', 'form-error', error.message)); }
  }

  function renderDetails(item, history, metadata = {}) {
    $('#detail-title').textContent = item.title || 'Объявление'; $('#detail-location').textContent = [item.city, item.region, item.source === 'demo' ? 'УЧЕБНЫЙ ПРИМЕР' : 'ПРОДАЖА'].filter(Boolean).join(' · ');
    const content = $('#detail-content'); content.replaceChildren();
    if (item.address || item.district) content.append(el('p', 'muted', [item.district, item.address].filter(Boolean).join(' · ')));
    content.append(el('p', 'muted', `Впервые замечено: ${date(item.firstSeen, true)} · Последнее наблюдение: ${date(item.lastSeen, true)}`));
    const facts = el('div', 'detail-facts'); facts.append(el('span', '', item.kind === 'house' ? 'Дом' : 'Квартира'), el('span', '', sellerLabel(item.sellerType))); if (number(item.rooms) !== null) facts.append(el('span', '', `${amount(item.rooms)} комн.`)); if (number(item.area) !== null) facts.append(el('span', '', `${amount(item.area)} м²`)); if (number(item.floor) !== null) facts.append(el('span', '', `Этаж: ${amount(item.floor)}`)); if (number(item.floorsTotal) !== null) facts.append(el('span', '', `Этажность: ${amount(item.floorsTotal)}`)); content.append(facts);
    const priceRow = el('div', 'detail-price-row'); priceRow.append(priceNode(item.price, item.currency)); appendChange(priceRow, item); content.append(priceRow); renderPriceWatch(content, item);
    const url = safeUrl(item.url); if (url) { const a = el('a', 'button secondary', 'Открыть исходное объявление ↗'); a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer'; content.append(a); }
    const newPrice = el('button', 'button secondary new-price-button', 'Записать новую цену'); newPrice.type = 'button'; newPrice.addEventListener('click', () => openAdd(item)); content.append(newPrice, contactBlock(item));
    const chartSection = el('section', 'chart-section'); const heading = el('div', 'chart-heading'); heading.append(el('h3', '', 'История запрашиваемой цены')); const currencies = [...new Set(history.map(record => record.currency).filter(Boolean))]; const select = el('select'); select.setAttribute('aria-label', 'Валюта графика'); for (const currency of currencies) { const o = el('option', '', currency); o.value = currency; select.append(o); } if (currencies.includes(item.currency)) select.value = item.currency;
    if (currencies.length > 1) heading.append(select); chartSection.append(heading); const chart = el('div'); chartSection.append(chart); if (currencies.length > 1) chartSection.append(el('p', 'muted', 'В истории несколько валют. График показывает только выбранную валюту, без пересчёта.'));
    const draw = () => drawChart(chart, history, select.value || item.currency); select.addEventListener('change', draw); draw(); content.append(chartSection);
    const pending = number(metadata.historicalFxPending);
    const historyFxControls = el('div', 'history-fx-controls'); const historyFxStatus = el('p', 'muted', pending !== null ? pending > 0 ? `Ожидают исторического курса: ${integerFormatter.format(pending)}. За один запрос загружается до 30 дат.` : 'Курсы для доступных исторических наблюдений загружены.' : 'Пересчёт использует официальный курс НБУ на дату каждого наблюдения.'); historyFxStatus.setAttribute('role', 'status');
    const historyFxButton = el('button', 'button secondary', 'Загрузить курсы НБУ по датам'); historyFxButton.type = 'button'; historyFxButton.disabled = !history.length || pending === 0;
    historyFxButton.addEventListener('click', async () => {
      const id = item.id; historyFxButton.disabled = true; historyFxButton.textContent = 'Загружаем исторические курсы…'; historyFxStatus.textContent = 'Обращаемся к НБУ за курсами на даты наблюдений. Исходные цены не меняются.';
      try { const result = await api('/api/fx/history', { method: 'POST', body: JSON.stringify({ id }) }); if (state.detailId !== id) return; renderDetails(result.listing || item, Array.isArray(result.history) ? result.history : history, result); }
      catch (error) { historyFxStatus.textContent = error.message; historyFxStatus.setAttribute('role', 'alert'); historyFxButton.disabled = false; historyFxButton.textContent = 'Загрузить курсы НБУ по датам'; }
    });
    historyFxControls.append(historyFxStatus, historyFxButton); content.append(historyFxControls);
    for (const issue of metadata.fxErrors || []) content.append(el('p', 'conversion-warning', `${issue.date ? calendarDate(issue.date) + ': ' : ''}${issue.message || 'Не удалось загрузить точный курс НБУ.'}`));
    const wrap = el('div', 'history-table-wrap'); const table = el('table', 'history-table'); table.append(el('caption', '', 'Сохранённые наблюдения')); const thead = el('thead'); const header = el('tr'); for (const label of ['Дата наблюдения', 'Статус', 'Исходная цена', 'Цена в UAH / курс на дату']) { const th = el('th', '', label); th.scope = 'col'; header.append(th); } thead.append(header); table.append(thead); const tbody = el('tbody');
    const sorted = [...history].sort((a, b) => new Date(b.observedAt) - new Date(a.observedAt)); for (const record of sorted) {
      const tr = el('tr'); tr.append(el('td', '', date(record.observedAt, true)), el('td', '', statusLabel(record.status)), el('td', '', `${amount(record.price)} ${record.currency || ''}`));
      const converted = el('td'); if (number(record.priceUAH) !== null) { converted.append(document.createTextNode(`${amount(record.priceUAH)} UAH`)); const details = [record.fxDate ? calendarDate(record.fxDate) : '', number(record.fxRate) !== null ? `1 ${record.currency || ''} = ${rateAmount(record.fxRate)} UAH` : ''].filter(Boolean).join(' · '); if (details) converted.append(el('small', 'history-rate', details)); } else converted.append(el('span', 'muted', 'Курс не загружен')); tr.append(converted); tbody.append(tr);
    } if (!sorted.length) { const tr = el('tr'); const td = el('td', '', 'Наблюдения пока отсутствуют.'); td.colSpan = 4; tr.append(td); tbody.append(tr); } table.append(tbody); wrap.append(table); content.append(wrap, el('p', 'data-note', 'График отражает исходные сохранённые цены. UAH в таблице рассчитывается по НБУ на дату каждого наблюдения, а не по дате сравнения актуальных предложений. Между проверками цена могла меняться; факт продажи не подтверждён.')); renderInsights(content, item); renderDetailExtras(content, item);
  }

  function renderInsights(content, item) {
    const section = el('section', 'insights-section'); section.append(el('h3', '', 'Свежесть и похожие предложения'), el('p', 'insights-message', 'Загружаем доступные показатели…')); content.append(section);
    api(`/api/listings/${encodeURIComponent(item.id)}/insights`).then(result => {
      if (!section.isConnected) return; section.replaceChildren(el('h3', '', 'Свежесть и похожие предложения'));
      const freshness = result.freshness || {}; if (typeof freshness.stale === 'boolean') section.append(el('p', 'insights-message', `${freshness.stale ? 'Давно не проверялось: актуальность нужно уточнить.' : 'Признака устаревания по последним наблюдениям нет.'}${number(freshness.ageDays) !== null ? ` Возраст последней проверки: ${amount(freshness.ageDays)} дн.` : ''}${freshness.lastChecked ? ` Последняя проверка: ${date(freshness.lastChecked, true)}.` : ''}`));
      const comparison = result.comparables || {}; const currency = typeof comparison.currency === 'string' ? comparison.currency : ''; const count = number(comparison.count);
      if (count !== null && count > 0 && number(comparison.medianPricePerM2) !== null && currency) { const grid = el('div', 'insights-grid'); for (const [label, value, suffix] of [['Похожих предложений', count, ''], ['Медиана цены за м²', comparison.medianPricePerM2, `${currency}/м²`], ['Этот объект за м²', comparison.targetPricePerM2, `${currency}/м²`]]) { const stat = el('article', 'insight-stat'); stat.append(el('small', '', label), el('strong', '', number(value) === null ? '—' : amount(value))); if (suffix) stat.append(el('span', '', suffix)); grid.append(stat); } section.append(grid); if (number(comparison.differencePercent) !== null) section.append(el('p', 'insights-message', `Отклонение цены за м² от медианы: ${Number(comparison.differencePercent) > 0 ? '+' : ''}${amount(comparison.differencePercent)}%.`)); if (comparison.methodLabel) section.append(el('p', 'insights-message', comparison.methodLabel)); section.append(el('p', 'insights-message', 'Сравниваются запрашиваемые цены в одной валюте. Это не оценка стоимости сделки.')); if (Array.isArray(comparison.items) && comparison.items.length) { const details = el('details', 'help-details'); details.append(el('summary', '', 'Посмотреть объявления для сравнения')); const list = el('div', 'duplicate-list'); for (const comparable of comparison.items) if (comparable) list.append(duplicateItem(comparable, false)); details.append(list); section.append(details); } }
      else section.append(el('p', 'insights-message', 'Недостаточно сопоставимых объявлений для медианы цены за м². Новые данные могут дополнить сравнение.'));
      section.append(el('h4', '', 'Возможные повторные публикации')); const relistings = Array.isArray(result.possibleRelistings) ? result.possibleRelistings : [];
      if (!relistings.length) section.append(el('p', 'insights-message', 'Возможные повторные публикации в сохранённых данных не найдены.'));
      for (const relisting of relistings) { const row = el('article', 'relisting-item'); row.append(el('strong', '', `Возможная перепубликация · ID ${relisting.id}`)); if (relisting.reason) row.append(el('p', '', relisting.reason)); const button = el('button', 'text-button', 'Посмотреть историю объявления →'); button.type = 'button'; button.addEventListener('click', () => openDetails(relisting.id)); row.append(button); section.append(row); }
    }).catch(error => { if (section.isConnected) section.replaceChildren(el('h3', '', 'Свежесть и похожие предложения'), el('p', 'insights-message', `Показатели недоступны: ${error.message}`)); });
  }

  function renderDetailExtras(content, item) {
    const notes = el('section', 'note-section'); notes.append(el('h3', '', 'Личная заметка')); const input = el('textarea'); input.rows = 3; input.maxLength = 5000; input.placeholder = 'Что важно об этом объекте?'; input.setAttribute('aria-label', 'Личная заметка к объявлению'); input.value = typeof state.noteDrafts[String(item.id)] === 'string' ? state.noteDrafts[String(item.id)] : typeof state.preferences.notes[String(item.id)] === 'string' ? state.preferences.notes[String(item.id)] : ''; input.addEventListener('input', () => { state.noteDrafts[String(item.id)] = input.value; }); const button = el('button', 'button secondary', 'Сохранить заметку'); button.type = 'button'; const status = el('p', 'muted'); status.setAttribute('role', 'status');
    button.addEventListener('click', async () => { button.disabled = true; status.textContent = 'Сохраняем…'; try { applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify({ note: { id: item.id, text: input.value } }) })); delete state.noteDrafts[String(item.id)]; status.textContent = 'Заметка сохранена.'; } catch (error) { status.textContent = error.message; } finally { button.disabled = false; } }); notes.append(input, button, status); content.append(notes);
    const map = el('section', 'map-section'); map.append(el('h3', '', 'Объект на карте')); const lat = number(item.latitude); const lon = number(item.longitude);
    if (lat === null || lon === null || lat < -90 || lat > 90 || lon < -180 || lon > 180) { map.append(el('p', 'muted', 'Координаты не предоставлены. Расположение по адресу не угадывается.')); content.append(map); return; }
    const explanation = el('p', 'muted', 'Карта OSM загрузится при открытии; координаты передаются сервису OpenStreetMap.'); const mapButton = el('button', 'button secondary', 'Показать карту OpenStreetMap'); mapButton.type = 'button';
    mapButton.addEventListener('click', () => {
      const bbox = [Math.max(-180, lon - .01), Math.max(-90, lat - .01), Math.min(180, lon + .01), Math.min(90, lat + .01)].map(value => value.toFixed(6)).join(',');
      const url = new URL('https://www.openstreetmap.org/export/embed.html'); url.search = new URLSearchParams({ bbox, layer: 'mapnik', marker: `${lat.toFixed(6)},${lon.toFixed(6)}` }).toString();
      const frame = el('iframe', 'map-frame'); frame.title = 'Расположение объекта на OpenStreetMap'; frame.referrerPolicy = 'no-referrer'; frame.loading = 'lazy'; frame.setAttribute('sandbox', 'allow-scripts allow-same-origin'); frame.src = url.href; map.append(frame); mapButton.disabled = true; mapButton.textContent = 'Карта открыта';
    }, { once: true }); map.append(explanation, mapButton); content.append(map);
  }

  function renderPriceWatch(content, item) {
    const section = el('section', 'price-watch-section'); section.append(el('h3', '', 'Контроль желаемой цены'));
    const watch = watchFor(item.id); const status = state.watchStatus; const check = status?.checks?.[String(item.id)];
    section.append(el('p', '', watch?.active ? `Порог: не выше ${amount(watch.targetPrice)} ${watch.currency || ''}. Отслеживание включено.` : watch ? `Отслеживание выключено. Сохранённый порог: ${amount(watch.targetPrice)} ${watch.currency || ''}.` : 'Задайте желаемую цену, чтобы получать событие при достижении порога.'));
    section.append(el('p', '', check?.lastChecked ? `Последняя проверка: ${date(check.lastChecked, true)}.` : 'Для этого объекта проверки ещё не зафиксированы.'));
    if (check?.lastError) section.append(el('p', 'watch-error', `Ошибка проверки: ${check.lastError}`));
    const scheduling = status?.running ? 'Проверка выполняется.' : number(status?.intervalMinutes) !== null ? `Интервал проверки: ${amount(status.intervalMinutes)} мин.${status.nextRun ? ` Следующий запуск: ${date(status.nextRun, true)}.` : ''}` : 'Проверки работают, пока запущен локальный сервер.'; section.append(el('p', '', scheduling));
    const actions = el('div', 'price-watch-actions'); const edit = el('button', 'button primary', watch ? 'Изменить порог' : 'Следить за ценой'); edit.type = 'button'; edit.addEventListener('click', () => openTarget(item)); const checkButton = el('button', 'button secondary', 'Проверить цену сейчас'); checkButton.type = 'button'; checkButton.disabled = !watch?.active || !safeUrl(item.url) || !!status?.running;
    const resultStatus = el('p'); resultStatus.setAttribute('role', 'status');
    checkButton.addEventListener('click', async () => { checkButton.disabled = true; resultStatus.textContent = 'Проверяем доступную страницу объявления…'; try { await api('/api/watch/check', { method: 'POST', body: JSON.stringify({ id: item.id }) }); await Promise.allSettled([loadWatchStatus(), loadPreferences()]); if (String(state.detailId) === String(item.id) && $('#detail-dialog').open) await openDetails(state.detailId); await loadListings(); showToast('Запрос проверки обработан. Результат — в истории и уведомлениях.'); } catch (error) { resultStatus.textContent = error.message; checkButton.disabled = false; } }); actions.append(edit, checkButton); section.append(actions, resultStatus); if (!safeUrl(item.url)) section.append(el('p', '', 'Для автоматической проверки нужна исходная ссылка. Без неё цена меняется только при добавлении новых наблюдений.')); content.append(section);
  }

  function statusLabel(status) { return ({ active: 'Активно', removed: 'Снято', inactive: 'Неактивно', sold: 'Продано (по источнику)', unavailable: 'Недоступно', unknown: 'Не указан' })[status] || status || 'Наблюдение'; }
  function sellerLabel(type) { return ({ owner: 'Собственник', developer: 'Застройщик', agent: 'Риелтор' })[type] || 'Продавец не указан'; }
  function drawChart(container, history, currency) {
    container.replaceChildren(); const points = history.filter(record => record.currency === currency && number(record.price) !== null && !Number.isNaN(new Date(record.observedAt).getTime())).sort((a, b) => new Date(a.observedAt) - new Date(b.observedAt));
    if (points.length < 2) { container.append(el('p', 'chart-message', points.length ? `Сохранена одна цена: ${amount(points[0].price)} ${currency || ''}. График появится после следующего наблюдения в этой валюте.` : 'Недостаточно наблюдений для графика.')); return; }
    const ns = 'http://www.w3.org/2000/svg'; const svg = document.createElementNS(ns, 'svg'); svg.setAttribute('viewBox', '0 0 680 240'); svg.setAttribute('class', 'price-chart'); svg.setAttribute('role', 'img'); const title = document.createElementNS(ns, 'title'); title.textContent = `История цены в ${currency}: ${points.length} наблюдений. Все значения доступны в таблице ниже.`; svg.append(title);
    const values = points.map(point => Number(point.price)); const min = Math.min(...values); const max = Math.max(...values); const padding = max === min ? Math.max(Math.abs(max) * .05, 1) : (max - min) * .2; const low = Math.max(0, min - padding); const high = max + padding; const t0 = new Date(points[0].observedAt).getTime(); const t1 = new Date(points.at(-1).observedAt).getTime();
    const coordinates = points.map((point, index) => ({ x: 92 + (t1 === t0 ? index / (points.length - 1) : (new Date(point.observedAt).getTime() - t0) / (t1 - t0)) * 560, y: 185 - ((Number(point.price) - low) / (high - low || 1)) * 155, point }));
    const node = (name, attrs, text) => { const n = document.createElementNS(ns, name); for (const [key, value] of Object.entries(attrs)) n.setAttribute(key, value); if (text !== undefined) n.textContent = text; svg.append(n); return n; };
    for (let i = 0; i < 4; i++) { const y = 30 + i * (155 / 3); node('line', { x1: 92, x2: 652, y1: y, y2: y, stroke: '#e6eded', 'stroke-dasharray': '4 5' }); node('text', { x: 80, y: y + 4, 'text-anchor': 'end', fill: '#7b8e99', 'font-size': '10' }, amount(high - i * ((high - low) / 3))); }
    node('polyline', { points: coordinates.map(point => `${point.x},${point.y}`).join(' '), fill: 'none', stroke: '#087e78', 'stroke-width': '2.5', 'stroke-linejoin': 'round', 'stroke-linecap': 'round' });
    for (const p of coordinates) { const dot = node('circle', { cx: p.x, cy: p.y, r: 4, fill: '#ffffff', stroke: '#087e78', 'stroke-width': 2 }); const label = document.createElementNS(ns, 'title'); label.textContent = `${date(p.point.observedAt, true)}: ${amount(p.point.price)} ${currency}`; dot.append(label); }
    node('text', { x: 92, y: 215, fill: '#7b8e99', 'font-size': '10' }, date(points[0].observedAt)); node('text', { x: 652, y: 215, 'text-anchor': 'end', fill: '#7b8e99', 'font-size': '10' }, date(points.at(-1).observedAt)); container.append(svg);
  }

  for (const button of $$('[data-close]')) button.addEventListener('click', () => button.closest('dialog').close());
  $('#add-button').addEventListener('click', () => openAdd()); $('#empty-add').addEventListener('click', () => openAdd());
  $('#summary-button').addEventListener('click', () => { openDialog('#summary-dialog'); loadSummary(); });
  $('#summary-load').addEventListener('click', () => { const day = $('#summary-date').value; if (/^\d{4}-\d{2}-\d{2}$/.test(day)) loadSummary(day); else $('#summary-content').replaceChildren(el('p', 'form-error', 'Выберите дату сводки.')); });
  $('#merge-form').addEventListener('submit', event => { event.preventDefault(); const form = event.currentTarget; submitForm(form, async () => { const parts = String(new FormData(form).get('ids') || '').split(/[,;\s]+/).filter(Boolean); const ids = [...new Set(parts.map(Number))]; if (ids.length < 2 || ids.length > 50 || ids.some(id => !Number.isInteger(id) || id < 1)) throw new Error('Укажите от 2 до 50 различных положительных числовых ID.'); applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify({ sameObjects: ids }) })); form.reset(); await loadListings(); await openDuplicates(); showToast('Совпадение подтверждено. Исходные истории сохранены.'); }); });
  $('#duplicates-button').addEventListener('click', openDuplicates);
  $('#notifications-button').addEventListener('click', async () => { $('#notifications-content').replaceChildren(el('p', 'loading', 'Загружаем события…')); openDialog('#notifications-dialog'); try { await loadPreferences(); renderNotifications(); } catch (error) { $('#notifications-content').replaceChildren(el('p', 'form-error', error.message)); } });
  $('#notifications-read').addEventListener('click', async () => { const button = $('#notifications-read'); button.disabled = true; try { applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify({ markAllRead: true }) })); renderNotifications(); } catch (error) { $('#notifications-content').prepend(el('p', 'form-error', error.message)); button.disabled = false; } });
  $('#watch-button').addEventListener('click', () => { try { collectFilters(); const names = { city: 'Город', region: 'Область', district: 'Район', kind: 'Тип', sellerType: 'Продавец', rooms: 'Комнаты', priceMin: 'Цена от', priceMax: 'Цена до', areaMin: 'Площадь от', areaMax: 'Площадь до', floorMin: 'Этаж от', floorMax: 'Этаж до', currency: 'Валюта', q: 'Поиск', favorites: 'Избранное', changes: 'Изменения' }; const values = { apartment: 'квартира', house: 'дом', owner: 'собственник', developer: 'застройщик', agent: 'риелтор', unknown: 'неизвестно', all: 'все', down: 'снижение', up: 'повышение', changed: 'любые', true: 'да' }; $('#watch-summary').textContent = [...state.filters.entries()].filter(([key]) => names[key]).map(([key, value]) => `${names[key]}: ${values[value] || value}`).join(' · ') || 'Все квартиры и дома в продаже.'; formError($('#watch-form'), ''); openDialog('#watch-dialog'); } catch (error) { showNotice(error.message, true); } });
  $('#watch-form').addEventListener('submit', event => { event.preventDefault(); const form = event.currentTarget; submitForm(form, async () => { const name = String(new FormData(form).get('name') || '').trim(); if (!name) throw new Error('Введите название подборки.'); applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify({ saveSearch: { name, filters: Object.fromEntries(state.filters) } }) })); $('#watch-dialog').close(); form.reset(); showToast('Подборка сохранена. События будут доступны во внутренних уведомлениях.'); }); });
  $('#target-form').addEventListener('submit', event => { event.preventDefault(); const form = event.currentTarget; submitForm(form, async () => { const item = state.watchItem; if (!item) throw new Error('Сначала выберите объявление.'); const fd = new FormData(form); const targetPrice = Number(fd.get('targetPrice')); const currency = String(fd.get('currency') || ''); if (!Number.isFinite(targetPrice) || targetPrice < 0) throw new Error('Введите неотрицательную желаемую цену.'); if (!['USD', 'UAH', 'EUR'].includes(currency)) throw new Error('Выберите USD, UAH или EUR.'); const active = fd.has('active'); applyPreferences(await api('/api/preferences', { method: 'POST', body: JSON.stringify({ watch: { id: item.id, active, targetPrice, currency } }) })); $('#target-dialog').close(); await refreshAll(); if ($('#detail-dialog').open && String(state.detailId) === String(item.id)) await openDetails(state.detailId); showToast(active ? 'Порог сохранён. Проверки работают, пока запущен сервер.' : 'Отслеживание цены выключено.'); }); });
  $('#fx-refresh-button').addEventListener('click', async () => { const button = $('#fx-refresh-button'); button.disabled = true; try { const selectedDate = $('#fx-date').value; if (!/^\d{4}-\d{2}-\d{2}$/.test(selectedDate)) { $('#fx-date').focus(); throw new Error('Выберите дату официального курса НБУ.'); } collectFilters(); const result = await api('/api/fx/refresh', { method: 'POST', body: JSON.stringify({ date: selectedDate }) }); if (result.rates || result.requestedDate) renderFx(result); else await loadFx(); await loadListings(); showToast(`Курс НБУ на ${calendarDate(selectedDate)} загружен.`); } catch (error) { showNotice(error.message, true); await loadListings(); } finally { button.disabled = false; } });
  $('#fx-date').addEventListener('change', async () => { try { collectFilters(); await loadListings(); } catch (error) { showNotice(error.message, true); } });
  $('#import-button').addEventListener('click', () => openDialog('#import-dialog')); $('#empty-import').addEventListener('click', () => openDialog('#import-dialog'));
  $('#sources-button').addEventListener('click', async () => { try { await loadStatus(); } catch (error) { showNotice(error.message, true); } populateSources(); openDialog('#sources-dialog'); });
  $('#source-key').addEventListener('input', () => { const enabled = !!state.status?.apiKeyConfigured || !!$('#source-key').value.trim(); $('#load-regions-button').disabled = !enabled; $('#load-cities-button').disabled = !enabled; });
  $('#load-regions-button').addEventListener('click', async () => {
    const button = $('#load-regions-button'); button.disabled = true; formError($('#sources-form'), '');
    try { await ensureSourceKey(); const result = await api('/api/ria/regions'); state.riaRegions = Array.isArray(result.regions) ? result.regions : []; directoryOptions($('#source-region-select'), state.riaRegions, 'Выберите область'); const saved = $('#source-stateId').value; if (state.riaRegions.some(item => String(item.id) === saved)) $('#source-region-select').value = saved; showToast(state.riaRegions.length ? 'Справочник областей загружен.' : 'API вернул пустой справочник областей.'); }
    catch (error) { formError($('#sources-form'), error.message); }
    finally { button.disabled = !state.status?.apiKeyConfigured && !$('#source-key').value.trim(); }
  });
  $('#source-region-select').addEventListener('change', event => {
    const selected = state.riaRegions.find(item => String(item.id) === event.target.value); if (!selected) return;
    $('#source-stateId').value = selected.id; $('#source-region').value = selected.name; $('#source-cityId').value = ''; $('#source-city').value = ''; state.riaCities = []; directoryOptions($('#source-city-select'), [], 'Загрузите города выбранной области');
  });
  $('#load-cities-button').addEventListener('click', async () => {
    const button = $('#load-cities-button'); button.disabled = true; formError($('#sources-form'), '');
    try { await ensureSourceKey(); const stateId = Number($('#source-stateId').value); if (!Number.isInteger(stateId) || stateId < 1) throw new Error('Выберите область или укажите её числовой ID.'); const result = await api(`/api/ria/cities?${new URLSearchParams({ stateId: String(stateId) })}`); state.riaCities = Array.isArray(result.cities) ? result.cities : []; directoryOptions($('#source-city-select'), state.riaCities, 'Выберите город'); const saved = $('#source-cityId').value; if (state.riaCities.some(item => String(item.id) === saved)) $('#source-city-select').value = saved; showToast(state.riaCities.length ? 'Города области загружены.' : 'API вернул пустой справочник городов.'); }
    catch (error) { formError($('#sources-form'), error.message); }
    finally { button.disabled = !state.status?.apiKeyConfigured && !$('#source-key').value.trim(); }
  });
  $('#source-city-select').addEventListener('change', event => { const selected = state.riaCities.find(item => String(item.id) === event.target.value); if (!selected) return; $('#source-cityId').value = selected.id; $('#source-city').value = selected.name; if (selected.stateId) $('#source-stateId').value = selected.stateId; });
  $('#filter-kind').addEventListener('change', event => { $('#floors-legend').textContent = event.target.value === 'house' ? 'Этажность дома' : 'Этажность здания'; });
  $('#filter-city').addEventListener('change', event => { const matches = state.cities.filter(item => item.city.toLocaleLowerCase('uk') === event.target.value.trim().toLocaleLowerCase('uk')); const region = $('#filter-region'); if (matches.length === 1 && matches[0].region && (!region.value.trim() || region.value === state.autoRegion)) { region.value = matches[0].region; state.autoRegion = matches[0].region; } else if (!event.target.value.trim() && region.value === state.autoRegion) { region.value = ''; state.autoRegion = ''; } });
  $('#filter-form').addEventListener('submit', event => { event.preventDefault(); searchInternet(); });
  $('#cached-search-button').addEventListener('click', async () => { try { collectFilters(); showNotice(''); await loadListings(); } catch (error) { showNotice(error.message, true); } });
  $('#empty-online').addEventListener('click', () => $('#filter-form').requestSubmit());
  $('#load-more-online').addEventListener('click', () => searchInternet('more'));
  $('#filter-city').addEventListener('change', () => { const city = catalogCity($('#filter-city').value); if (city) $('#filter-region').value = city.regionName; loadCatalog(true).then(renderOnlineResult); });
  $('#filter-kind').addEventListener('change', renderOnlineResult);
  $('#filter-currency').addEventListener('change', async () => { try { collectFilters(); showNotice(''); await loadListings(); } catch (error) { showNotice(error.message, true); } });
  $('#show-city-results').addEventListener('click', () => { state.resetCity = { city: state.onlineResult.search.cityName, region: state.onlineResult.search.regionName, kind: $('#filter-kind').value }; $('#filter-form').reset(); });
  $('#filter-form').addEventListener('reset', () => { $('#search-input').value = ''; $('#changes-select').value = 'all'; $('#sort-select').value = 'updated'; setTimeout(() => { if (state.resetCity) { for (const [key,value] of Object.entries(state.resetCity)) $('#filter-form').elements[key].value = value; state.resetCity = null; } $('#floors-legend').textContent = 'Этажность здания'; collectFilters(); showNotice(''); loadListings(); }, 0); });
  for (const id of ['changes-select', 'sort-select']) $(`#${id}`).addEventListener('change', () => { try { collectFilters(); loadListings(); } catch (error) { showNotice(error.message, true); } });
  $('#search-input').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); try { collectFilters(); loadListings(); } catch (error) { showNotice(error.message, true); } } });
  $('#search-input').addEventListener('search', () => { try { collectFilters(); loadListings(); } catch (error) { showNotice(error.message, true); } });
  $('#refresh-button').addEventListener('click', async () => { $('#refresh-button').disabled = true; showNotice(''); try { await refreshAll(); } finally { $('#refresh-button').disabled = false; } });
  $('#previous-page').addEventListener('click', () => { if (state.page > 1) { state.page--; loadListings(); $('.results').scrollIntoView({ block: 'start' }); } }); $('#next-page').addEventListener('click', () => { if (state.page * state.pageSize < state.total) { state.page++; loadListings(); $('.results').scrollIntoView({ block: 'start' }); } });

  $('#add-form').addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    submitForm(form, async () => {
      const fd = new FormData(form); const editing = state.editRecord; const record = editing ? { ...editing, deal: 'sale' } : { source: 'manual', deal: 'sale' };
      if (!editing) {
        for (const name of ['url', 'externalId', 'title', 'city', 'region', 'district', 'kind', 'sellerType', 'currency', 'address', 'sellerName', 'contactPhone', 'contactEmail', 'contactUrl']) { const value = String(fd.get(name) || '').trim(); if (value) record[name] = value; }
        for (const name of ['area', 'rooms', 'floor', 'floorsTotal', 'latitude', 'longitude']) { const value = fd.get(name); if (value !== '' && value !== null) record[name] = Number(value); }
      }
      record.price = Number(fd.get('price'));
      if (!Number.isFinite(record.price) || record.price < 0) throw new Error('Введите неотрицательную цену.');
      if (!record.url && !record.externalId) throw new Error('Укажите ссылку или постоянный ID объявления.');
      if (record.url && !safeUrl(record.url)) throw new Error('Нужна ссылка HTTP или HTTPS без логина и пароля.');
      if (record.contactUrl && !safeUrl(record.contactUrl)) throw new Error('Контактная ссылка должна использовать HTTP или HTTPS.');
      delete record.observedAt; const observed = fd.get('observedAt'); if (observed) record.observedAt = new Date(observed).toISOString();
      const result = await api('/api/import', { method: 'POST', body: JSON.stringify({ records: [record] }) }); $('#add-dialog').close(); form.reset(); state.editRecord = null; showToast(ingestionMessage(result)); state.page = 1; await refreshAll();
      if (editing && $('#detail-dialog').open && String(state.detailId) === String(editing.id)) await openDetails(state.detailId);
    });
  });

  $('#import-file').addEventListener('change', async event => { const file = event.target.files[0]; if (!file) return; formError($('#import-form'), ''); try { if (file.size > 16 * 1024 * 1024) throw new Error('Файл больше 16 МБ. Разделите его на несколько частей.'); $('#import-text').value = await file.text(); $('#import-format').value = file.name.toLowerCase().endsWith('.csv') ? 'csv' : 'json'; $('#import-file-label').textContent = `${file.name} · ${formatter.format(file.size / 1024)} КБ`; } catch (error) { $('#import-file-label').textContent = ''; $('#import-text').value = ''; formError($('#import-form'), error.message); } });
  $('#import-form').addEventListener('submit', event => { event.preventDefault(); const form = event.currentTarget; submitForm(form, async () => { const text = $('#import-text').value.trim().replace(/^\uFEFF/, ''); if (!text) throw new Error('Выберите файл или вставьте данные.'); if (new Blob([text]).size > 16 * 1024 * 1024) throw new Error('Объём данных больше 16 МБ.'); let payload; if ($('#import-format').value === 'csv') payload = { csv: text, source: 'import' }; else { try { payload = JSON.parse(text); } catch { throw new Error('Не удалось прочитать JSON. Проверьте кавычки, запятые и скобки.'); } if (Array.isArray(payload)) payload = { records: payload }; if (!payload || typeof payload !== 'object') throw new Error('Ожидается массив объявлений, объект records или резервная копия.'); } const result = await api('/api/import', { method: 'POST', body: JSON.stringify(payload) }); $('#import-dialog').close(); showToast(ingestionMessage(result)); state.page = 1; await refreshAll(); }); });

  $('#export-button').addEventListener('click', async () => { const button = $('#export-button'); button.disabled = true; try { const result = await api('/api/export'); const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: 'application/json;charset=utf-8' })); const a = el('a'); a.href = url; a.download = `domtrack-ua-${new Date().toISOString().slice(0, 10)}.json`; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); showToast('Резервная копия подготовлена.'); } catch (error) { showNotice(error.message, true); } finally { button.disabled = false; } });
  $('#sources-form').addEventListener('submit', event => { event.preventDefault(); const form = event.currentTarget; submitForm(form, async () => { const fd = new FormData(form); const collector = {}; for (const key of ['stateId', 'cityId']) { const value = String(fd.get(key) || '').trim(); collector[key] = value ? Number(value) : null; } for (const key of ['cityName', 'regionName', 'kind']) collector[key] = String(fd.get(key) || '').trim(); collector.intervalMinutes = Number(fd.get('intervalMinutes')); collector.maxListings = Number(fd.get('maxListings')); collector.monthlyBudget = Number(fd.get('monthlyBudget')); collector.enabled = fd.has('enabled'); const payload = { collector }; const apiKey = String(fd.get('apiKey') || '').trim(); if (apiKey) payload.apiKey = apiKey; await api('/api/settings', { method: 'POST', body: JSON.stringify(payload) }); form.elements.apiKey.value = ''; await loadStatus(); $('#sources-dialog').close(); showToast('Настройки источника сохранены.'); }); });
  $('#collect-button').addEventListener('click', async () => { const button = $('#collect-button'); button.disabled = true; formError($('#sources-form'), ''); try { const result = await api('/api/collect', { method: 'POST', body: JSON.stringify({}) }); showToast(result.message || ingestionMessage(result)); await refreshAll(); } catch (error) { formError($('#sources-form'), error.message); } finally { try { await loadStatus(); } catch { button.disabled = false; } } });
  $('#web-scan-form').addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    submitForm(form, async () => {
      const fd = new FormData(form); const urls = [...new Set(String(fd.get('urls') || '').split(/\r?\n/).map(value => value.trim()).filter(Boolean))];
      if (!urls.length || urls.length > 5) throw new Error('Укажите от 1 до 5 начальных ссылок, каждую с новой строки.');
      if (urls.some(url => !safeUrl(url))) throw new Error('Используйте полные ссылки HTTP или HTTPS без логина и пароля.');
      const cityName = String(fd.get('cityName') || '').trim(); const regionName = String(fd.get('regionName') || '').trim(); if (!cityName || !regionName) throw new Error('Укажите город и область для загружаемых объявлений.');
      const maxPages = Number(fd.get('maxPages')); if (!Number.isInteger(maxPages) || maxPages < 1 || maxPages > 20) throw new Error('Лимит страниц должен быть от 1 до 20.');
      $('#web-scan-result').textContent = 'Загружаем открытые страницы. Это может занять некоторое время…';
      try {
        const result = await api('/api/web/scan', { method: 'POST', body: JSON.stringify({ urls, cityName, regionName, maxPages, followLinks: fd.has('followLinks') }) });
        const lines = []; if (number(result.pagesVisited) !== null) lines.push(`Проверено страниц: ${integerFormatter.format(Number(result.pagesVisited))}`); if (number(result.recordsImported) !== null) lines.push(`Импортировано записей: ${integerFormatter.format(Number(result.recordsImported))}`);
        const errors = Array.isArray(result.errors) ? result.errors : [];
        for (const error of errors.slice(0, 5)) { if (typeof error === 'string') lines.push(error); else if (error && typeof error === 'object') lines.push([error.url, error.error || error.message].filter(value => typeof value === 'string').join(' — ') || 'Одну из страниц не удалось обработать.'); }
        if (errors.length > 5) lines.push(`Ещё ошибок: ${errors.length - 5}.`);
        if (Number(result.recordsImported) === 0) lines.push('Подходящие структурированные объявления о продаже не найдены. Попробуйте прямые ссылки на объекты или импорт файла.');
        $('#web-scan-result').textContent = lines.join(' · ') || 'Проверка завершена.'; state.page = 1; await refreshAll();
      } catch (error) { $('#web-scan-result').textContent = ''; throw error; }
    });
  });
  $('#demo-button').addEventListener('click', async () => { const button = $('#demo-button'); button.disabled = true; try { const result = await api('/api/demo', { method: 'POST', body: JSON.stringify({}) }); showToast(`Учебные данные добавлены. ${ingestionMessage(result)}`); state.page = 1; await refreshAll(); } catch (error) { showNotice(error.message, true); } finally { button.disabled = false; } });

  collectFilters(); refreshAll();
  setInterval(() => { if (!document.hidden && !$('#sources-dialog').open) Promise.allSettled([loadStatus(), loadPreferences(), loadWatchStatus()]); }, 60000);
})();

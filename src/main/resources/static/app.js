'use strict';
const $ = id => document.getElementById(id);
let busy = false, timer = null, controller = null, closed = false, latest = null;
let stream = null, queueConnected = false;
const units = {power:'Вт', voltage:'В', current:'А', energy:'кВт·ч'};
const dt = value => value ? new Date(value).toLocaleString('ru-RU', {dateStyle:'short',timeStyle:'medium'}) : 'API не предоставил';
function setNotice(message, kind = '') { $('notice').textContent = message; $('notice').className = `notice ${kind}`; }
function allowCommands() {
  const s = latest?.snapshot, r = s?.reading;
  return !!(r?.writable && r.relayCode && r.relay !== null && s.online !== false && latest.cloud === 'connected' && !latest.stale);
}
function buttons() {
  $('refresh').disabled = busy;
  $('refresh').textContent = busy ? 'Выполняется…' : '↻ Обновить';
  $('turn-on').disabled = $('turn-off').disabled = busy || !allowCommands();
}
function render(state) {
  latest = state;
  $('family').textContent = state.family;
  const cloudNames = {connected:'Подключено', error:'Ошибка запроса', not_requested:'Не проверено', not_configured:'Нужна настройка'};
  $('cloud').textContent = cloudNames[state.cloud] || 'Нет данных';
  $('cloud-dot').className = 'status-dot ' + (state.cloud === 'connected' ? 'good' : state.error ? 'bad' : '');
  if (state.error) setNotice(`${state.error.message}${state.stale ? ' Отображаются последние полученные данные.' : ''}`, 'error');
  else if (!state.configured) setNotice('Заполните TUYA_CLIENT_SECRET и остальные параметры в tuya.local.properties, затем перезапустите приложение.', 'error');
  else if (state.snapshot) setNotice(state.snapshot.online === false ? 'Облако доступно. Розетка offline; показания в облаке могут быть устаревшими.' : 'Последние данные получены из Tuya Cloud. Управление доступно при однозначно определённом реле.', state.snapshot.online === false ? 'error' : 'success');
  const s = state.snapshot;
  if (s) {
    $('device-name').textContent = s.name;
    $('online').textContent = s.online === true ? 'В сети · online' : s.online === false ? 'Не в сети · offline' : 'Нет данных';
    $('online-dot').className = 'status-dot ' + (s.online === true ? 'good' : s.online === false ? 'bad' : '');
    $('relay').textContent = s.reading.relay === true ? 'Включена' : s.reading.relay === false ? 'Выключена' : 'Нет данных';
    $('relay-icon').className = 'relay-icon ' + (s.reading.relay === true ? 'on' : '');
    $('received').textContent = dt(s.receivedAt) + (s.source === 'message_queue' ? ' · событие Tuya' : ' · REST') + (state.stale ? ' · последнее чтение REST не удалось' : '') + (s.online === false ? ' · сохранённые показания облака' : '');
    for (const [key, metric] of Object.entries(s.reading.measurements)) {
      const present = metric.value !== null;
      const value = present ? metric.value.replace('.', ',') : 'Нет данных';
      if (key === 'power') {
        $('power').textContent = value; $('power-unit').hidden = !present;
        $('power').parentElement.classList.toggle('empty', !present);
        $('power-note').textContent = `${metric.code} · ${metric.note}`;
        $('power-reported').textContent = dt(metric.reportedAt);
      } else {
        $(key).textContent = present ? `${value} ${units[key]}` : value;
        $(key).title = `${metric.code} · ${metric.note}`;
        $(`${key}-time`).textContent = metric.reportedAt ? `Отчёт: ${dt(metric.reportedAt)}` : 'Время отчёта не предоставлено';
      }
    }
    $('raw-status').textContent = JSON.stringify(s.rawStatus, null, 2);
    $('raw-spec').textContent = JSON.stringify(s.rawSpecification, null, 2);
    $('warnings').replaceChildren();
    if (s.reading.warnings.length) {
      const list = document.createElement('ul');
      for (const warning of s.reading.warnings) { const li = document.createElement('li'); li.textContent = warning; list.append(li); }
      $('warnings').append(list);
    }
  }
  if (/QUOTA|TUYA_2884/.test(state.error?.code || '')) $('auto').checked = false;
  buttons();
}
function intervalMs() {
  const n = Number($('interval').value);
  const seconds = Number.isFinite(n) ? Math.max(30, Math.min(3600, Math.round(n))) : 60;
  $('interval').value = seconds;
  return seconds * 1000;
}
function schedule() {
  clearTimeout(timer); timer = null;
  if ($('auto').checked && !queueConnected && !closed && !document.hidden && !busy) timer = setTimeout(() => request('/api/refresh'), intervalMs());
}
function renderQueue(queue) {
  queueConnected = queue.enabled && queue.connection === 'connected';
  const names = {disabled:'Выключены', connecting:'Подключение…', connected:'Подписка подключена', reconnecting:'Переподключение…', error:'Ошибка подключения'};
  $('event-state').textContent = names[queue.connection] || 'Нет данных';
  $('event-dot').className = 'status-dot ' + (queueConnected ? 'good' : queue.enabled ? 'bad' : '');
  $('event-message').textContent = queue.message + (queue.rejected ? ` Не применено сообщений: ${queue.rejected}.` : '');
  $('event-count').textContent = `${queue.received} · применено ${queue.applied}`;
  $('event-time').textContent = queue.lastDeviceEventAt ? dt(queue.lastDeviceEventAt) : 'Ещё не получено';
  schedule();
}
function connectStream() {
  if (closed || document.hidden || stream) return;
  stream = new EventSource('/api/events');
  stream.onopen = () => { $('page-stream').textContent = 'Связь со страницей: подключена'; };
  stream.addEventListener('snapshot', event => {
    try { const update = JSON.parse(event.data); render(update.state); renderQueue(update.queue); }
    catch (_) { $('page-stream').textContent = 'Не удалось прочитать обновление страницы'; }
  });
  stream.onerror = () => { $('page-stream').textContent = 'Связь со страницей: восстановление…'; };
}
async function request(path, body) {
  if (busy || closed) return;
  clearTimeout(timer); busy = true; buttons(); controller = new AbortController();
  const command = path === '/api/relay';
  if (command) $('command-result').textContent = 'Отправка команды и ожидание показания реле…';
  try {
    const response = await fetch(path, {method: path === '/api/snapshot' ? 'GET' : 'POST',
      headers:{'Content-Type':'application/json','X-Local-Request':'1'},
      ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal:controller.signal, cache:'no-store'});
    const data = await response.json();
    if (!response.ok) {
      if (data.state) render(data.state);
      throw new Error(data.error?.message || 'Локальный запрос не выполнен.');
    }
    render(data.state || data);
    if (command) $('command-result').textContent = data.message;
  } catch (error) {
    if (error.name !== 'AbortError') {
      setNotice(`${error.message || 'Не удалось получить ответ приложения.'} Последние данные сохранены на странице.`, 'error');
      if (command) $('command-result').textContent = 'Ответ не получен. Результат команды неизвестен; обновите состояние перед повторной командой.';
      if (latest) { latest.cloud = 'error'; latest.stale = true; $('cloud').textContent = 'Ошибка запроса'; $('cloud-dot').className = 'status-dot bad'; }
    }
  } finally { busy = false; controller = null; buttons(); schedule(); }
}
$('refresh').addEventListener('click', () => request('/api/refresh'));
$('turn-on').addEventListener('click', () => request('/api/relay', {on:true}));
$('turn-off').addEventListener('click', () => request('/api/relay', {on:false}));
$('auto').addEventListener('change', schedule);
$('interval').addEventListener('change', schedule);
document.addEventListener('visibilitychange', () => { if (document.hidden) { stream?.close(); stream = null; } else connectStream(); schedule(); });
window.addEventListener('pagehide', () => { closed = true; clearTimeout(timer); controller?.abort(); stream?.close(); stream = null; });
window.addEventListener('pageshow', event => { if (event.persisted) { closed = false; connectStream(); schedule(); } });
(async () => { await request('/api/snapshot'); connectStream(); if (latest?.configured && !latest.snapshot) await request('/api/refresh'); })();

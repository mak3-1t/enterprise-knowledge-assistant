'use strict';

const get = (id) => document.getElementById(id);
const notice = (message) => { get('notice').textContent = message; };
const noEvidence = 'Không tìm thấy đủ căn cứ trong tài liệu đã lập chỉ mục.';
let token = null;
let activeStream = null;
let conversation = null;
let revision = 0;

async function api(path, options = {}) {
  const headers = new Headers(options.headers);
  if (token) headers.set('Authorization', 'Bearer ' + token);
  if (options.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers, cache: 'no-store' });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || 'HTTP ' + response.status);
  }
  return response;
}

function action(id, run) {
  get(id).addEventListener('submit', async (event) => {
    event.preventDefault();
    const submit = event.target.querySelector('button:not([type=button])');
    submit.disabled = true;
    notice('');
    try {
      await run(new FormData(event.target));
    } catch (error) {
      notice(error.message);
    } finally {
      submit.disabled = false;
    }
  });
}

action('login', async (form) => {
  const session = await (await api('/api/auth/login', {
    method: 'POST', body: JSON.stringify(Object.fromEntries(form)),
  })).json();
  token = session.accessToken;
  get('login').reset();
  get('login-panel').hidden = true;
  get('workspace').hidden = false;
  get('tenant').textContent = String(form.get('tenantSlug'));
  await documents();
});

get('logout').addEventListener('click', async () => {
  if (activeStream) activeStream.abort();
  try { await api('/api/auth/logout', { method: 'POST' }); }
  catch (error) { notice('Không xác nhận được đăng xuất trên máy chủ: ' + error.message); }
  finally {
    token = null;
    conversation = null;
    revision = 0;
    get('workspace').hidden = true;
    get('login-panel').hidden = false;
    for (const id of ['answer', 'citations', 'history', 'documents']) get(id).replaceChildren();
    get('chat').reset();
    get('upload').reset();
  }
});

async function documents() {
  const activeToken = token;
  const rows = await (await api('/api/documents?page=0&size=20')).json();
  if (activeToken !== token) return;
  const list = get('documents');
  list.replaceChildren();
  for (const row of rows) {
    const item = document.createElement('li');
    item.textContent = row.filename + ' · ' + row.status + (row.errorCode ? ' · ' + row.errorCode : '');
    list.append(item);
  }
}

get('reload').addEventListener('click', () => documents().catch(error => notice(error.message)));

action('upload', async (form) => {
  const file = form.get('file');
  if (!file || file.size < 1 || file.size > 20 * 1024 * 1024) throw new Error('Chọn một file tối đa 20 MiB.');
  const intent = await (await api('/api/documents/uploads', {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ filename: file.name, sizeBytes: file.size }),
  })).json();
  const multipart = new FormData();
  for (const [name, value] of Object.entries(intent.upload.fields)) multipart.append(name, value);
  multipart.append('file', file, file.name);
  await fetch(intent.upload.url, { method: 'POST', body: multipart, mode: 'no-cors', credentials: 'omit' });
  await api('/api/documents/' + intent.document.id + '/complete', { method: 'POST', body: '{}' });
  get('upload').reset();
  await documents();
  notice('Đã gửi tài liệu. Bấm Làm mới để xem khi nào chuyển sang READY.');
});

function showCitations(sources) {
  get('citations').replaceChildren();
  for (const source of sources || []) {
    const row = document.createElement('li');
    row.textContent = '[' + source.id + '] ' + source.filename
      + (source.page == null ? '' : ' · Trang ' + source.page)
      + ' · Đoạn ' + (source.chunkOrdinal + 1);
    get('citations').append(row);
  }
}

function sseEvent(frame) {
  let event = '';
  const data = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  return data.length ? { event, value: JSON.parse(data.join('\n')).value } : null;
}

async function streamQuestion(question) {
  const controller = new AbortController();
  const activeToken = token;
  activeStream = controller;
  get('cancel').disabled = false;
  try {
    const response = await api('/api/chat/stream', {
      method: 'POST', headers: { Accept: 'text/event-stream' },
      body: JSON.stringify({ question }), signal: controller.signal,
    });
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let pending = '';
    let complete = false;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (activeToken !== token) return;
        pending += value;
        const frames = pending.split(/\r?\n\r?\n/);
        pending = frames.pop();
        for (const frame of frames) {
          const message = sseEvent(frame);
          if (!message) continue;
          if (message.event === 'delta') get('answer').textContent += message.value;
          if (message.event === 'citations') showCitations(message.value);
          if (message.event === 'error') throw new Error(message.value);
          if (message.event === 'done') {
            complete = true;
            if (message.value === 'NO_EVIDENCE') {
              get('answer').textContent = noEvidence;
              showCitations([]);
            }
          }
        }
      }
      if (!complete) throw new Error('Luồng trả lời kết thúc trước khi hoàn tất.');
    } finally {
      await reader.cancel().catch(() => {});
    }
  } catch (error) {
    if (activeToken === token) {
      get('answer').textContent = '';
      showCitations([]);
    }
    if (error.name !== 'AbortError') throw error;
    if (activeToken === token) notice('Đã dừng trả lời.');
  } finally {
    activeStream = null;
    get('cancel').disabled = true;
  }
}

get('cancel').addEventListener('click', () => activeStream?.abort());
get('new-conversation').addEventListener('click', () => {
  conversation = null;
  revision = 0;
  get('history').replaceChildren();
});

async function savedQuestion(question) {
  const activeToken = token;
  get('new-conversation').disabled = true;
  try {
    if (!conversation) {
      const created = await (await api('/api/chat/conversations', { method: 'POST' })).json();
      if (activeToken !== token) return;
      conversation = created.id;
      revision = created.revision;
    }
    const path = '/api/chat/conversations/' + conversation;
    const result = await (await api(path + '/turns', {
      method: 'POST', body: JSON.stringify({ question, expectedRevision: revision }),
    })).json();
    if (activeToken !== token) return;
    revision = result.revision;
    get('answer').textContent = result.result.answer || noEvidence;
    showCitations(result.result.citations);
    const history = await (await api(path)).json();
    if (activeToken !== token) return;
    get('history').replaceChildren();
    for (const turn of history.turns.slice().reverse()) {
      const row = document.createElement('li');
      row.textContent = turn.question + ' — ' + (turn.response?.answer || (turn.response ? noEvidence : 'Nguồn không còn khả dụng.'));
      get('history').append(row);
    }
  } finally {
    get('new-conversation').disabled = false;
  }
}

action('chat', async (form) => {
  const question = String(form.get('question')).trim();
  if (!question) return;
  get('answer').textContent = '';
  showCitations([]);
  if (form.has('save')) await savedQuestion(question);
  else await streamQuestion(question);
});

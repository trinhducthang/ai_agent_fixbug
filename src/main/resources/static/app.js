'use strict';

const STEPS = [
  ['PULLING', 'Lấy issue & tạo nhánh'],
  ['CODING', 'Claude sửa code'],
  ['REVIEWING', 'Claude review'],
  ['WAITING_APPROVAL', 'Bạn duyệt / chat'],
  ['PUSHING', 'Push & tạo MR'],
  ['DONE', 'Xong'],
];
const ORDER = { QUEUED: 0, PULLING: 1, CODING: 2, REVIEWING: 3, WAITING_APPROVAL: 4, CHATTING: 4, PUSHING: 5, DONE: 6 };
const MODELS = [['', 'Mặc định tài khoản'], ['opus', 'Opus'], ['sonnet', 'Sonnet'], ['haiku', 'Haiku']];
const NONE = '__none__';

const state = {
  config: null,
  issues: [],
  typeFilter: 'ALL',
  projectFilter: 'ALL',
  selected: new Set(),
  selectedKey: null,
  tasks: [],
  task: null,
  selectedTaskId: null,
  logNext: 0,
  lastTaskState: null,
  msgTaskId: null,
  polling: false,
};

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'text') n.textContent = v;
    else if (k === 'checked') n.checked = !!v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c != null) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return n;
}

async function api(path, { json, method, ...rest } = {}) {
  const init = { ...rest, method: method || (json !== undefined ? 'POST' : 'GET'), headers: {} };
  if (json !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(json);
  }
  const res = await fetch('/api' + path, init);
  const ct = res.headers.get('content-type') || '';
  const body = ct.includes('application/json') ? await res.json() : await res.text();
  if (!res.ok) {
    throw new Error((body && body.message) || (typeof body === 'string' && body) || 'HTTP ' + res.status);
  }
  return body;
}

let toastTimer;
function toast(msg, isError = false) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast' + (isError ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), isError ? 9000 : 4000);
}

const fmt = (s) => (s ? String(s).slice(0, 16).replace('T', ' ') : '');
const statusClass = (s) => 'badge status-' + String(s || '').toLowerCase().replace(/\s+/g, '-');
const projects = () => (state.config ? state.config.projects : []);
const projectTitle = (name) => (projects().find((p) => p.name === name) || {}).title || name;

function projectSelect(value, id) {
  return el('select', id ? { id } : {},
    el('option', { value: '', text: '— chọn phần mềm —' }),
    projects().map((p) => el('option', { value: p.name, text: `${p.name} — ${p.title}`, selected: p.name === value })));
}

function routeBadge(route, reason) {
  return route
    ? el('span', { class: 'badge route', text: route, title: 'Tự nhận diện: ' + (reason || '') })
    : el('span', { class: 'badge route none', text: '? phần mềm', title: reason || 'Không xác định' });
}

// ------------------------------------------------------------------ config & Jira

async function loadConfig() {
  state.config = await api('/config');
  $('jql').value = state.config.defaultJql;
  const chip = $('claudeCli');
  if (state.config.claudeCli) {
    chip.textContent = 'Claude CLI: ' + state.config.claudeCli.split(/[\\/]/).slice(-4).join('/');
    chip.title = state.config.claudeCli;
    chip.className = 'chip ok';
  } else {
    chip.textContent = 'Claude CLI: không tìm thấy';
    chip.className = 'chip bad';
  }
  $('batchModel').replaceChildren(...MODELS.map(([v, l]) => el('option', { value: v, text: l })));
}

async function checkJira() {
  const chip = $('jiraUser');
  try {
    const me = await api('/jira/me');
    chip.textContent = `Jira: ${me.displayName || me.name} (${me.authMode})`;
    chip.className = 'chip ok';
    chip.title = '';
  } catch (e) {
    chip.textContent = 'Jira: chưa đăng nhập';
    chip.className = 'chip bad';
    chip.title = e.message;
  }
}

// ------------------------------------------------------------------ issues

async function loadIssues() {
  $('issueList').replaceChildren(el('div', { class: 'empty', text: 'Đang tải…' }));
  try {
    state.issues = await api('/issues?jql=' + encodeURIComponent($('jql').value));
  } catch (e) {
    state.issues = [];
    $('issueList').replaceChildren(el('div', { class: 'empty', text: e.message }));
    $('issueCount').textContent = '';
    renderFilters();
    return;
  }
  const keys = new Set(state.issues.map((i) => i.key));
  [...state.selected].forEach((k) => { if (!keys.has(k)) state.selected.delete(k); });
  renderFilters();
  renderIssues();
}

function chipGroup(containerId, current, entries, onPick) {
  $(containerId).replaceChildren(...entries.map(([value, label]) => el('button', {
    type: 'button',
    class: current === value ? 'active' : '',
    onclick: () => onPick(value),
  }, label)));
}

function renderFilters() {
  const types = {};
  const routes = {};
  state.issues.forEach((i) => {
    types[i.type] = (types[i.type] || 0) + 1;
    const r = i.route || NONE;
    routes[r] = (routes[r] || 0) + 1;
  });
  if (state.typeFilter !== 'ALL' && !types[state.typeFilter]) state.typeFilter = 'ALL';
  if (state.projectFilter !== 'ALL' && !routes[state.projectFilter]) state.projectFilter = 'ALL';
  chipGroup('typeFilter', state.typeFilter,
    [['ALL', `Tất cả (${state.issues.length})`], ...Object.keys(types).sort().map((t) => [t, `${t} (${types[t]})`])],
    (v) => { state.typeFilter = v; renderFilters(); renderIssues(); });
  chipGroup('projectFilter', state.projectFilter,
    [['ALL', 'Tất cả'], ...Object.keys(routes).sort().map((r) => [r, `${r === NONE ? 'Chưa xác định' : r} (${routes[r]})`])],
    (v) => { state.projectFilter = v; renderFilters(); renderIssues(); });
}

function visibleIssues() {
  return state.issues.filter((i) => (state.typeFilter === 'ALL' || i.type === state.typeFilter)
    && (state.projectFilter === 'ALL' || (i.route || NONE) === state.projectFilter));
}

function renderIssues() {
  const list = visibleIssues();
  $('issueCount').textContent = `(${list.length})`;
  updateSelectBar();
  if (!list.length) {
    $('issueList').replaceChildren(el('div', { class: 'empty', text: 'Không có issue nào khớp bộ lọc.' }));
    return;
  }
  $('issueList').replaceChildren(...list.map((i) => el('div', {
    class: 'item' + (i.key === state.selectedKey ? ' selected' : ''),
    onclick: () => selectIssue(i.key),
  },
  el('input', {
    type: 'checkbox',
    checked: state.selected.has(i.key),
    title: 'Chọn để giao Claude fix hàng loạt',
    onclick: (e) => e.stopPropagation(),
    onchange: (e) => { if (e.target.checked) state.selected.add(i.key); else state.selected.delete(i.key); updateSelectBar(); },
  }),
  el('div', { class: 'body' },
    el('div', { class: 'top' },
      el('span', { class: 'badge key', text: i.key }),
      routeBadge(i.route, i.routeReason),
      el('span', { class: 'badge', text: i.type }),
      el('span', { class: statusClass(i.status), text: i.status }),
      i.priority ? el('span', { class: 'badge', text: i.priority }) : null),
    el('div', { class: 'title', text: i.summary }),
    el('div', { class: 'sub', text: [i.module, i.reporter && 'Báo: ' + i.reporter, 'Cập nhật: ' + fmt(i.updated), i.dueDate && 'Hạn: ' + i.dueDate].filter(Boolean).join(' · ') })))));
}

function updateSelectBar() {
  const visible = visibleIssues();
  const allChecked = visible.length > 0 && visible.every((i) => state.selected.has(i.key));
  $('chkAll').checked = allChecked;
  $('chkAll').indeterminate = !allChecked && visible.some((i) => state.selected.has(i.key));
  $('btnBatch').textContent = `Giao Claude fix (${state.selected.size})`;
  $('btnBatch').disabled = state.selected.size === 0;
}

async function selectIssue(key) {
  state.selectedKey = key;
  renderIssues();
  const panel = $('issueDetail');
  panel.replaceChildren(el('div', { class: 'empty', text: 'Đang tải ' + key + '…' }));
  try {
    renderIssueDetail(await api('/issues/' + encodeURIComponent(key)));
  } catch (e) {
    panel.replaceChildren(el('div', { class: 'empty', text: e.message }));
  }
}

function renderIssueDetail({ issue: d, route, routeReason }) {
  const projSel = projectSelect(route, 'fProject');
  const reposInfo = el('div', { class: 'muted' });
  const showRepos = () => {
    const p = projects().find((x) => x.name === projSel.value);
    reposInfo.textContent = p ? 'Repo: ' + p.repos.map((r) => `${r.name} (${r.path} → ${r.targetBranch})`).join(' · ') : '';
  };
  projSel.addEventListener('change', showRepos);
  showRepos();
  const modelSel = el('select', { id: 'fModel' }, MODELS.map(([v, l]) => el('option', { value: v, text: l })));
  const instruction = el('textarea', { id: 'fInstruction', rows: 3, placeholder: 'Chỉ dẫn thêm cho Claude (tùy chọn): màn hình/API nghi ngờ, cách tái hiện, ràng buộc…' });
  const related = state.tasks.filter((t) => t.issueKey === d.key);

  $('issueDetail').replaceChildren(
    el('div', { class: 'top row' },
      el('a', { href: d.url, target: '_blank', rel: 'noopener', class: 'badge key', text: d.key }),
      routeBadge(route, routeReason),
      el('span', { class: 'badge', text: d.type }),
      el('span', { class: statusClass(d.status), text: d.status }),
      d.priority ? el('span', { class: 'badge', text: d.priority }) : null),
    el('div', { class: 'detail-title', text: d.summary }),
    el('div', { class: 'meta' },
      d.module ? el('span', { text: 'Module: ' + d.module }) : null,
      d.components.length ? el('span', { text: 'Component: ' + d.components.join(', ') }) : null,
      el('span', { text: 'Báo: ' + (d.reporter || '—') }),
      el('span', { text: 'Giao: ' + (d.assignee || '—') }),
      el('span', { text: 'Cập nhật: ' + fmt(d.updated) }),
      d.dueDate ? el('span', { text: 'Hạn: ' + d.dueDate }) : null),
    el('pre', { class: 'desc', text: d.description || '(Không có mô tả)' }),
    d.comments.length
      ? el('details', { class: 'comments' },
        el('summary', { text: `Bình luận (${d.comments.length})` }),
        ...d.comments.map((c) => el('div', { class: 'comment' },
          el('div', { class: 'who', text: `${c.author || ''} · ${fmt(c.created)}` }), c.body || '')))
      : null,
    d.attachments.length
      ? el('div', { class: 'attachments', text: 'Đính kèm: ' + d.attachments.map((a) => a.filename).join(', ') })
      : null,
    el('div', { class: 'form' },
      el('label', {}, 'Phần mềm' + (route ? ' (tự nhận diện: ' + routeReason + ')' : ' – không tự nhận diện được, hãy chọn'), projSel),
      el('label', {}, 'Model Claude', modelSel),
      el('div', { class: 'wide' }, reposInfo),
      el('label', { class: 'wide' }, 'Chỉ dẫn thêm', instruction),
      el('div', { class: 'wide row' },
        el('button', { class: 'btn primary', type: 'button', onclick: () => startTask(d.key) }, 'Giao cho Claude xử lý'),
        el('span', { class: 'muted', text: 'Claude làm trong worktree riêng của từng repo (BE + FE), không đụng thư mục làm việc của bạn. Chỉ push khi bạn bấm duyệt.' }))),
    related.length
      ? el('div', { class: 'meta' }, 'Phiên của issue này: ',
        ...related.map((t) => el('a', { href: '#', onclick: (e) => { e.preventDefault(); selectTask(t.id); }, text: `${t.project} · ${t.stateLabel}` })))
      : null,
  );
}

async function startTask(key) {
  if (!$('fProject').value) {
    toast('Chọn phần mềm cho issue này trước', true);
    return;
  }
  try {
    const t = await api('/tasks', {
      json: { issueKey: key, project: $('fProject').value, model: $('fModel').value || null, instruction: $('fInstruction').value },
    });
    toast('Đã tạo phiên ' + t.id);
    await loadTasks();
    selectTask(t.id);
  } catch (e) {
    toast(e.message, true);
  }
}

// ------------------------------------------------------------------ batch

function openBatch() {
  const items = state.issues.filter((i) => state.selected.has(i.key));
  if (!items.length) return;
  $('batchCount').textContent = items.length;
  $('batchRows').replaceChildren(...items.map((i) => el('div', { class: 'batch-row' },
    el('div', {},
      el('div', { class: 'k', text: `${i.key} · ${i.type}` }),
      el('div', { class: 's', text: i.summary, title: i.summary }),
      el('div', { class: 'r', text: i.route ? 'Tự nhận diện: ' + i.routeReason : 'Không tự nhận diện được – chọn tay' })),
    projectSelect(i.route, 'batch-' + i.key))));
  $('batchInstruction').value = '';
  $('batchDialog').showModal();
}

async function submitBatch() {
  const items = state.issues.filter((i) => state.selected.has(i.key))
    .map((i) => ({ issueKey: i.key, project: ($('batch-' + i.key) || {}).value || null }));
  const missing = items.filter((x) => !x.project).map((x) => x.issueKey);
  if (missing.length) {
    toast('Chưa chọn phần mềm cho: ' + missing.join(', '), true);
    $('batchDialog').showModal();
    return;
  }
  try {
    const results = await api('/tasks/batch', {
      json: { items, model: $('batchModel').value || null, instruction: $('batchInstruction').value },
    });
    const ok = results.filter((r) => r.taskId);
    const bad = results.filter((r) => !r.taskId);
    ok.forEach((r) => state.selected.delete(r.issueKey));
    renderIssues();
    await loadTasks();
    if (ok.length) selectTask(ok[0].taskId);
    toast(`Đã tạo ${ok.length} phiên` + (bad.length ? `\nLỗi:\n` + bad.map((r) => `${r.issueKey}: ${r.error}`).join('\n') : ''), bad.length > 0);
  } catch (e) {
    toast(e.message, true);
  }
}

// ------------------------------------------------------------------ tasks

async function loadTasks() {
  try {
    state.tasks = await api('/tasks');
  } catch (e) {
    return;
  }
  if (!state.tasks.length) {
    $('taskList').replaceChildren(el('div', { class: 'empty', text: 'Chưa có phiên nào.' }));
    return;
  }
  $('taskList').replaceChildren(...state.tasks.map((t) => el('div', {
    class: 'item nocheck' + (t.id === state.selectedTaskId ? ' selected' : ''),
    onclick: () => selectTask(t.id),
  },
  el('div', { class: 'body' },
    el('div', { class: 'top' },
      el('span', { class: 'badge key', text: t.issueKey }),
      el('span', { class: 'badge route', text: t.project }),
      el('span', { class: 'badge ' + taskBadge(t), text: t.stateLabel })),
    el('div', { class: 'title', text: t.issueSummary || '' }),
    el('div', { class: 'sub', text: ['$' + t.costUsd.toFixed(2), fmt(t.createdAt), t.repos.filter((r) => r.mrUrl).length ? 'MR: ' + t.repos.filter((r) => r.mrUrl).map((r) => r.name).join(', ') : null].filter(Boolean).join(' · ') })))));
}

function taskBadge(t) {
  if (t.state === 'DONE') return 'done';
  if (t.state === 'FAILED' || t.state === 'CANCELLED') return 'fail';
  return t.busy ? 'busy' : '';
}

function selectTask(id) {
  state.selectedTaskId = id;
  state.logNext = 0;
  state.lastTaskState = null;
  $('tab-log').textContent = '';
  $('diff').textContent = '';
  $('taskPanel').classList.remove('hidden');
  loadTasks();
  refreshTask();
  poll();
  $('taskPanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function refreshTask() {
  if (!state.selectedTaskId) return;
  try {
    renderTask(await api('/tasks/' + encodeURIComponent(state.selectedTaskId)));
  } catch (e) {
    toast(e.message, true);
  }
}

function renderTask(t) {
  state.task = t;
  $('taskTitle').textContent = `${t.issueKey} · ${t.project}`;
  $('taskMeta').replaceChildren(
    el('span', { text: t.projectTitle }),
    el('span', { text: 'Model: ' + (t.model || 'mặc định') }),
    el('span', { text: 'Chi phí ước tính: $' + t.costUsd.toFixed(2) }),
    el('span', { text: 'Thư mục phiên: ' + t.taskDir }),
    t.attachments.length || t.skippedAttachments.length
      ? el('span', {
        text: `Đính kèm: ${t.attachments.length} file Claude phải xem` + (t.skippedAttachments.length ? `, ${t.skippedAttachments.length} file không xem được` : ''),
        title: [...t.attachments, ...t.skippedAttachments.map((s) => '(bỏ qua) ' + s)].join('\n'),
      })
      : null,
  );
  $('taskRepos').replaceChildren(...t.repos.map((r) => el('div', { class: 'repo' },
    el('span', { class: 'badge key', text: r.name }),
    el('span', { text: r.branch ? `${r.branch} → ${r.targetBranch}` : `(chưa tạo) → ${r.targetBranch}` }),
    r.mrUrl
      ? el('a', { href: r.mrUrl, target: '_blank', rel: 'noopener', text: r.mrCreated ? 'Merge request' : 'Tạo MR thủ công' })
      : null,
    r.pushed ? el('span', { class: 'badge done', text: 'đã push' }) : null)));

  const cur = ORDER[t.state];
  const items = STEPS.map(([key, label]) => {
    let cls = '';
    if (t.state === 'DONE' || (cur !== undefined && ORDER[key] < cur)) cls = 'done';
    if (key === t.state || (t.state === 'CHATTING' && key === 'WAITING_APPROVAL')) cls = 'active';
    return el('li', { class: cls, text: t.state === 'CHATTING' && key === 'WAITING_APPROVAL' ? 'Claude xử lý chat' : label });
  });
  if (t.state === 'FAILED' || t.state === 'CANCELLED') items.push(el('li', { class: 'fail', text: t.stateLabel }));
  $('steps').replaceChildren(...items);

  $('taskError').classList.toggle('hidden', !t.error);
  $('taskError').textContent = t.error || '';
  $('taskWarnings').classList.toggle('hidden', !t.warnings.length);
  $('taskWarnings').textContent = t.warnings.map((w) => '⚠ ' + w).join('\n');
  $('coderSummary').textContent = t.coderSummary || '(chưa có)';
  $('reviewSummary').textContent = t.reviewSummary || '(chưa có)';

  $('btnCancel').disabled = !t.canCancel;
  $('btnDiscard').disabled = !t.canDiscard;
  $('btnChat').disabled = !t.canChat;
  $('chatInput').disabled = !t.canChat;
  $('btnPush').disabled = !t.canPush;

  const msg = `${t.issueKey}: ${t.issueSummary || ''}`.trim();
  if (state.msgTaskId !== t.id) {
    $('commitMsg').value = msg;
    $('mrTitle').value = msg;
    state.msgTaskId = t.id;
  } else if (t.issueSummary && $('commitMsg').value === `${t.issueKey}:`) {
    $('commitMsg').value = msg;
    $('mrTitle').value = msg;
  }
}

async function poll() {
  const id = state.selectedTaskId;
  if (!id || state.polling) return;
  state.polling = true;
  try {
    const slice = await api(`/tasks/${encodeURIComponent(id)}/logs?from=${state.logNext}`);
    if (id !== state.selectedTaskId) return;
    if (slice.lines.length) {
      const pre = $('tab-log');
      const atBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;
      pre.append(slice.lines.join('\n') + '\n');
      if (atBottom) pre.scrollTop = pre.scrollHeight;
    }
    state.logNext = slice.next;
    if (slice.state !== state.lastTaskState) {
      const wasBusy = state.lastTaskState !== null && !['WAITING_APPROVAL', 'DONE', 'FAILED', 'CANCELLED'].includes(state.lastTaskState);
      state.lastTaskState = slice.state;
      await refreshTask();
      loadTasks();
      if (wasBusy && ['WAITING_APPROVAL', 'DONE'].includes(slice.state)) loadDiff();
    }
  } catch (e) {
    if (String(e.message).includes('Không có phiên')) {
      state.selectedTaskId = null;
      $('taskPanel').classList.add('hidden');
    }
  } finally {
    state.polling = false;
  }
}

async function loadDiff() {
  if (!state.selectedTaskId) return;
  const pre = $('diff');
  pre.textContent = 'Đang tải diff…';
  try {
    renderDiff(await api(`/tasks/${encodeURIComponent(state.selectedTaskId)}/diff`));
  } catch (e) {
    pre.textContent = e.message;
  }
}

function renderDiff(text) {
  const frag = document.createDocumentFragment();
  for (const line of String(text).split('\n')) {
    let cls = '';
    if (line.startsWith('=====')) cls = 'repo';
    else if (line.startsWith('diff --git') || line.startsWith('+++ ') || line.startsWith('--- ')) cls = 'file';
    else if (line.startsWith('@@')) cls = 'hunk';
    else if (line.startsWith('+')) cls = 'add';
    else if (line.startsWith('-')) cls = 'del';
    frag.append(el('span', { class: cls, text: line || ' ' }));
  }
  $('diff').replaceChildren(frag);
}

// ------------------------------------------------------------------ actions

async function sendChat() {
  const message = $('chatInput').value.trim();
  if (!message || !state.selectedTaskId) return;
  try {
    renderTask(await api(`/tasks/${encodeURIComponent(state.selectedTaskId)}/chat`, { json: { message } }));
    $('chatInput').value = '';
    switchTab('log');
  } catch (e) {
    toast(e.message, true);
  }
}

async function push() {
  const t = state.task;
  if (!t) return;
  const repos = t.repos.filter((r) => r.created).map((r) => `${r.name}: ${r.branch} → ${r.targetBranch}`).join('\n');
  const ok = confirm(`Commit thay đổi, push và tạo merge request cho các repo CÓ THAY ĐỔI:\n${repos}\n\nHãy chắc chắn bạn đã đọc tab Diff.`);
  if (!ok) return;
  try {
    renderTask(await api(`/tasks/${encodeURIComponent(t.id)}/push`, {
      json: { commitMessage: $('commitMsg').value, mrTitle: $('mrTitle').value },
    }));
    switchTab('log');
  } catch (e) {
    toast(e.message, true);
  }
}

async function cancelTask() {
  if (!state.selectedTaskId) return;
  try {
    renderTask(await api(`/tasks/${encodeURIComponent(state.selectedTaskId)}/cancel`, { method: 'POST' }));
  } catch (e) {
    toast(e.message, true);
  }
}

async function discardTask() {
  const t = state.task;
  if (!t) return;
  const ok = confirm(`Xóa thư mục phiên ${t.taskDir} (worktree + nhánh local của ${t.repos.map((r) => r.name).join(', ')})?\nThay đổi chưa push sẽ mất. Nhánh/MR đã push lên GitLab vẫn giữ nguyên.`);
  if (!ok) return;
  try {
    const r = await api('/tasks/' + encodeURIComponent(t.id), { method: 'DELETE' });
    toast(r.message);
    state.selectedTaskId = null;
    state.task = null;
    $('taskPanel').classList.add('hidden');
    loadTasks();
  } catch (e) {
    toast(e.message, true);
  }
}

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  ['log', 'diff', 'summary'].forEach((n) => $('tab-' + n).classList.toggle('hidden', n !== name));
  if (name === 'diff') loadDiff();
}

// ------------------------------------------------------------------ wiring

document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));
$('btnReload').addEventListener('click', loadIssues);
$('btnApplyJql').addEventListener('click', loadIssues);
$('btnResetJql').addEventListener('click', () => { $('jql').value = state.config ? state.config.defaultJql : ''; loadIssues(); });
$('chkAll').addEventListener('change', (e) => {
  visibleIssues().forEach((i) => { if (e.target.checked) state.selected.add(i.key); else state.selected.delete(i.key); });
  renderIssues();
});
$('btnBatch').addEventListener('click', openBatch);
$('batchDialog').addEventListener('close', () => { if ($('batchDialog').returnValue === 'default') submitBatch(); });
$('btnDiff').addEventListener('click', loadDiff);
$('btnChat').addEventListener('click', sendChat);
$('chatInput').addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) sendChat(); });
$('btnPush').addEventListener('click', push);
$('btnCancel').addEventListener('click', cancelTask);
$('btnDiscard').addEventListener('click', discardTask);
$('btnCookie').addEventListener('click', () => { $('cookieInput').value = ''; $('cookieDialog').showModal(); });
$('cookieDialog').addEventListener('close', async () => {
  if ($('cookieDialog').returnValue !== 'default') return;
  try {
    await api('/jira/cookie', { json: { cookie: $('cookieInput').value } });
    toast('Đã cập nhật phiên Jira');
  } catch (e) {
    toast(e.message, true);
  }
  await checkJira();
  loadIssues();
});

(async function init() {
  try {
    await loadConfig();
  } catch (e) {
    toast('Không đọc được cấu hình: ' + e.message, true);
  }
  await checkJira();
  loadIssues();
  loadTasks();
  setInterval(poll, 1500);
  setInterval(loadTasks, 5000);
})();

'use strict';

const state = {
  profiles: [],
  activeId: null,
  draft: null,
  findings: [],
  warnings: [],
  jiraResult: null,
};

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'text') n.textContent = v;
    else if (k === 'value') n.value = v;
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
  if (!res.ok) throw new Error((body && body.message) || (typeof body === 'string' && body) || 'HTTP ' + res.status);
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

const list = (s) => String(s || '').split(/[,\n]/).map((x) => x.trim()).filter(Boolean);
const lines = (s) => String(s || '').split(/\n/).map((x) => x.trim()).filter(Boolean);

/** Ô nhập gắn với một thuộc tính của object (cập nhật ngay khi gõ). */
function field(label, obj, key, opts = {}) {
  const tag = opts.rows ? 'textarea' : 'input';
  const isList = opts.list;
  const value = isList ? (obj[key] || []).join(opts.rows ? '\n' : ', ') : (obj[key] || '');
  const input = el(tag, {
    type: tag === 'input' ? (opts.type || 'text') : null,
    rows: opts.rows || null,
    placeholder: opts.placeholder || '',
    value,
    spellcheck: 'false',
    list: opts.datalist || null,
    oninput: (e) => { obj[key] = isList ? (opts.rows ? lines(e.target.value) : list(e.target.value)) : e.target.value; },
  });
  return el('label', { class: opts.wide ? 'wide' : '' }, label, input, opts.hint ? el('span', { class: 'hint', text: opts.hint }) : null);
}

// ------------------------------------------------------------------ profile list

async function loadProfiles(editId) {
  const r = await api('/profiles');
  state.profiles = r.profiles;
  state.activeId = r.activeId;
  $('dataDir').textContent = 'Profile lưu tại ' + r.dataDir + ' (ngoài project, mật khẩu được mã hóa).';
  $('profileList').replaceChildren(...r.profiles.map((p) => el('div', {
    class: 'item nocheck' + (state.draft && state.draft.id === p.id ? ' selected' : ''),
    onclick: () => edit(p.id),
  }, el('div', { class: 'body' },
    el('div', { class: 'top' },
      el('strong', { text: p.name }),
      p.active ? el('span', { class: 'badge done', text: 'đang dùng' }) : null),
    el('div', { class: 'sub', text: (p.jiraBaseUrl || '(chưa có Jira)') + (p.jiraUsername ? ' · ' + p.jiraUsername : '') }),
    el('div', { class: 'sub', text: p.projects.length + ' phần mềm: ' + p.projects.map((x) => x.name).join(', ') })))));
  if (editId) edit(editId);
  else if (!state.draft && r.profiles.length) edit(r.activeId);
}

function edit(id) {
  const p = state.profiles.find((x) => x.id === id);
  if (!p) return;
  state.draft = JSON.parse(JSON.stringify(p));
  state.draft.password = '';
  state.findings = [];
  state.warnings = [];
  state.jiraResult = null;
  renderEditor();
  loadProfilesListHighlight();
}

function loadProfilesListHighlight() {
  document.querySelectorAll('#profileList .item').forEach((n, i) => {
    n.classList.toggle('selected', state.profiles[i] && state.draft && state.profiles[i].id === state.draft.id);
  });
}

function newProfile() {
  state.draft = {
    id: null, name: '', active: false, jiraBaseUrl: '', jiraUsername: '', hasPassword: false, password: '',
    defaultJql: '', moduleField: '', sourceRoot: '', sourceLinks: [], jiraProjectKeys: [], projects: [],
  };
  state.findings = [];
  state.warnings = [];
  state.jiraResult = null;
  renderEditor();
  loadProfilesListHighlight();
}

// ------------------------------------------------------------------ editor

function renderEditor() {
  const d = state.draft;
  const jiraLink = el('input', {
    type: 'text', spellcheck: 'false', value: d.jiraLink || d.jiraBaseUrl || '',
    placeholder: 'Dán link Jira bất kỳ: trang chủ, link issue, link filter/board…',
    oninput: (e) => { d.jiraLink = e.target.value; },
  });
  const password = el('input', {
    type: 'password', autocomplete: 'new-password',
    placeholder: d.hasPassword ? '•••••• (đã lưu – để trống để giữ nguyên)' : 'Mật khẩu Jira',
    oninput: (e) => { d.password = e.target.value; },
  });

  $('editor').replaceChildren(
    el('div', { class: 'panel-head' },
      el('h2', { text: d.id ? 'Sửa profile' : 'Profile mới' }),
      d.active ? el('span', { class: 'badge done', text: 'đang dùng' }) : null),

    el('div', { class: 'form' },
      field('Tên profile', d, 'name', { wide: true, placeholder: 'vd: BTP – Nền tảng số pháp luật' })),

    el('h3', { class: 'section', text: '1. Jira' }),
    el('div', { class: 'form' },
      el('label', { class: 'wide' }, 'Link Jira', jiraLink),
      field('Tài khoản Jira', d, 'jiraUsername', { placeholder: 'username' }),
      el('label', {}, 'Mật khẩu Jira', password),
      el('div', { class: 'row' },
        el('button', { class: 'btn primary', type: 'button', onclick: analyzeJira }, 'Kiểm tra & tự nhận'))),
    el('div', { id: 'jiraResult' }),
    el('div', { class: 'form' },
      field('Địa chỉ Jira (base URL)', d, 'jiraBaseUrl', { placeholder: 'https://jira.example.com' }),
      field('Field Module (tùy chọn)', d, 'moduleField', { placeholder: 'customfield_10700', hint: 'Dùng để tự phân loại issue theo phần mềm' }),
      field('Mã Jira project', d, 'jiraProjectKeys', { list: true, placeholder: 'ABC, XYZ' }),
      field('JQL mặc định', d, 'defaultJql', { wide: true, rows: 2 })),

    el('h3', { class: 'section', text: '2. Source code' }),
    el('div', { class: 'form' },
      field('Thư mục chứa code local', d, 'sourceRoot', {
        wide: true, placeholder: 'vd: F:\\ – nơi tìm bản clone sẵn có và nơi clone repo mới',
      }),
      field('Link source code (mỗi dòng một link)', d, 'sourceLinks', {
        wide: true, rows: 5, list: true,
        placeholder: 'https://git.example.com/group/app/app_be\nhttps://git.example.com/group/app/app_fe/-/tree/develop\nF:\\app   (thư mục local chứa nhiều repo cũng được)',
      }),
      el('div', { class: 'row wide' },
        el('button', { class: 'btn primary', type: 'button', onclick: analyzeSources }, 'Phân tích & tự map'),
        el('span', { class: 'muted', text: 'Tự nhận BE/FE, nhánh nhận MR, lệnh build, node_modules dùng chung, gom thành phần mềm và gắn Jira project.' }))),
    el('div', { id: 'warnings' }),
    el('div', { id: 'softwares' }),

    el('div', { class: 'row end footer' },
      d.id && !d.active ? el('button', { class: 'btn danger', type: 'button', onclick: deleteProfile }, 'Xóa profile') : null,
      el('button', { class: 'btn', type: 'button', onclick: () => save(false) }, 'Lưu'),
      el('button', { class: 'btn primary', type: 'button', onclick: () => save(true) }, 'Lưu & dùng profile này')),
  );
  renderJiraResult();
  renderWarnings();
  renderSoftwares();
}

function renderJiraResult() {
  const r = state.jiraResult;
  const box = $('jiraResult');
  if (!box) return;
  if (!r) { box.replaceChildren(); return; }
  box.replaceChildren(el('div', { class: 'result' },
    el('div', { text: `Jira ${r.serverVersion || '?'} tại ${r.baseUrl}` + (r.serverTitle ? ` (${r.serverTitle})` : '') }),
    r.loginOk
      ? el('div', { class: 'ok', text: '✓ Đăng nhập được: ' + r.user })
      : el('div', { class: 'bad', text: '✗ Chưa đăng nhập được: ' + (r.loginError || '') }),
    r.projects.length ? el('div', { text: 'Project: ' + r.projects.map((p) => `${p.key} – ${p.name} (${p.components} component)`).join('; ') }) : null,
    r.projectErrors.length ? el('div', { class: 'bad', text: 'Không đọc được: ' + r.projectErrors.join('; ') }) : null,
    el('div', { text: r.moduleField ? `Field Module: ${r.moduleField} (${r.moduleFieldName})` : 'Không tìm thấy field Module – phân loại theo tiêu đề/component' }),
    el('div', { class: 'muted', text: 'JQL gợi ý: ' + r.suggestedJql })));
}

function renderWarnings() {
  const box = $('warnings');
  if (!box) return;
  box.replaceChildren(state.warnings.length
    ? el('div', { class: 'alert warn' }, state.warnings.map((w) => '⚠ ' + w).join('\n'))
    : '');
}

function findingFor(path) {
  if (!path) return null;
  const norm = (p) => String(p).replace(/\\/g, '/').toLowerCase();
  return state.findings.find((f) => f.path && norm(f.path) === norm(path)) || null;
}

function renderSoftwares() {
  const box = $('softwares');
  if (!box) return;
  const d = state.draft;
  box.replaceChildren(
    ...d.projects.map((p, pi) => softwareCard(p, pi)),
    el('div', { class: 'row' },
      el('button', {
        class: 'btn small ghost', type: 'button',
        onclick: () => { d.projects.push({ name: 'app' + (d.projects.length + 1), title: '', jiraProjects: [], keywords: [], contextDirs: [], repos: [emptyRepo('be')] }); renderSoftwares(); },
      }, '+ Thêm phần mềm')),
    state.findings.length ? el('datalist', { id: 'dl-empty' }) : null,
  );
}

function emptyRepo(name) {
  return { name, path: '', remote: 'origin', targetBranch: 'develop', verifyHint: '', sharedDirs: [] };
}

function softwareCard(p, pi) {
  const d = state.draft;
  return el('div', { class: 'software' },
    el('div', { class: 'panel-head' },
      el('h3', { text: `Phần mềm: ${p.name}` }),
      el('button', { class: 'btn small danger', type: 'button', onclick: () => { d.projects.splice(pi, 1); renderSoftwares(); } }, 'Xóa')),
    el('div', { class: 'form' },
      field('Tên (ngắn, không dấu)', p, 'name'),
      field('Tiêu đề', p, 'title'),
      field('Jira project của riêng phần mềm này', p, 'jiraProjects', { list: true, hint: 'Issue thuộc project này → luôn vào phần mềm này' }),
      field('Từ khóa nhận diện', p, 'keywords', { list: true, wide: true, hint: 'Tìm trong Module (3đ), tiêu đề (2đ), component (1đ)' }),
      field('Tài liệu tham khảo (chỉ đọc)', p, 'contextDirs', { list: true, wide: true })),
    el('div', { class: 'repo-table' },
      el('div', { class: 'repo-row head' },
        el('span', { text: 'Tên' }), el('span', { text: 'Đường dẫn local' }), el('span', { text: 'Nhánh nhận MR' }),
        el('span', { text: 'Lệnh kiểm tra' }), el('span', { text: 'node_modules dùng chung' }), el('span', { text: '' })),
      ...p.repos.map((r, ri) => repoRow(p, r, ri))),
    el('div', { class: 'row' },
      el('button', { class: 'btn small ghost', type: 'button', onclick: () => { p.repos.push(emptyRepo('repo' + (p.repos.length + 1))); renderSoftwares(); } }, '+ Thêm repo')));
}

function repoRow(p, r, ri) {
  const f = findingFor(r.path);
  const dlId = `dl-${p.name}-${ri}`;
  const status = !f ? null
    : f.exists
      ? el('span', { class: 'badge done', text: '✓ có local', title: [f.remoteUrl, f.buildTool, f.note].filter(Boolean).join('\n') })
      : el('button', { class: 'btn small primary', type: 'button', onclick: () => cloneRepo(f), title: f.remoteUrl }, 'Clone');
  const input = (key, opts = {}) => el('input', {
    type: 'text', spellcheck: 'false', value: opts.list ? (r[key] || []).join(', ') : (r[key] || ''), list: opts.datalist || null,
    oninput: (e) => { r[key] = opts.list ? list(e.target.value) : e.target.value; },
  });
  return el('div', { class: 'repo-row' },
    input('name'),
    input('path'),
    el('span', {}, input('targetBranch', { datalist: dlId }),
      el('datalist', { id: dlId }, ...((f && f.branches) || []).map((b) => el('option', { value: b })))),
    input('verifyHint'),
    input('sharedDirs', { list: true }),
    el('span', { class: 'row' }, status,
      el('button', { class: 'btn small ghost', type: 'button', title: 'Bỏ repo', onclick: () => { p.repos.splice(ri, 1); renderSoftwares(); } }, '✕')),
    f && f.note ? el('div', { class: 'note', text: f.note }) : null);
}

// ------------------------------------------------------------------ actions

async function analyzeJira() {
  const d = state.draft;
  const link = (d.jiraLink || d.jiraBaseUrl || '').trim();
  if (!link) { toast('Dán link Jira trước', true); return; }
  try {
    const r = await api('/profiles/analyze-jira', { json: { profileId: d.id, link, username: d.jiraUsername, password: d.password } });
    state.jiraResult = r;
    d.jiraBaseUrl = r.baseUrl;
    if (r.projects.length) d.jiraProjectKeys = r.projects.map((p) => p.key);
    if (r.moduleField) d.moduleField = r.moduleField;
    if (!d.defaultJql || (d.defaultJql !== r.suggestedJql && confirm('Thay JQL mặc định bằng JQL gợi ý?\n\n' + r.suggestedJql))) {
      d.defaultJql = r.suggestedJql;
    }
    if (!d.name && r.projects.length) d.name = r.projects.map((p) => p.name).join(' + ');
    renderEditor();
    toast(r.loginOk ? 'Đã nhận Jira và đăng nhập được' : 'Đã nhận Jira nhưng chưa đăng nhập được', !r.loginOk);
  } catch (e) {
    toast(e.message, true);
  }
}

async function analyzeSources() {
  const d = state.draft;
  if (!d.sourceLinks.length) { toast('Dán ít nhất một link source', true); return; }
  if (d.projects.length && !confirm('Thay cấu hình phần mềm hiện tại bằng kết quả tự map?')) return;
  const btns = document.querySelectorAll('button');
  btns.forEach((b) => { b.disabled = true; });
  toast('Đang phân tích (có thể mất vài chục giây nếu phải đọc nhánh từ remote)…');
  try {
    const r = await api('/profiles/analyze-sources', { json: { links: d.sourceLinks, sourceRoot: d.sourceRoot, jiraProjectKeys: d.jiraProjectKeys } });
    d.projects = r.projects;
    state.findings = r.repos;
    state.warnings = r.warnings;
    renderEditor();
    toast(`Đã map ${r.repos.length} repo thành ${r.projects.length} phần mềm`);
  } catch (e) {
    toast(e.message, true);
  } finally {
    document.querySelectorAll('button').forEach((b) => { b.disabled = false; });
  }
}

async function cloneRepo(f) {
  if (!confirm(`Clone ${f.remoteUrl}\nvào ${f.path} ?`)) return;
  toast('Đang clone ' + f.remoteUrl + '…');
  try {
    await api('/profiles/clone', { json: { url: f.remoteUrl, path: f.path } });
    f.exists = true;
    f.note = 'Đã clone – bấm "Phân tích & tự map" lại để nhận lệnh build và node_modules';
    renderSoftwares();
    toast('Đã clone vào ' + f.path);
  } catch (e) {
    toast(e.message, true);
  }
}

async function save(activate) {
  const d = state.draft;
  const body = {
    id: d.id, name: d.name, jiraBaseUrl: d.jiraBaseUrl, jiraUsername: d.jiraUsername, jiraPassword: d.password || null,
    clearPassword: false, defaultJql: d.defaultJql, moduleField: d.moduleField, sourceRoot: d.sourceRoot,
    sourceLinks: d.sourceLinks, jiraProjectKeys: d.jiraProjectKeys,
    projects: d.projects.map((p) => ({
      name: p.name, title: p.title, jiraProjects: p.jiraProjects || [], keywords: p.keywords || [],
      contextDirs: p.contextDirs || [],
      repos: p.repos.map((r) => ({ name: r.name, path: r.path, remote: r.remote || 'origin', targetBranch: r.targetBranch, verifyHint: r.verifyHint, sharedDirs: r.sharedDirs || [] })),
    })),
  };
  try {
    const saved = await api('/profiles', { json: body });
    if (activate) await api(`/profiles/${encodeURIComponent(saved.id)}/activate`, { method: 'POST' });
    state.draft = null;
    await loadProfiles(saved.id);
    toast(activate ? 'Đã lưu và chuyển sang profile này' : 'Đã lưu profile');
  } catch (e) {
    toast(e.message, true);
  }
}

async function deleteProfile() {
  const d = state.draft;
  if (!d.id || !confirm(`Xóa profile "${d.name}"? (không ảnh hưởng code/repo)`)) return;
  try {
    await api('/profiles/' + encodeURIComponent(d.id), { method: 'DELETE' });
    state.draft = null;
    await loadProfiles();
    toast('Đã xóa profile');
  } catch (e) {
    toast(e.message, true);
  }
}

$('btnNew').addEventListener('click', newProfile);
$('btnImport').addEventListener('click', async () => {
  try {
    const p = await api('/profiles/import-yml', { method: 'POST' });
    state.draft = null;
    await loadProfiles(p.id);
    toast('Đã tạo profile từ application.yml');
  } catch (e) {
    toast(e.message, true);
  }
});

loadProfiles().catch((e) => toast(e.message, true));

const folderInput = document.getElementById('folderInput');
const loadBtn = document.getElementById('loadBtn');
const eventsList = document.getElementById('eventsList');
const eventDetails = document.getElementById('eventDetails');
const artifactViewer = document.getElementById('artifactViewer');
const metaPill = document.getElementById('metaPill');
const filterInput = document.getElementById('filterInput');

/** @type {Map<string, File>} */
let fileMap = new Map();
/** @type {string} */
let rootPrefix = "";
/** @type {any[]} */
let events = [];
/** @type {number} */
let activeIndex = -1;

loadBtn.addEventListener('click', () => folderInput.click());
folderInput.addEventListener('change', () => loadTrace(folderInput.files));
filterInput.addEventListener('input', () => renderEvents());

function toLocalTime(tsMs) {
  try { return new Date(tsMs).toLocaleString(); } catch { return String(tsMs); }
}

function setMeta(meta) {
  if (!meta) {
    metaPill.textContent = "No trace loaded";
    return;
  }
  metaPill.textContent = `${meta.runId} • ${meta.appId} ${meta.appVersionName} • sdk ${meta.deviceSdkInt}`;
}

function buildFileMap(files) {
  fileMap = new Map();
  for (const f of files) {
    const rel = f.webkitRelativePath || f.name;
    fileMap.set(rel, f);
  }
}

function findRootPrefix() {
  for (const key of fileMap.keys()) {
    if (key.endsWith('/trace.jsonl') || key === 'trace.jsonl') {
      return key.replace(/trace\\.jsonl$/, '');
    }
  }
  return "";
}

function getFileByRelativePath(relPath) {
  const withPrefix = rootPrefix ? (rootPrefix + relPath) : relPath;
  return fileMap.get(withPrefix) || fileMap.get(relPath) || null;
}

async function readTextFile(relPath) {
  const f = getFileByRelativePath(relPath);
  if (!f) return null;
  return await f.text();
}

async function loadTrace(files) {
  if (!files || files.length === 0) return;
  buildFileMap(files);
  rootPrefix = findRootPrefix();

  const metaText = await readTextFile('meta.json');
  if (metaText) {
    try { setMeta(JSON.parse(metaText)); } catch { setMeta(null); }
  } else {
    setMeta(null);
  }

  const traceText = await readTextFile('trace.jsonl');
  if (!traceText) {
    events = [];
    renderEvents();
    eventDetails.textContent = "Could not find trace.jsonl in the selected folder.";
    return;
  }

  events = traceText
    .split('\\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)
    .map(line => {
      try { return JSON.parse(line); } catch { return null; }
    })
    .filter(Boolean)
    .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));

  activeIndex = -1;
  renderEvents();
  eventDetails.textContent = `Loaded ${events.length} events. Select one on the left.`;
  artifactViewer.textContent = "Select an artifact chip to preview.";
}

function matchesFilter(evt, q) {
  if (!q) return true;
  const s = q.toLowerCase();
  const blob = JSON.stringify(evt).toLowerCase();
  return blob.includes(s);
}

function renderEvents() {
  const q = (filterInput.value || "").trim();
  eventsList.innerHTML = "";
  const filtered = events
    .map((evt, idx) => ({ evt, idx }))
    .filter(x => matchesFilter(x.evt, q));

  for (const { evt, idx } of filtered) {
    const li = document.createElement('li');
    li.className = 'event' + (idx === activeIndex ? ' active' : '');
    li.addEventListener('click', () => selectEvent(idx));

    const top = document.createElement('div');
    top.className = 'event-top';

    const type = document.createElement('div');
    type.className = 'event-type';
    type.textContent = evt.type || 'unknown';

    const meta = document.createElement('div');
    meta.className = 'event-meta';
    const turn = (evt.turnNumber != null) ? `t${evt.turnNumber}` : '';
    meta.textContent = `${turn}  #${evt.seq ?? ''}`;

    top.appendChild(type);
    top.appendChild(meta);
    li.appendChild(top);

    const badge = document.createElement('div');
    badge.className = 'badge';
    if (String(evt.type || '').includes('error')) badge.classList.add('err');
    else if (String(evt.type || '').includes('warning')) badge.classList.add('warn');
    else badge.classList.add('ok');
    badge.textContent = toLocalTime(evt.tsMs ?? 0);
    li.appendChild(badge);

    eventsList.appendChild(li);
  }
}

function prettyJson(obj) {
  try { return JSON.stringify(obj, null, 2); } catch { return String(obj); }
}

function selectEvent(index) {
  activeIndex = index;
  renderEvents();
  const evt = events[index];
  if (!evt) return;

  const artifacts = Array.isArray(evt.artifacts) ? evt.artifacts : [];
  const data = evt.data ?? null;

  const details = document.createElement('div');
  details.className = 'viewer';

  const kv = document.createElement('div');
  kv.className = 'kv';
  kv.innerHTML = `
    <div class="k">type</div><div class="mono">${escapeHtml(evt.type || '')}</div>
    <div class="k">time</div><div class="mono">${escapeHtml(toLocalTime(evt.tsMs || 0))}</div>
    <div class="k">session</div><div class="mono">${escapeHtml(evt.sessionId || '')}</div>
    <div class="k">turn</div><div class="mono">${escapeHtml(evt.turnNumber != null ? String(evt.turnNumber) : '')}</div>
    <div class="k">turnId</div><div class="mono">${escapeHtml(evt.turnId || '')}</div>
  `;
  details.appendChild(kv);

  if (data != null) {
    details.appendChild(divider("data"));
    const pre = document.createElement('pre');
    pre.textContent = prettyJson(data);
    details.appendChild(pre);
  }

  if (artifacts.length > 0) {
    details.appendChild(divider("artifacts"));
    const list = document.createElement('div');
    list.className = 'artifact-list';
    for (const a of artifacts) {
      const chip = document.createElement('div');
      chip.className = 'artifact';
      chip.textContent = `${a.kind || 'artifact'}: ${a.path || ''}`;
      chip.title = a.path || '';
      chip.addEventListener('click', () => previewArtifact(a));
      list.appendChild(chip);
    }
    details.appendChild(list);
  }

  eventDetails.innerHTML = "";
  eventDetails.appendChild(details);
}

function divider(label) {
  const d = document.createElement('div');
  d.className = 'hint';
  d.style.marginTop = '10px';
  d.textContent = label;
  return d;
}

function escapeHtml(s) {
  return String(s)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

async function previewArtifact(artifact) {
  const relPath = artifact.path;
  const mime = artifact.mimeType || '';
  if (!relPath) return;

  const f = getFileByRelativePath(relPath);
  if (!f) {
    artifactViewer.textContent = `Missing file: ${relPath}`;
    return;
  }

  artifactViewer.innerHTML = "";
  const wrap = document.createElement('div');
  wrap.className = 'viewer';

  const header = document.createElement('div');
  header.className = 'hint';
  header.textContent = `${artifact.kind || 'artifact'} • ${relPath} • ${mime || f.type || 'unknown'}`;
  wrap.appendChild(header);

  if ((mime || f.type).startsWith('image/')) {
    const url = URL.createObjectURL(f);
    const img = document.createElement('img');
    img.src = url;
    wrap.appendChild(img);
  } else {
    const text = await f.text();
    const pre = document.createElement('pre');
    pre.textContent = tryPretty(text);
    wrap.appendChild(pre);
  }

  artifactViewer.appendChild(wrap);
}

function tryPretty(text) {
  const t = String(text || '');
  const trimmed = t.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try { return JSON.stringify(JSON.parse(trimmed), null, 2); } catch { return t; }
  }
  return t;
}


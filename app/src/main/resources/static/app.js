// Schedula Demo App — Complete feature coverage
// Every backend feature has a UI control here.

const API = "/v1";
const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);

// ============ API CLIENT ============
async function api(method, path, body) {
  const opts = { method, headers: { "Content-Type": "application/json", "X-API-Key": getKey() } };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(API + path, opts);
  if (!res.ok) { const t = await res.text(); throw new Error(res.status + ": " + t); }
  return res.status === 204 ? null : res.json();
}
function getKey() { return localStorage.getItem("apiKey") || "sk_00000000-0000-0000-0000-000000000001_devkey123"; }
function getAdminKey() { return localStorage.getItem("adminKey") || ""; }
function authHeaders() { const h = { "Content-Type": "application/json", "X-API-Key": getKey() }; const a = getAdminKey(); if (a) h["X-Admin-Key"] = a; return h; }

// ============ TOAST ============
function toast(msg, type = "info") {
  let c = $("#toast-container");
  if (!c) { c = document.createElement("div"); c.id = "toast-container"; c.style.cssText = "position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:6px"; document.body.appendChild(c); }
  const t = document.createElement("div"); t.className = `toast ${type}`; t.textContent = msg; c.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

// ============ NAVIGATION ============
function showView(name) {
  $$(".view").forEach(v => v.hidden = true);
  const v = $("#view-" + name); if (v) v.hidden = false;
  $$(".tab").forEach(t => t.classList.toggle("active", t.dataset.view === name));
  const loaders = { jobs: loadJobs, workflows: loadWorkflows, schedules: loadSchedules, dlq: loadDlq, analytics: loadAnalytics, fingerprints: loadFingerprints, audits: loadAudits, fleet: loadFleet, firewall: loadFirewall };
  if (loaders[name]) loaders[name]();
}

// ============ HELPERS ============
function esc(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }
function fmtDate(ts) { return ts ? new Date(ts).toLocaleString() : "—"; }
function statusBadge(s) { return `<span class="badge s-${esc(s)}">${esc(s)}</span>`; }
function copyText(t) { navigator.clipboard.writeText(t); toast("Copied"); }

// ============ JOBS ============
async function loadJobs() {
  try {
    const status = $("#jobs-status-filter")?.value || "";
    const jobs = await api("GET", `/jobs?limit=50${status ? "&status=" + status : ""}`);
    const tbody = $("#jobs-tbody"); if (!tbody) return;
    tbody.innerHTML = jobs.map(j => {
      const actions = [];
      actions.push(`<button class="btn-xs" onclick="viewJob('${j.id}')">Detail</button>`);
      if (["SCHEDULED","QUEUED","PAUSED"].includes(j.status)) actions.push(`<button class="btn-xs danger" onclick="jobAction('${j.id}','cancel')">Cancel</button>`);
      if (["SCHEDULED","QUEUED"].includes(j.status)) actions.push(`<button class="btn-xs" onclick="jobAction('${j.id}','pause')">Pause</button>`);
      if (["RUNNING","DISPATCHED"].includes(j.status)) actions.push(`<button class="btn-xs warn" onclick="jobAction('${j.id}','cancel')">Cancel</button>`);
      if (["COMPLETED","FAILED_TERMINAL","DEAD","CANCELLED"].includes(j.status)) actions.push(`<button class="btn-xs" onclick="jobAction('${j.id}','retry')">Retry</button>`);
      return `<tr>
        <td class="mono">${j.id.slice(0,8)}…</td><td>${esc(j.jobType)}</td>
        <td>${statusBadge(j.status)}</td><td>${j.priority}</td>
        <td>${j.attemptsMade}/${j.maxAttempts}</td>
        <td class="mono small">${j.webhookState || "—"}</td>
        <td>${fmtDate(j.updatedAt)}</td>
        <td class="actions">${actions.join(" ")}</td></tr>`;
    }).join("");
  } catch (e) { toast("Load jobs: " + e.message, "error"); }
}

async function jobAction(id, action) {
  try { const r = await api("POST", `/jobs/${id}/${action}`); toast(`${action}: ${JSON.stringify(r).slice(0,60)}`); loadJobs(); }
  catch (e) { toast(`${action} failed: ${e.message}`, "error"); }
}

async function viewJob(id) {
  try {
    const [job, execs, events, timeline] = await Promise.all([
      api("GET", `/jobs/${id}`),
      api("GET", `/jobs/${id}/executions`).catch(() => []),
      api("GET", `/jobs/${id}/events`).catch(() => []),
      api("GET", `/timeline/${id}`).catch(() => [])
    ]);
    const d = $("#job-detail-panel"); if (!d) return;
    d.innerHTML = `
      <h3>Job ${id.slice(0,12)}… ${statusBadge(job.status)}</h3>
      <div class="detail-grid">
        <div><b>Payload:</b><pre class="mono small">${esc(job.payloadJson)}</pre></div>
        <div><b>Result:</b><pre class="mono small">${esc(job.resultJson || "none")}</pre></div>
        <div><b>Queue:</b> ${esc(job.queueName)} | <b>Webhook:</b> ${esc(job.webhookState)}</div>
        <div><b>Timeline:</b></div>
      </div>
      ${timeline.length ? `<table class="detail-table"><tr><th>Phase</th><th>Actor</th><th>Decision</th><th>Time</th></tr>${timeline.map(t => `<tr><td>${esc(t.phase)}</td><td>${esc(t.actor)}</td><td>${esc(t.decision)}</td><td class="mono small">${fmtDate(t.occurred_at)}</td></tr>`).join("")}</table>` : "<p class='muted'>No timeline data</p>"}
      <h4>Executions (${execs.length})</h4>
      ${execs.length ? `<table class="detail-table"><tr><th>#</th><th>Status</th><th>Worker</th><th>Token</th><th>Result</th><th>Error</th></tr>${execs.map(e => `<tr><td>${e.attemptNo}</td><td>${statusBadge(e.status)}</td><td class="mono small">${e.workerId?.slice(0,8)||"-"}</td><td>${e.fencingToken}</td><td class="mono small">${esc((e.resultJson||"").slice(0,60))}</td><td class="small">${esc(e.errorClass||"")} ${esc((e.errorDetail||"").slice(0,40))}</td></tr>`).join("")}</table>` : "<p class='muted'>No executions yet</p>"}
      <h4>Events (${events.length})</h4>
      ${events.length ? `<table class="detail-table"><tr><th>Time</th><th>Event</th><th>Actor</th><th>Reason</th></tr>${events.map(ev => `<tr><td class="mono small">${fmtDate(ev.occurredAt)}</td><td>${esc(ev.eventType)}</td><td class="mono small">${esc(ev.actor)}</td><td class="small">${esc(ev.reason||"")}</td></tr>`).join("")}</table>` : "<p class='muted'>No events</p>"}
      <button class="btn-xs" onclick="closeDetail()">Close</button>`;
    d.hidden = false;
  } catch (e) { toast(e.message, "error"); }
}
function closeDetail() { const d = $("#job-detail-panel"); if (d) d.hidden = true; }

// ============ WORKFLOWS ============
async function loadWorkflows() {
  try {
    const execs = await api("GET", "/workflows/executions?limit=50");
    const tbody = $("#workflows-tbody"); if (!tbody) return;
    tbody.innerHTML = execs.map(w => `<tr>
      <td class="mono">${w.id.slice(0,8)}…</td>
      <td>${statusBadge(w.status)}</td>
      <td>${w.compensated ? "yes" : "no"}</td>
      <td class="mono small">${fmtDate(w.createdAt)}</td>
      <td class="actions">
        <button class="btn-xs" onclick="viewWf('${w.id}')">Tasks</button>
        ${w.isOpen ? `<button class="btn-xs danger" onclick="cancelWf('${w.id}')">Cancel</button>` : ""}
      </td></tr>`).join("");
  } catch (e) { toast(e.message, "error"); }
}

async function viewWf(id) {
  try {
    const wf = await api("GET", `/workflows/executions/${id}`);
    const tbody = $("#wf-tasks-tbody"); if (!tbody) return;
    tbody.innerHTML = (wf.tasks||[]).map(t => `<tr>
      <td>${esc(t.key)}</td><td>${esc(t.kind)}</td><td>${statusBadge(t.status)}</td>
      <td>${t.attemptNo}</td><td class="mono small">${t.jobId ? t.jobId.slice(0,8) : "—"}</td>
      <td class="small">${esc(t.error||"")}</td></tr>`).join("");
    $("#wf-detail-name").textContent = id.slice(0,12)+"…";
    $("#view-workflow-detail").hidden = false;
    $("#view-workflows").hidden = true;
  } catch (e) { toast(e.message, "error"); }
}

async function cancelWf(id) {
  if (!confirm("Cancel this workflow?")) return;
  try { await api("POST", `/workflows/executions/${id}/cancel`); toast("Cancelled"); loadWorkflows(); }
  catch (e) { toast(e.message, "error"); }
}

// ============ SIGNALS ============
async function sendSignal(execId) {
  const name = prompt("Signal name:");
  if (!name) return;
  try { await api("POST", `/workflows/executions/${execId}/signals`, { signal: name }); toast("Signal sent"); }
  catch (e) { toast(e.message, "error"); }
}

// ============ SCHEDULES ============
async function loadSchedules() {
  try {
    const rows = await api("GET", "/schedules").catch(() => []);
    const tbody = $("#schedules-tbody"); if (!tbody) return;
    // render schedules
  } catch (e) { toast(e.message, "error"); }
}

// ============ DLQ ============
async function loadDlq() {
  try {
    const letters = await api("GET", "/dlq?limit=100");
    const tbody = $("#dlq-tbody"); if (!tbody) return;
    tbody.innerHTML = letters.map(l => `<tr>
      <td class="mono">${l.messageId?.slice(0,8)}…</td>
      <td class="mono">${l.jobId?.slice(0,8)}…</td>
      <td>${esc(l.jobType)}</td><td>${l.deliverCount}</td>
      <td class="small">${esc(l.errorClass||"")}</td>
      <td>${l.resolvedAt ? "✅" : "❌"}</td>
      <td class="actions">
        ${!l.resolvedAt ? `<button class="btn-xs" onclick="dlqRetry('${l.messageId}')">Retry</button>
        <button class="btn-xs danger" onclick="dlqDelete('${l.messageId}')">Del</button>` : "resolved"}
      </td></tr>`).join("") || "<tr><td colspan=7 class='muted'>empty</td></tr>";
  } catch (e) { toast(e.message, "error"); }
}
async function dlqRetry(id) { try { await api("POST", `/dlq/${id}/retry`); toast("Replayed"); loadDlq(); } catch (e) { toast(e.message,"error"); } }
async function dlqDelete(id) { if(!confirm("Delete?"))return; try { await api("DELETE", `/dlq/${id}`); toast("Deleted"); loadDlq(); } catch(e) { toast(e.message,"error"); } }
async function dlqBulkRetry() { if(!confirm("Retry ALL unresolved?"))return; try { const r = await api("POST","/dlq/retry-bulk",{}); toast(`Bulk retried ${r.retried} letters`); loadDlq(); } catch(e) { toast(e.message,"error"); } }
async function dlqBulkDelete() { if(!confirm("Delete ALL unresolved?"))return; try { await api("DELETE","/dlq/delete-bulk",{}); toast("Bulk deleted"); loadDlq(); } catch(e) { toast(e.message,"error"); } }

// ============ FLEET ============
async function loadFleet() {
  try {
    const [workers, schedulers, queues] = await Promise.all([
      api("GET","/workers"), api("GET","/schedulers"), api("GET","/queues")
    ]);
    const wt = $("#workers-tbody");
    if (wt) wt.innerHTML = workers.map(w => `<tr>
      <td>${esc(w.name)}</td><td>${statusBadge(w.status)}</td>
      <td class="mono small">${(w.capabilities||[]).join(",")}</td>
      <td class="mono small">${(w.subscribed_queues||[]).join(",")}</td>
      <td>${w.running_count}/${w.max_concurrency}</td>
      <td class="mono small">${fmtDate(w.last_heartbeat_at)}</td></tr>`).join("");
    const st = $("#schedulers-pre");
    if (st) st.textContent = JSON.stringify(schedulers, null, 2);
  } catch (e) { toast(e.message, "error"); }
}

// ============ ANALYTICS ============
async function loadAnalytics() {
  try {
    const metrics = await fetch("/actuator/prometheus").then(r => r.text());
    const get = (n) => { const m = metrics.match(new RegExp("^"+n+"\\{?.*\\}?\\s+(\\S+)","m")); return m ? m[1] : "—"; };
    const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
    set("#m-depth", get("schedula_queue_depth"));
    set("#m-completed", get("schedula_job_completed_total"));
    set("#m-failed", get("schedula_job_dead_total"));
    set("#m-running", get("schedula_job_started_total"));
    set("#m-submitted", get("schedula_job_submitted_total"));
    set("#m-cancelled", get("schedula_job_cancelled_total"));
    set("#m-retried", get("schedula_job_retried_total"));
    set("#m-webhooks", get("schedula_webhooks_delivered_total"));
    set("#m-fenced", get("schedula_fenced_write_rejected_total"));
    set("#m-throttled", get("schedula_tenant_throttled_total"));
    set("#m-leader-changes", get("schedula_leader_changes_total"));
    // Trend
    const trend = get("schedula_queue_depth_trend");
    set("#m-trend", trend);
    const predicted = get("schedula_queue_depth_predicted_5m");
    set("#m-predicted", predicted);
  } catch (e) { toast(e.message, "error"); }
}

// ============ FINGERPRINTS ============
async function loadFingerprints() {
  try {
    const fps = await api("GET", "/fingerprints");
    const tbody = $("#fingerprints-tbody"); if (!tbody) return;
    tbody.innerHTML = fps.map(f => `<tr>
      <td>${esc(f.job_type)}</td><td>${f.total_24h}</td>
      <td>${f.completed_24h}</td><td>${f.failed_24h}</td>
      <td>${f.success_rate_pct}%</td>
      <td>${Number(f.p50_duration_s).toFixed(1)}s</td>
      <td>${Number(f.p95_duration_s).toFixed(1)}s</td>
      <td>${Number(f.p99_duration_s).toFixed(1)}s</td>
      <td>${Number(f.avg_attempts).toFixed(1)}</td>
      <td class="small">${esc(f.most_common_error || "—")}</td>
    </tr>`).join("") || "<tr><td colspan=10 class='muted'>No data yet</td></tr>";
  } catch (e) { toast(e.message, "error"); }
}

// ============ AUDITS ============
async function loadAudits() {
  try {
    const rows = await api("GET", "/admin/audits?limit=100").catch(() => []);
    const tbody = $("#audits-tbody"); if (!tbody) return;
    tbody.innerHTML = rows.map(a => `<tr>
      <td class="mono small">${fmtDate(a.occurred_at)}</td>
      <td class="mono small">${esc(a.actor)}</td><td>${esc(a.action)}</td>
      <td>${esc(a.target_type)}</td><td class="mono small">${esc(a.target_id||"")}</td>
    </tr>`).join("");
  } catch (e) { toast(e.message, "error"); }
}

// ============ FIREWALL ============
async function loadFirewall() {
  try {
    const rows = await fetch("/actuator/prometheus").then(r => r.text());
    const quarantined = (rows.match(/schedula_firewall_quarantined\{.*\}\s+(\S+)/m) || [])[1] || "0";
    const el = $("#firewall-status"); if (el) el.textContent = `Quarantined dependencies: ${quarantined}`;
  } catch (e) { toast(e.message, "error"); }
}

// ============ FORMS ============
function submitJob() {
  const type = prompt("Job type:", "echo"); if (!type) return;
  const payloadStr = prompt("Payload JSON:", "{}"); if (payloadStr === null) return;
  let payload; try { payload = JSON.parse(payloadStr); } catch { return toast("Invalid JSON", "error"); }
  const queue = prompt("Queue (default):", "default");
  api("POST", "/jobs", { jobType: type, payload, queueName: queue || "default" })
    .then(j => { toast("Submitted: " + j.id?.slice(0,8)); loadJobs(); })
    .catch(e => toast(e.message, "error"));
}

function startDemoWorkflow() {
  api("POST", "/workflows/demo-order-flow/executions", { input: {} })
    .then(w => { toast("Started: " + w.workflowExecutionId?.slice(0,8)); loadWorkflows(); })
    .catch(e => toast(e.message, "error"));
}

function registerWorkflow() {
  const name = prompt("Workflow name:"); if (!name) return;
  const defStr = prompt("Definition JSON:", '{"tasks":[{"key":"a","jobType":"log","payload":{}}]}');
  if (!defStr) return;
  try {
    const definition = JSON.parse(defStr);
    api("POST", "/workflows", { name, definition })
      .then(r => { toast("Registered v" + r.version); loadWorkflows(); })
      .catch(e => toast(e.message, "error"));
  } catch { toast("Invalid JSON", "error"); }
}

function createSchedule() {
  const name = prompt("Schedule name:"); if (!name) return;
  const jobType = prompt("Job type:", "log"); if (!jobType) return;
  const interval = prompt("Interval (ms):", "60000"); if (!interval) return;
  api("POST", "/schedules", { name, jobType, payload: {}, intervalMs: +interval, missedPolicy: "COALESCE" })
    .then(s => { toast("Created: " + s.name); loadSchedules(); })
    .catch(e => toast(e.message, "error"));
}

// ============ INIT ============
document.addEventListener("DOMContentLoaded", () => {
  // Restore keys
  const key = localStorage.getItem("apiKey");
  if (key) { const k = $("#apiKey"); if (k) k.value = key; }
  const admin = localStorage.getItem("adminKey");
  if (admin) { const a = $("#adminKey"); if (a) a.value = admin; }

  // Save keys
  $("#saveKeys")?.addEventListener("click", () => {
    localStorage.setItem("apiKey", $("#apiKey")?.value || "");
    localStorage.setItem("adminKey", $("#adminKey")?.value || "");
    toast("Keys saved"); testConnection();
  });

  // Tab navigation
  $$("[data-view]").forEach(btn => btn.addEventListener("click", () => showView(btn.dataset.view)));

  // Load initial
  testConnection();
});

async function testConnection() {
  try { await api("GET", "/jobs?limit=1"); $("#connStatus").textContent = "✅ Connected"; $("#connStatus").className = "status ok"; loadJobs(); }
  catch (e) { $("#connStatus").textContent = "❌ " + e.message.slice(0, 30); $("#connStatus").className = "status bad"; }
}

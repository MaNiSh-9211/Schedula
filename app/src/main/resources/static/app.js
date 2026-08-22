/* Schedula admin UI — vanilla JS against the REST API. No build step. */
const $ = (s) => document.querySelector(s);
const api = {
  key: localStorage.getItem("schedula.key") || "",
  admin: localStorage.getItem("schedula.admin") || "",
};

function headers() {
  const h = { "Content-Type": "application/json" };
  if (api.key) h["X-API-Key"] = api.key;
  if (api.admin) h["X-Admin-Key"] = api.admin;
  return h;
}

async function call(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { ...headers(), ...(opts.headers || {}) } });
  if (!res.ok) {
    let detail = res.status + " " + res.statusText;
    try { const body = await res.json(); if (body.detail) detail = body.detail; } catch {}
    throw new Error(detail);
  }
  return res.status === 204 ? null : res.json();
}

function toast(msg, bad) {
  const t = $("#toast");
  t.textContent = msg;
  t.style.borderLeftColor = bad ? "var(--bad)" : "var(--accent)";
  t.classList.add("show");
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove("show"), 3500);
}

function esc(s) { return String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function fmt(ts) { return ts ? new Date(ts).toLocaleString() : "—"; }
function badge(status) { return `<span class="status s-${esc(status)}">${esc(status)}</span>`; }

/* ---------- navigation ---------- */
document.querySelectorAll("nav button").forEach(b =>
  b.onclick = () => {
    document.querySelectorAll("nav button").forEach(x => x.classList.remove("active"));
    document.querySelectorAll(".view").forEach(v => v.classList.remove("active"));
    b.classList.add("active");
    $("#" + "view-" + b.dataset.view).classList.add("active");
    ({ overview: loadOverview, jobs: loadJobs, workflows: loadWorkflows,
       schedules: loadSchedules, dlq: loadDlq, fleet: loadFleet, audits: loadAudits }[b.dataset.view] || (() => {}))();
  });

/* ---------- key handling ---------- */
$("#savekeys").onclick = () => {
  api.key = $("#apikey").value.trim();
  api.admin = $("#adminkey").value.trim();
  localStorage.setItem("schedula.key", api.key);
  localStorage.setItem("schedula.admin", api.admin);
  testConnection();
};
$("#apikey").value = api.key;
$("#adminkey").value = api.admin;

async function testConnection() {
  try {
    await call("/v1/jobs?limit=1");
    $("#connstate").textContent = "connected";
    $("#connstate").className = "ok";
    loadOverview();
  } catch (e) {
    $("#connstate").textContent = "auth failed";
    $("#connstate").className = "bad";
    toast(e.message, true);
  }
}

/* ---------- overview ---------- */
async function loadOverview() {
  try {
    const [queues, schedulers, metrics] = await Promise.all([
      call("/v1/queues"), call("/v1/schedulers"),
      fetch("/actuator/prometheus").then(r => r.text())
    ]);
    const qbody = $("#overview-queues tbody");
    qbody.innerHTML = queues.map(q =>
      `<tr><td class="mono">${esc(q.queue_name)}</td><td>${q.ready}</td>
       <td>${q.claimed}</td><td>${q.deadlettered}</td></tr>`).join("");

    const depth = (metrics.match(/^schedula_queue_depth\{.*\} (\S+)/m) || [])[1] || "?";
    const throttled = (metrics.match(/^schedula_tenant_throttled_total\{.*\} (\S+)/m) || [])[1] || "0";
    const dead = queues.reduce((a, q) => a + Number(q.deadlettered), 0);
    const ready = queues.reduce((a, q) => a + Number(q.ready), 0);
    const nodes = schedulers.nodes ? schedulers.nodes.length : "?";
    const leader = schedulers.leader && schedulers.leader.ownerNodeId !== "none"
      ? schedulers.leader.ownerNodeId.slice(0, 8) + "… (fence " + schedulers.leader.fencingToken + ")"
      : "none elected";

    $("#overview-cards").innerHTML = `
      <div class="card"><b>${ready}</b><span>ready messages</span></div>
      <div class="card"><b>${dead}</b><span>dead letters</span></div>
      <div class="card"><b>${nodes}</b><span>scheduler nodes</span></div>
      <div class="card"><b>${esc(leader)}</b><span>current leader</span></div>
      <div class="card"><b>${esc(depth)}</b><span>queue depth gauge</span></div>
      <div class="card"><b>${esc(throttled)}</b><span>tenant throttles</span></div>`;
    $("#overview-leader").textContent = JSON.stringify(schedulers, null, 2);
  } catch (e) { toast(e.message, true); }
}

/* ---------- jobs ---------- */
async function loadJobs() {
  const status = $("#jobs-status").value;
  try {
    const jobs = await call("/v1/jobs?limit=50" + (status ? "&status=" + status : ""));
    $("#jobs-meta").textContent = jobs.length + " shown (latest 50)";
    $("#jobs-table tbody").innerHTML = jobs.map(j => `
      <tr data-id="${j.id}">
        <td class="mono">${j.id.slice(0, 8)}…</td>
        <td>${esc(j.jobType)}</td>
        <td>${badge(j.status)}</td>
        <td>${j.priority}</td>
        <td>${j.attemptsMade}/${j.maxAttempts}</td>
        <td class="mono small">${fmt(j.scheduledFor)}</td>
        <td class="mono small">${fmt(j.updatedAt)}</td>
        <td class="actions" id="act-${j.id}"></td>
      </tr>`).join("");
    for (const j of jobs) renderActions(j);
  } catch (e) { toast(e.message, true); }
}

function renderActions(j) {
  const cell = document.getElementById("act-" + j.id);
  if (!cell) return;
  const btn = (label, fn, cls) =>
    `<button ${cls ? 'class="' + cls + '"' : ""} onclick="${fn}('${j.id}')">${label}</button>`;
  let html = `<button onclick="showJob('${j.id}')">detail</button>`;
  if (["SCHEDULED","QUEUED","PAUSED"].includes(j.status))
    html += btn("cancel", "cancelJob") + (j.status === "PAUSED" ? btn("resume","resumeJob") : btn("pause","pauseJob"));
  else if (["RUNNING","DISPATCHED"].includes(j.status))
    html += btn("cancel", "cancelJob", "danger");
  else
    html += btn("retry", "retryJob");
  cell.innerHTML = html;
}

window.showJob = async (id) => {
  try {
    const [job, execs, events] = await Promise.all([
      call("/v1/jobs/" + id),
      call("/v1/jobs/" + id + "/executions"),
      call("/v1/jobs/" + id + "/events")
    ]);
    $("#job-detail").classList.remove("hidden");
    $("#jd-id").textContent = id;
    $("#jd-json").textContent = JSON.stringify(job, null, 2);
    $("#jd-exec tbody").innerHTML = execs.map(e => `
      <tr><td>${e.attemptNo}</td><td>${badge(e.status)}</td>
      <td class="mono">${e.workerId ? e.workerId.slice(0,8)+"…" : "—"}</td>
      <td>${e.fencingToken}</td>
      <td class="mono small">${fmt(e.startedAt)}</td>
      <td class="mono small">${fmt(e.finishedAt)}</td>
      <td class="small">${esc(e.errorClass || "")} ${esc((e.errorDetail||"").slice(0,80))}</td></tr>`).join("");
    $("#jd-events tbody").innerHTML = events.map(ev => `
      <tr><td class="mono small">${fmt(ev.occurredAt)}</td><td>${esc(ev.eventType)}</td>
      <td class="mono small">${esc(ev.actor)}</td><td class="small">${esc(ev.reason || "")}</td></tr>`).join("");
  } catch (e) { toast(e.message, true); }
};

async function jobAction(id, action, confirmMsg) {
  if (confirmMsg && !confirm(confirmMsg)) return;
  try {
    await call(`/v1/jobs/${id}/${action}`, { method: "POST" });
    toast(action + " ok: " + id.slice(0, 8));
    loadJobs();
  } catch (e) { toast(e.message, true); }
}
window.cancelJob = (id) => jobAction(id, "cancel");
window.pauseJob = (id) => jobAction(id, "pause");
window.resumeJob = (id) => jobAction(id, "resume");
window.retryJob = (id) => jobAction(id, "retry");

$("#submit-form").onsubmit = async (ev) => {
  ev.preventDefault();
  const f = new FormData(ev.target);
  const body = {};
  body.jobType = f.get("jobType");
  try { body.payload = JSON.parse(f.get("payloadText") || "{}"); }
  catch { return toast("payload is not valid JSON", true); }
  if (+f.get("priority")) body.priority = +f.get("priority");
  if (f.get("scheduledFor")) body.scheduledFor = f.get("scheduledFor");
  if (+f.get("maxAttempts")) body.maxAttempts = +f.get("maxAttempts");
  if (+f.get("timeoutMs")) body.timeoutMs = +f.get("timeoutMs");
  if (+f.get("requiredCpu")) body.requiredCpu = +f.get("requiredCpu");
  if (+f.get("requiredMemMb")) body.requiredMemMb = +f.get("requiredMemMb");
  const caps = (f.get("caps") || "").split(",").map(s => s.trim()).filter(Boolean);
  if (caps.length) body.requiredCapabilities = caps;
  if (f.get("retryPolicyText")) body.retryPolicy = JSON.parse(f.get("retryPolicyText"));
  const idem = f.get("idemKey");
  const h = headers(); h["Content-Type"] = "application/json";
  if (idem) h["Idempotency-Key"] = idem;
  try {
    const res = await fetch("/v1/jobs", { method: "POST", headers: h, body: JSON.stringify(body) });
    const json = await res.json();
    $("#submit-result").textContent = JSON.stringify(json, null, 2);
    toast(res.ok ? "submitted " + json.id : "submit failed (" + res.status + ")", !res.ok);
  } catch (e) { toast(e.message, true); }
};

/* ---------- workflows ---------- */
async function loadWorkflows() {
  try {
    const execs = await call("/v1/workflows/executions?limit=25");
    $("#wf-table tbody").innerHTML = execs.map(e => `
      <tr>
        <td class="mono">${String(e.id).slice(0,8)}…</td>
        <td>${badge(e.status)}</td>
        <td>${e.compensated ? "yes" : "no"}</td>
        <td class="mono small">${fmt(e.createdAt)}</td>
        <td class="actions">
          <button onclick="showWf('${e.id}')">tasks</button>
          ${["RUNNING","FAILING","COMPENSATING"].includes(e.status)
            ? `<button class="danger" onclick="cancelWf('${e.id}')">cancel</button>` : ""}
        </td>
      </tr>`).join("") || "<tr><td colspan=5 class=muted>no executions yet</td></tr>";
  } catch (e) { toast(e.message, true); }
}

window.showWf = async (id) => {
  try {
    const s = await call("/v1/workflows/executions/" + id);
    $("#wf-detail").classList.remove("hidden");
    $("#wft-table tbody").innerHTML = (s.tasks || []).map(t => `
      <tr><td>${esc(t.key)}</td><td>${esc(t.kind)}</td><td>${badge(t.status)}</td>
      <td>${t.attemptNo}</td>
      <td class="mono small">${t.jobId ? `<a href="#" onclick="showJob('${t.jobId}');return false;">${String(t.jobId).slice(0,8)}…</a>` : "—"}</td>
      <td class="small">${esc(t.error || "")}</td></tr>`).join("");
  } catch (e) { toast(e.message, true); }
};
window.cancelWf = async (id) => {
  if (!confirm("cancel this workflow execution?")) return;
  try {
    await call("/v1/workflows/executions/" + id + "/cancel", { method: "POST" });
    toast("workflow cancelled");
    loadWorkflows();
  } catch (e) { toast(e.message, true); }
};

$("#wf-register").onsubmit = async (ev) => {
  ev.preventDefault();
  const f = new FormData(ev.target);
  let def;
  try { def = JSON.parse(f.get("defText")); }
  catch { return toast("definition is not valid JSON", true); }
  try {
    const res = await call("/v1/workflows", {
      method: "POST", body: JSON.stringify({ name: f.get("name"), definition: def })
    });
    $("#wf-reg-result").textContent = JSON.stringify(res, null, 2);
    toast("registered version " + res.version);
  } catch (e) { toast(e.message, true); }
};

$("#wf-start").onsubmit = async (ev) => {
  ev.preventDefault();
  const f = new FormData(ev.target);
  let input;
  try { input = JSON.parse(f.get("inputText") || "{}"); }
  catch { return toast("input is not valid JSON", true); }
  try {
    const res = await call(`/v1/workflows/${encodeURIComponent(f.get("name"))}/executions`,
      { method: "POST", body: JSON.stringify({ input }) });
    toast("started " + res.workflowExecutionId);
    loadWorkflows();
  } catch (e) { toast(e.message, true); }
};

/* ---------- schedules ---------- */
async function loadSchedules() {
  // schedules list endpoint is per-id only; use the jobs-style table from admin SQL view:
  // simplest robust approach — list via /v1/schedules/{id} is not enumerable, so we keep
  // creation + deletion by id captured in this session.
  try {
    const rows = window._knownSchedules || [];
    $("#sched-table tbody").innerHTML = rows.map(s => `
      <tr><td>${esc(s.name)}</td><td>${s.kind}</td>
      <td class="mono small">${esc(s.cronExpr || s.intervalMs)}</td><td>${esc(s.timezone)}</td>
      <td class="mono small">${fmt(s.nextFireAt)}</td><td>${esc(s.missedPolicy)}</td>
      <td class="actions"><button onclick="delSchedule('${s.id}')">delete</button></td></tr>`).join("");
  } catch (e) { toast(e.message, true); }
}

$("#sched-form").onsubmit = async (ev) => {
  ev.preventDefault();
  const f = new FormData(ev.target);
  let payload = {};
  try { payload = JSON.parse(f.get("payloadText") || "{}"); }
  catch { return toast("payload is not valid JSON", true); }
  const body = {
    name: f.get("name"), jobType: f.get("jobType"), payload,
    missedPolicy: f.get("missedPolicy")
  };
  if (f.get("mode") === "cron") {
    body.cronExpr = f.get("cronExpr");
    if (f.get("timezone")) body.timezone = f.get("timezone");
  } else {
    body.intervalMs = +f.get("intervalMs");
  }
  try {
    const s = await call("/v1/schedules", { method: "POST", body: JSON.stringify(body) });
    window._knownSchedules = [s, ...(window._knownSchedules || [])];
    $("#sched-result").textContent = JSON.stringify(s, null, 2);
    toast("schedule created");
    loadSchedules();
  } catch (e) { toast(e.message, true); }
};

window.delSchedule = async (id) => {
  try {
    await call("/v1/schedules/" + id, { method: "DELETE" });
    window._knownSchedules = (window._knownSchedules || []).filter(s => s.id !== id);
    toast("schedule deleted");
    loadSchedules();
  } catch (e) { toast(e.message, true); }
};

/* ---------- dlq ---------- */
async function loadDlq() {
  try {
    const letters = await call("/v1/dlq?limit=100");
    $("#dlq-table tbody").innerHTML = letters.map(l => `
      <tr>
        <td class="mono">${String(l.messageId).slice(0,8)}…</td>
        <td class="mono">${String(l.jobId).slice(0,8)}…</td>
        <td>${esc(l.jobType || "?")}</td>
        <td>${l.deliverCount}</td>
        <td class="small">${esc(l.errorClass || "")} ${esc((l.errorDetail||"").slice(0,60))}</td>
        <td>${l.resolvedAt ? "yes" : "no"}</td>
        <td class="actions">
          ${l.resolvedAt ? "" : `<button onclick="dlqRetry('${l.messageId}')">retry</button>
          <button class="danger" onclick="dlqDelete('${l.messageId}')">delete</button>`}
        </td>
      </tr>`).join("") || "<tr><td colspan=7 class=muted>empty — nothing dead 🎉</td></tr>";
  } catch (e) { toast(e.message, true); }
}
window.dlqRetry = async (id) => {
  try { await call("/v1/dlq/" + id + "/retry", { method: "POST" }); toast("replayed"); loadDlq(); }
  catch (e) { toast(e.message, true); }
};
window.dlqDelete = async (id) => {
  if (!confirm("delete dead letter?")) return;
  try { await call("/v1/dlq/" + id, { method: "DELETE" }); toast("deleted"); loadDlq(); }
  catch (e) { toast(e.message, true); }
};

/* ---------- audits ---------- */
async function loadAudits() {
  try {
    const rows = await call("/v1/admin/audits?limit=100");
    $("#audits-table tbody").innerHTML = rows.map(a => `
      <tr>
        <td class="mono small">${fmt(a.occurred_at)}</td>
        <td class="mono small">${esc(a.actor)}</td>
        <td>${esc(a.action)}</td>
        <td>${esc(a.target_type)}</td>
        <td class="mono small">${esc(a.target_id || "")}</td>
      </tr>`).join("") || "<tr><td colspan=5 class=muted>no audit entries</td></tr>";
  } catch (e) { toast(e.message, true); }
}

/* ---------- fleet ---------- */
async function loadFleet() {
  try {
    const [workers, schedulers] = await Promise.all([call("/v1/workers"), call("/v1/schedulers")]);
    $("#workers-table tbody").innerHTML = workers.map(w => `
      <tr><td>${esc(w.name)}</td><td>${badge(w.status)}</td><td>${esc(w.version)}</td>
      <td class="mono small">${esc((w.capabilities||[]).join(", "))}</td>
      <td>${w.running_count}/${w.max_concurrency}</td>
      <td class="mono small">${fmt(w.last_heartbeat_at)}</td></tr>`).join("");
    $("#schedulers-pre").textContent = JSON.stringify(schedulers, null, 2);
  } catch (e) { toast(e.message, true); }
}

loadOverview();
setInterval(() => {
  if ($("#view-overview").classList.contains("active")) loadOverview();
}, 10_000);

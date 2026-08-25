"use strict";
var API = "/v1";
var $ = function(s) { return document.querySelector(s); };
var $$ = function(s) { return document.querySelectorAll(s); };

function getKey() { try { return localStorage.getItem("apiKey") || "sk_00000000-0000-0000-0000-000000000001_devkey123"; } catch(e) { return ""; } }
function getAdminKey() { try { return localStorage.getItem("adminKey") || ""; } catch(e) { return ""; } }
function saveKeys() {
    try {
        localStorage.setItem("apiKey", $("#in-api-key").value);
        localStorage.setItem("adminKey", $("#in-admin-key").value);
    } catch(e) {}
    toast("Keys saved", "success");
    showView(currentView);
}

async function api(method, path, body) {
    var opts = { method: method, headers: { "Content-Type": "application/json", "X-API-Key": getKey() } };
    var admin = getAdminKey();
    if (admin) opts.headers["X-Admin-Key"] = admin;
    if (body) opts.body = JSON.stringify(body);
    var res = await fetch(API + path, opts);
    if (!res.ok) {
        var txt = await res.text();
        try { var j = JSON.parse(txt); if (j.detail) txt = j.detail; } catch(e) {}
        throw new Error(res.status + ": " + txt);
    }
    return res.status === 204 ? null : res.json();
}

function toast(msg, type) {
    var c = document.getElementById("toast-container");
    if (!c) { c = document.createElement("div"); c.id = "toast-container"; c.style.cssText = "position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:6px"; document.body.appendChild(c); }
    var t = document.createElement("div");
    t.style.cssText = "padding:10px 16px;border-radius:6px;background:#161b22;border:1px solid #30363d;color:#c9d1d9;font-size:13px;max-width:400px";
    if (type === "error") { t.style.borderColor = "#f85149"; t.style.color = "#f85149"; }
    if (type === "success") { t.style.borderColor = "#3fb950"; t.style.color = "#3fb950"; }
    t.textContent = msg;
    c.appendChild(t);
    setTimeout(function() { t.remove(); }, 4000);
}

function esc(s) { var d = document.createElement("div"); d.textContent = s || ""; return d.innerHTML; }
function fmtDate(ts) { return ts ? new Date(ts).toLocaleString() : "-"; }
function badge(s) { return '<span class="badge s-' + (s||"").toLowerCase() + '">' + (s||"-") + "</span>"; }

var currentView = "dashboard";
function showView(name) {
    currentView = name;
    var main = document.getElementById("main-content");
    var renderers = {
        dashboard: renderDashboard, jobs: renderJobs, workflows: renderWorkflows,
        schedules: renderSchedules, dlq: renderDlq, fleet: renderFleet,
        fingerprints: renderFingerprints, analytics: renderAnalytics, admin: renderAdmin
    };
    if (renderers[name]) main.innerHTML = renderers[name]();
    var loaders = {
        dashboard: loadDashboard, jobs: loadJobs, workflows: loadWorkflows,
        schedules: loadSchedules, dlq: loadDlq, fleet: loadFleet,
        fingerprints: loadFingerprints, analytics: loadAnalytics, admin: loadAdmin
    };
    if (loaders[name]) loaders[name]();
    document.querySelectorAll("#main-nav a").forEach(function(a) {
        a.classList.toggle("active", a.getAttribute("data-view") === name);
    });
}

// ===== DASHBOARD =====
function renderDashboard() {
    return '<div class="cards" id="dash-cards"></div>' +
        '<div class="section-title">Scheduler Cluster</div><pre class="mono" id="dash-leader"></pre>' +
        '<div class="section-title">Queues</div><table id="dash-queues"><thead><tr><th>Queue</th><th>Ready</th><th>Claimed</th><th>Dead</th></tr></thead><tbody></tbody></table>';
}
async function loadDashboard() {
    try {
        var results = await Promise.all([
            api("GET", "/queues"), api("GET", "/schedulers"),
            fetch("/actuator/prometheus").then(function(r) { return r.text(); })
        ]);
        var queues = results[0], schedulers = results[1], metrics = results[2];
        function m(name) { var match = metrics.match(new RegExp("^" + name + "\\{?.*\\}?\\s+(\\S+)", "m")); return match ? match[1] : "-"; }
        var cards = document.getElementById("dash-cards");
        if (cards) cards.innerHTML =
            '<div class="card"><b>' + m("schedula_queue_depth") + '</b><span>Queue Depth</span></div>' +
            '<div class="card"><b>' + m("schedula_job_completed_total") + '</b><span>Completed</span></div>' +
            '<div class="card"><b>' + m("schedula_job_dead_total") + '</b><span>Dead</span></div>' +
            '<div class="card"><b>' + m("schedula_job_running_total") + '</b><span>Running</span></div>' +
            '<div class="card"><b>' + (schedulers.nodes ? schedulers.nodes.length : "?") + '</b><span>Scheduler Nodes</span></div>' +
            '<div class="card"><b>' + (schedulers.leader && schedulers.leader.ownerNodeId !== "none" ? schedulers.leader.fencingToken : "-") + '</b><span>Fencing Token</span></div>';
        var leader = document.getElementById("dash-leader");
        if (leader) leader.textContent = JSON.stringify(schedulers, null, 2);
        var qt = document.querySelector("#dash-queues tbody");
        if (qt) qt.innerHTML = queues.map(function(q) {
            return "<tr><td>" + esc(q.queue_name) + "</td><td>" + q.ready + "</td><td>" + q.claimed + "</td><td>" + q.deadlettered + "</td></tr>";
        }).join("");
    } catch (e) { toast("Dashboard: " + e.message, "error"); }
}

// ===== JOBS =====
function renderJobs() {
    return '<div class="toolbar"><h2>Jobs</h2><div class="toolbar-right">' +
        '<select id="jobs-filter"><option value="">All</option><option value="SCHEDULED">SCHEDULED</option><option value="QUEUED">QUEUED</option><option value="RUNNING">RUNNING</option><option value="COMPLETED">COMPLETED</option><option value="DEAD">DEAD</option><option value="CANCELLING">CANCELLING</option></select>' +
        '<button class="btn primary" onclick="showSubmitModal()">+ Submit</button>' +
        '<button class="btn" onclick="showBatchModal()">+ Batch</button>' +
        '<button class="btn" onclick="loadJobs()">Refresh</button></div></div>' +
        '<table><thead><tr><th>ID</th><th>Type</th><th>Queue</th><th>Status</th><th>Priority</th><th>Attempts</th><th>Webhook</th><th>Actions</th></tr></thead><tbody id="jobs-tbody"></tbody></table>' +
        '<div id="job-detail" class="hidden" style="margin-top:16px"></div>' +
        getSubmitModalHtml() + getBatchModalHtml();
}
async function loadJobs() {
    try {
        var filter = document.getElementById("jobs-filter");
        var status = filter ? filter.value : "";
        var jobs = await api("GET", "/jobs?limit=50" + (status ? "&status=" + status : ""));
        var tbody = document.getElementById("jobs-tbody");
        if (!tbody) return;
        tbody.innerHTML = jobs.map(function(j) {
            var actions = '<button class="btn-xs" onclick="viewJobDetail(\\'' + j.id + '\\')">Detail</button>';
            if (["SCHEDULED","QUEUED","PAUSED"].indexOf(j.status) >= 0) actions += ' <button class="btn-xs danger" onclick="jobAction(\\'' + j.id + '\\',\\'cancel\\')">Cancel</button>';
            if (["SCHEDULED","QUEUED"].indexOf(j.status) >= 0) actions += ' <button class="btn-xs warn" onclick="jobAction(\\'' + j.id + '\\',\\'pause\\')">Pause</button>';
            if (j.status === "PAUSED") actions += ' <button class="btn-xs" onclick="jobAction(\\'' + j.id + '\\',\\'resume\\')">Resume</button>';
            if (["RUNNING","DISPATCHED"].indexOf(j.status) >= 0) actions += ' <button class="btn-xs danger" onclick="jobAction(\\'' + j.id + '\\',\\'cancel\\')">Cancel</button>';
            if (["COMPLETED","FAILED_TERMINAL","DEAD","CANCELLED"].indexOf(j.status) >= 0) actions += ' <button class="btn-xs" onclick="jobAction(\\'' + j.id + '\\',\\'retry\\')">Retry</button>';
            return "<tr><td class='mono'>" + j.id.slice(0,8) + "</td><td>" + esc(j.jobType) + "</td><td>" + esc(j.queueName || "default") + "</td><td>" + badge(j.status) + "</td><td>" + j.priority + "</td><td>" + j.attemptsMade + "/" + j.maxAttempts + "</td><td>" + (j.webhookState || "-") + "</td><td>" + actions + "</td></tr>";
        }).join("");
    } catch (e) { toast("Load jobs: " + e.message, "error"); }
}

function getSubmitModalHtml() {
    return '<div id="submit-modal" class="modal" hidden><div class="modal-content"><h3>Submit Job</h3>' +
        '<label>Job Type *<input id="sub-type" value="echo" /></label>' +
        '<label>Payload (JSON)<textarea id="sub-payload">{}</textarea></label>' +
        '<div class="form-grid"><label>Queue<input id="sub-queue" value="default" /></label>' +
        '<label>Priority<input id="sub-priority" type="number" value="0" /></label>' +
        '<label>Max Attempts<input id="sub-attempts" type="number" value="3" /></label>' +
        '<label>Timeout (ms)<input id="sub-timeout" type="number" value="60000" /></label></div>' +
        '<label>Scheduled For (ISO, optional)<input id="sub-scheduled" placeholder="2026-12-25T09:00:00Z" /></label>' +
        '<label>Capabilities (comma-sep)<input id="sub-caps" placeholder="python,gpu" /></label>' +
        '<label>Webhook URL<input id="sub-webhook" placeholder="https://..." /></label>' +
        '<label>Idempotency Key<input id="sub-idem" /></label>' +
        '<div class="btn-row"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" onclick="doSubmitJob()">Submit</button></div></div></div>';
}
function showSubmitModal() { var m = document.getElementById("submit-modal"); if (m) m.hidden = false; }
async function doSubmitJob() {
    var payload = {};
    try { payload = JSON.parse(document.getElementById("sub-payload").value); } catch(e) { return toast("Invalid JSON", "error"); }
    var body = { jobType: document.getElementById("sub-type").value, payload: payload,
        queueName: document.getElementById("sub-queue").value,
        priority: parseInt(document.getElementById("sub-priority").value) || 0,
        maxAttempts: parseInt(document.getElementById("sub-attempts").value) || 3,
        timeoutMs: parseInt(document.getElementById("sub-timeout").value) || 60000 };
    var sched = document.getElementById("sub-scheduled").value;
    if (sched) body.scheduledFor = sched;
    var caps = document.getElementById("sub-caps").value;
    if (caps) body.requiredCapabilities = caps.split(",").map(function(s){return s.trim();});
    var wh = document.getElementById("sub-webhook").value;
    if (wh) body.webhookUrl = wh;
    var idem = document.getElementById("sub-idem").value;
    try {
        var res = await api("POST", "/jobs", body);
        toast("Submitted: " + (res.id || "").slice(0,8), "success");
        closeModal(); loadJobs();
    } catch (e) { toast(e.message, "error"); }
}

function getBatchModalHtml() {
    return '<div id="batch-modal" class="modal" hidden><div class="modal-content"><h3>Batch Submit</h3>' +
        '<label>Jobs JSON (array)<textarea id="batch-jobs" style="min-height:120px">[{"jobType":"log","payload":{"n":1}},{"jobType":"log","payload":{"n":2}}]</textarea></label>' +
        '<div class="btn-row"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" onclick="doBatchSubmit()">Submit Batch</button></div></div></div>';
}
function showBatchModal() { var m = document.getElementById("batch-modal"); if (m) m.hidden = false; }
async function doBatchSubmit() {
    try {
        var jobs = JSON.parse(document.getElementById("batch-jobs").value);
        var res = await api("POST", "/jobs/batch", { jobs: jobs });
        toast("Batch submitted: " + (res.submitted || []).length + " jobs", "success");
        closeModal(); loadJobs();
    } catch (e) { toast(e.message, "error"); }
}

function closeModal() { document.querySelectorAll(".modal").forEach(function(m) { m.hidden = true; }); }

async function jobAction(id, action) {
    try {
        var r = await api("POST", "/jobs/" + id + "/" + action);
        toast(action + ": " + JSON.stringify(r).slice(0, 60), "success");
        loadJobs();
    } catch (e) { toast(action + " failed: " + e.message, "error"); }
}

async function viewJobDetail(id) {
    try {
        var results = await Promise.all([
            api("GET", "/jobs/" + id),
            api("GET", "/jobs/" + id + "/executions").catch(function() { return []; }),
            api("GET", "/jobs/" + id + "/events").catch(function() { return []; }),
            api("GET", "/timeline/" + id).catch(function() { return []; })
        ]);
        var job = results[0], execs = results[1], events = results[2], timeline = results[3];
        var detail = document.getElementById("job-detail");
        if (!detail) return;
        detail.className = "";
        var html = '<div class="section-title">Job ' + id.slice(0,12) + " " + badge(job.status) + '</div>';
        html += '<div class="detail-grid"><div><b>Payload</b><pre class="mono">' + esc(job.payloadJson || "{}") + '</pre></div>';
        html += '<div><b>Config</b><pre class="mono">type=' + esc(job.jobType) + " queue=" + esc(job.queueName||"default") + " attempts=" + job.attemptsMade + "/" + job.maxAttempts + " timeout=" + job.timeoutMs + "ms</pre></div></div>";
        if (timeline && timeline.length > 0) {
            html += '<div class="section-title">Execution Timeline</div><table class="detail-table"><tr><th>Phase</th><th>Actor</th><th>Decision</th><th>Time</th></tr>';
            timeline.forEach(function(t) { html += "<tr><td>" + esc(t.phase) + "</td><td>" + esc(t.actor) + "</td><td>" + esc(t.decision) + "</td><td>" + fmtDate(t.occurred_at) + "</td></tr>"; });
            html += "</table>";
        }
        if (execs && execs.length > 0) {
            html += '<div class="section-title">Executions (' + execs.length + ')</div><table class="detail-table"><tr><th>#</th><th>Status</th><th>Worker</th><th>Token</th><th>Result</th><th>Error</th></tr>';
            execs.forEach(function(e) {
                html += "<tr><td>" + e.attemptNo + "</td><td>" + badge(e.status) + "</td><td class='mono small'>" + (e.workerId ? e.workerId.slice(0,8) : "-") + "</td><td>" + e.fencingToken + "</td><td class='mono small'>" + esc((e.resultJson || "").slice(0,80)) + "</td><td class='small'>" + esc(e.errorClass || "") + " " + esc((e.errorDetail || "").slice(0,50)) + "</td></tr>";
            });
            html += "</table>";
        }
        if (events && events.length > 0) {
            html += '<div class="section-title">Events (' + events.length + ')</div><table class="detail-table"><tr><th>Time</th><th>Event</th><th>Actor</th><th>Reason</th></tr>';
            events.forEach(function(ev) { html += "<tr><td class='mono small'>" + fmtDate(ev.occurredAt) + "</td><td>" + esc(ev.eventType) + "</td><td>" + esc(ev.actor) + "</td><td class='small'>" + esc(ev.reason || "") + "</td></tr>"; });
            html += "</table>";
        }
        html += '<button class="btn-xs" onclick="document.getElementById(\'job-detail\').className=\'hidden\'">Close</button>';
        detail.innerHTML = html;
    } catch (e) { toast(e.message, "error"); }
}

// ===== WORKFLOWS =====
function renderWorkflows() {
    return '<div class="toolbar"><h2>Workflows</h2><div class="toolbar-right">' +
        '<button class="btn primary" onclick="showRegisterModal()">+ Register</button>' +
        '<button class="btn" onclick="startDemoWorkflow()">Start demo-order-flow</button>' +
        '<button class="btn" onclick="loadWorkflows()">Refresh</button></div></div>' +
        '<table><thead><tr><th>ID</th><th>Status</th><th>Compensated</th><th>Created</th><th>Actions</th></tr></thead><tbody id="workflows-tbody"></tbody></table>' +
        '<div id="wf-tasks" class="hidden" style="margin-top:16px"></div>' +
        getRegisterModalHtml();
}
function getRegisterModalHtml() {
    return '<div id="register-modal" class="modal" hidden><div class="modal-content"><h3>Register Workflow</h3>' +
        '<label>Name<input id="wf-name" value="my-workflow" /></label>' +
        '<label>Definition (JSON)<textarea id="wf-def" style="min-height:120px">{\"tasks\":[{\"key\":\"a\",\"jobType\":\"log\",\"payload\":{}},{\"key\":\"b\",\"jobType\":\"log\",\"dependsOn\":[\"a\"]}]}</textarea></label>' +
        '<div class="btn-row"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" onclick="doRegisterWorkflow()">Register</button></div></div></div>';
}
function showRegisterModal() { var m = document.getElementById("register-modal"); if (m) m.hidden = false; }
async function doRegisterWorkflow() {
    try {
        var name = document.getElementById("wf-name").value;
        var def = JSON.parse(document.getElementById("wf-def").value);
        var res = await api("POST", "/workflows", { name: name, definition: def });
        toast("Registered v" + res.version, "success");
        closeModal(); loadWorkflows();
    } catch (e) { toast(e.message, "error"); }
}
async function loadWorkflows() {
    try {
        var execs = await api("GET", "/workflows/executions?limit=50");
        var tbody = document.getElementById("workflows-tbody");
        if (!tbody) return;
        tbody.innerHTML = execs.map(function(w) {
            var actions = '<button class="btn-xs" onclick="viewWfTasks(\\'' + w.id + '\\')">Tasks</button>';
            if (w.status === "RUNNING" || w.status === "FAILING") actions += ' <button class="btn-xs danger" onclick="cancelWf(\\'' + w.id + '\\')">Cancel</button>';
            return "<tr><td class='mono'>" + w.id.slice(0,8) + "</td><td>" + badge(w.status) + "</td><td>" + (w.compensated ? "yes" : "no") + "</td><td>" + fmtDate(w.createdAt) + "</td><td>" + actions + "</td></tr>";
        }).join("");
    } catch (e) { toast(e.message, "error"); }
}
async function startDemoWorkflow() {
    try {
        var r = await api("POST", "/workflows/demo-order-flow/executions", { input: {} });
        toast("Started: " + r.workflowExecutionId.slice(0,8), "success");
        loadWorkflows();
    } catch (e) { toast(e.message, "error"); }
}
async function viewWfTasks(id) {
    try {
        var wf = await api("GET", "/workflows/executions/" + id);
        var div = document.getElementById("wf-tasks");
        if (!div) return;
        div.className = "";
        var html = '<div class="section-title">Tasks for ' + id.slice(0,12) + ' ' + badge(wf.status) + '</div>';
        if (wf.tasks && wf.tasks.length > 0) {
            html += '<table class="detail-table"><tr><th>Key</th><th>Kind</th><th>Status</th><th>Attempt</th><th>Job</th><th>Error</th><th>Signal</th></tr>';
            wf.tasks.forEach(function(t) {
                var actions = "";
                if (t.kind === "SIGNAL" && t.status === "RUNNING") {
                    actions = '<button class="btn-xs" onclick="sendSignalTo(\\'' + id + '\\',\\'' + t.key + '\\')">Send Signal</button>';
                }
                html += "<tr><td>" + esc(t.key) + "</td><td>" + esc(t.kind) + "</td><td>" + badge(t.status) + "</td><td>" + t.attemptNo + "</td><td class='mono small'>" + (t.jobId ? t.jobId.slice(0,8) : "-") + "</td><td class='small'>" + esc(t.error || "") + "</td><td>" + actions + "</td></tr>";
            });
            html += "</table>";
        }
        html += '<button class="btn-xs" onclick="document.getElementById(\'wf-tasks\').className=\'hidden\'">Close</button>';
        div.innerHTML = html;
    } catch (e) { toast(e.message, "error"); }
}
async function sendSignalTo(execId, signalName) {
    try {
        await api("POST", "/workflows/executions/" + execId + "/signals", { signal: signalName });
        toast("Signal sent: " + signalName, "success");
    } catch (e) { toast(e.message, "error"); }
}
async function cancelWf(id) {
    if (!confirm("Cancel workflow " + id.slice(0,8) + "?")) return;
    try { await api("POST", "/workflows/executions/" + id + "/cancel"); toast("Cancelled"); loadWorkflows(); }
    catch (e) { toast(e.message, "error"); }
}

// ===== SCHEDULES =====
function renderSchedules() {
    return '<div class="toolbar"><h2>Schedules</h2><div class="toolbar-right">' +
        '<button class="btn primary" onclick="showScheduleModal()">+ Create</button>' +
        '<button class="btn" onclick="loadSchedules()">Refresh</button></div></div>' +
        '<table><thead><tr><th>Name</th><th>Kind</th><th>Expr</th><th>Timezone</th><th>Next Fire</th><th>Policy</th><th>Target</th><th>Actions</th></tr></thead><tbody id="schedules-tbody"></tbody></table>' +
        getScheduleModalHtml();
}
function getScheduleModalHtml() {
    return '<div id="schedule-modal" class="modal" hidden><div class="modal-content"><h3>Create Schedule</h3>' +
        '<label>Name<input id="sched-name" /></label>' +
        '<label>Job Type<input id="sched-type" value="log" /></label>' +
        '<label>Payload (JSON)<textarea id="sched-payload">{}</textarea></label>' +
        '<label>Mode<select id="sched-mode"><option value="cron">Cron</option><option value="interval">Fixed Interval</option></select></label>' +
        '<label>Cron Expression<input id="sched-cron" placeholder="0 0 9 * * *" /></label>' +
        '<label>Interval (ms)<input id="sched-interval" type="number" value="60000" /></label>' +
        '<label>Timezone<input id="sched-tz" value="UTC" /></label>' +
        '<label>Missed Policy<select id="sched-policy"><option>COALESCE</option><option>SKIP_TO_LATEST</option><option value="RUN_ALL">RUN_ALL (Backfill)</option></select></label>' +
        '<label>Target Workflow (optional)<input id="sched-workflow" placeholder="workflow-name" /></label>' +
        '<div class="btn-row"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" onclick="doCreateSchedule()">Create</button></div></div></div>';
}
function showScheduleModal() { var m = document.getElementById("schedule-modal"); if (m) m.hidden = false; }
async function doCreateSchedule() {
    try {
        var body = { name: document.getElementById("sched-name").value,
            jobType: document.getElementById("sched-type").value,
            payload: JSON.parse(document.getElementById("sched-payload").value),
            missedPolicy: document.getElementById("sched-policy").value,
            timezone: document.getElementById("sched-tz").value };
        var mode = document.getElementById("sched-mode").value;
        if (mode === "cron") body.cronExpr = document.getElementById("sched-cron").value;
        else body.intervalMs = parseInt(document.getElementById("sched-interval").value);
        var targetWf = document.getElementById("sched-workflow").value;
        if (targetWf) body.targetWorkflow = targetWf;
        await api("POST", "/schedules", body);
        toast("Schedule created", "success"); closeModal(); loadSchedules();
    } catch (e) { toast(e.message, "error"); }
}
async function loadSchedules() {
    try {
        var rows = await api("GET", "/schedules").catch(function() { return []; });
        var tbody = document.getElementById("schedules-tbody");
        if (!tbody) return;
        tbody.innerHTML = (rows || []).map(function(s) {
            return "<tr><td>" + esc(s.name) + "</td><td>" + s.kind + "</td><td class='mono small'>" + esc(s.cronExpr || s.intervalMs || "-") + "</td><td>" + esc(s.timezone || "UTC") + "</td><td>" + fmtDate(s.nextFireAt) + "</td><td>" + esc(s.missedPolicy) + "</td><td>" + esc(s.targetWorkflow || "-") + '</td><td><button class="btn-xs danger" onclick="deleteSchedule(\\'' + s.id + '\\')">Delete</button></td></tr>';
        }).join("");
    } catch (e) { toast(e.message, "error"); }
}
async function deleteSchedule(id) {
    if (!confirm("Delete this schedule?")) return;
    try { await api("DELETE", "/schedules/" + id); toast("Deleted"); loadSchedules(); }
    catch (e) { toast(e.message, "error"); }
}

// ===== DLQ =====
function renderDlq() {
    return '<div class="toolbar"><h2>Dead Letter Queue</h2><div class="toolbar-right">' +
        '<button class="btn warn" onclick="dlqBulkRetry()">Bulk Retry All</button>' +
        '<button class="btn danger" onclick="dlqBulkDelete()">Delete All</button>' +
        '<button class="btn" onclick="loadDlq()">Refresh</button></div></div>' +
        '<table><thead><tr><th>Message</th><th>Job</th><th>Type</th><th>Deliveries</th><th>Error</th><th>Resolved</th><th>Actions</th></tr></thead><tbody id="dlq-tbody"></tbody></table>';
}
async function loadDlq() {
    try {
        var letters = await api("GET", "/dlq?limit=100");
        var tbody = document.getElementById("dlq-tbody");
        if (!tbody) return;
        tbody.innerHTML = letters.map(function(l) {
            var actions = "";
            if (!l.resolvedAt) {
                actions = '<button class="btn-xs" onclick="dlqRetry(\\'' + l.messageId + '\\')">Retry</button> ';
                actions += '<button class="btn-xs danger" onclick="dlqDelete(\\'' + l.messageId + '\\')">Delete</button>';
            }
            return "<tr><td class='mono'>" + (l.messageId || "").slice(0,8) + "</td><td class='mono'>" + (l.jobId || "").slice(0,8) + "</td><td>" + esc(l.jobType || "?") + "</td><td>" + l.deliverCount + "</td><td class='small'>" + esc(l.errorClass || "") + "</td><td>" + (l.resolvedAt ? "yes" : "no") + "</td><td>" + actions + "</td></tr>";
        }).join("") || "<tr><td colspan='7' class='muted'>DLQ is empty</td></tr>";
    } catch (e) { toast(e.message, "error"); }
}
async function dlqRetry(id) { try { await api("POST", "/dlq/" + id + "/retry"); toast("Replayed", "success"); loadDlq(); } catch (e) { toast(e.message, "error"); } }
async function dlqDelete(id) { if (!confirm("Delete?")) return; try { await api("DELETE", "/dlq/" + id); toast("Deleted"); loadDlq(); } catch (e) { toast(e.message, "error"); } }
async function dlqBulkRetry() { if (!confirm("Retry ALL unresolved?")) return; try { var r = await api("POST", "/dlq/retry-bulk", {}); toast("Bulk retried: " + (r.retried || 0), "success"); loadDlq(); } catch (e) { toast(e.message, "error"); } }
async function dlqBulkDelete() { if (!confirm("Delete ALL unresolved?")) return; try { await api("DELETE", "/dlq/delete-bulk", {}); toast("Bulk deleted"); loadDlq(); } catch (e) { toast(e.message, "error"); } }

// ===== FLEET =====
function renderFleet() {
    return '<div class="toolbar"><h2>Workers</h2><button class="btn" onclick="loadFleet()">Refresh</button></div>' +
        '<table><thead><tr><th>Name</th><th>Status</th><th>Version</th><th>Capabilities</th><th>Queues</th><th>Running</th><th>Heartbeat</th></tr></thead><tbody id="fleet-workers"></tbody></table>' +
        '<div class="section-title">Schedulers</div><pre class="mono" id="fleet-schedulers"></pre>' +
        '<div class="section-title">Queues</div><table id="fleet-queues"><thead><tr><th>Queue</th><th>Ready</th><th>Claimed</th><th>Dead</th></tr></thead><tbody></tbody></table>';
}
async function loadFleet() {
    try {
        var results = await Promise.all([
            api("GET", "/workers"), api("GET", "/schedulers"), api("GET", "/queues")
        ]);
        var wt = document.getElementById("fleet-workers");
        if (wt) wt.innerHTML = results[0].map(function(w) {
            return "<tr><td>" + esc(w.name) + "</td><td>" + badge(w.status) + "</td><td>" + esc(w.version) + "</td><td class='mono small'>" + (w.capabilities || []).join(",") + "</td><td class='mono small'>" + (w.subscribed_queues || []).join(",") + "</td><td>" + w.running_count + "/" + w.max_concurrency + "</td><td class='mono small'>" + fmtDate(w.last_heartbeat_at) + "</td></tr>";
        }).join("");
        var st = document.getElementById("fleet-schedulers");
        if (st) st.textContent = JSON.stringify(results[1], null, 2);
        var qt = document.querySelector("#fleet-queues tbody");
        if (qt) qt.innerHTML = results[2].map(function(q) {
            return "<tr><td>" + esc(q.queue_name) + "</td><td>" + q.ready + "</td><td>" + q.claimed + "</td><td>" + q.deadlettered + "</td></tr>";
        }).join("");
    } catch (e) { toast(e.message, "error"); }
}

// ===== FINGERPRINTS =====
function renderFingerprints() {
    return '<div class="toolbar"><h2>Job Fingerprints</h2><span class="muted">Auto-computed per-type stats (24h)</span>' +
        '<button class="btn" onclick="loadFingerprints()">Refresh</button></div>' +
        '<table><thead><tr><th>Type</th><th>Total</th><th>OK</th><th>Failed</th><th>Success Rate</th><th>P50</th><th>P95</th><th>P99</th><th>Avg Attempts</th><th>Top Error</th></tr></thead><tbody id="fp-tbody"></tbody></table>';
}
async function loadFingerprints() {
    try {
        var fps = await api("GET", "/fingerprints");
        var tbody = document.getElementById("fp-tbody");
        if (!tbody) return;
        tbody.innerHTML = fps.map(function(f) {
            return "<tr><td>" + esc(f.job_type) + "</td><td>" + f.total_24h + "</td><td>" + f.completed_24h + "</td><td>" + f.failed_24h + "</td><td>" + (f.success_rate_pct || 0) + "%</td><td>" + Number(f.p50_duration_s || 0).toFixed(1) + "s</td><td>" + Number(f.p95_duration_s || 0).toFixed(1) + "s</td><td>" + Number(f.p99_duration_s || 0).toFixed(1) + "s</td><td>" + Number(f.avg_attempts || 0).toFixed(1) + "</td><td class='small'>" + esc(f.most_common_error || "-") + "</td></tr>";
        }).join("") || "<tr><td colspan='10' class='muted'>No data</td></tr>";
    } catch (e) { toast(e.message, "error"); }
}

// ===== ANALYTICS =====
function renderAnalytics() {
    return '<div class="toolbar"><h2>Analytics</h2><button class="btn" onclick="loadAnalytics()">Refresh</button></div>' +
        '<div class="metrics-grid">' +
        '<div class="metric-card"><h3>Queue Depth</h3><div class="metric-value" id="an-depth">-</div></div>' +
        '<div class="metric-card"><h3>Submitted</h3><div class="metric-value" id="an-submitted">-</div></div>' +
        '<div class="metric-card"><h3>Completed</h3><div class="metric-value" id="an-completed">-</div></div>' +
        '<div class="metric-card"><h3>Failed</h3><div class="metric-value" id="an-failed">-</div></div>' +
        '<div class="metric-card"><h3>Retried</h3><div class="metric-value" id="an-retried">-</div></div>' +
        '<div class="metric-card"><h3>Cancelled</h3><div class="metric-value" id="an-cancelled">-</div></div>' +
        '<div class="metric-card"><h3>Trend</h3><div class="metric-value" id="an-trend">-</div></div>' +
        '<div class="metric-card"><h3>Predicted 5m</h3><div class="metric-value" id="an-predicted">-</div></div>' +
        '<div class="metric-card"><h3>Throttled</h3><div class="metric-value" id="an-throttled">-</div></div>' +
        '<div class="metric-card"><h3>Fenced Writes</h3><div class="metric-value" id="an-fenced">-</div></div>' +
        '</div>';
}
async function loadAnalytics() {
    try {
        var text = await fetch("/actuator/prometheus").then(function(r) { return r.text(); });
        function get(name) {
            var m = text.match(new RegExp("^" + name + "\\{?.*\\}?\\s+(\\S+)", "m"));
            return m ? m[1] : "-";
        }
        var map = { "an-depth": "schedula_queue_depth", "an-submitted": "schedula_job_submitted_total",
            "an-completed": "schedula_job_completed_total", "an-failed": "schedula_job_dead_total",
            "an-retried": "schedula_job_retried_total", "an-cancelled": "schedula_job_cancelled_total",
            "an-trend": "schedula_queue_depth_trend", "an-predicted": "schedula_queue_depth_predicted_5m",
            "an-throttled": "schedula_tenant_throttled_total", "an-fenced": "schedula_fenced_write_rejected_total" };
        Object.keys(map).forEach(function(id) {
            var el = document.getElementById(id);
            if (el) el.textContent = get(map[id]);
        });
    } catch (e) { toast(e.message, "error"); }
}

// ===== ADMIN =====
function renderAdmin() {
    return '<div class="toolbar"><h2>Admin</h2><span class="muted">Requires X-Admin-Key</span></div>' +
        '<div class="section-title">Tenants</div>' +
        '<button class="btn primary" onclick="createTenant()">+ Create Tenant</button>' +
        '<button class="btn" onclick="rotateKey()">Rotate Default Key</button>' +
        '<div class="section-title">Audit Trail</div>' +
        '<button class="btn" onclick="loadAdminAudits()">Refresh</button>' +
        '<table><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th></tr></thead><tbody id="admin-audits-tbody"></tbody></table>';
}
async function createTenant() {
    var name = prompt("Tenant name:"); if (!name) return;
    try {
        var r = await api("POST", "/admin/tenants", { name: name });
        toast("Tenant created. API Key: " + r.apiKey, "success");
    } catch (e) { toast(e.message, "error"); }
}
async function rotateKey() {
    if (!confirm("Rotate default tenant API key? Old key will stop working.")) return;
    try {
        var r = await api("POST", "/admin/tenants/00000000-0000-0000-0000-000000000001/rotate");
        toast("New key: " + r.apiKey, "success");
    } catch (e) { toast(e.message, "error"); }
}
async function loadAdminAudits() {
    try {
        var rows = await api("GET", "/admin/audits?limit=100");
        var tbody = document.getElementById("admin-audits-tbody");
        if (!tbody) return;
        tbody.innerHTML = rows.map(function(a) {
            return "<tr><td class='mono small'>" + fmtDate(a.occurred_at) + "</td><td>" + esc(a.actor) + "</td><td>" + esc(a.action) + "</td><td class='mono small'>" + esc(a.target_id || "") + "</td></tr>";
        }).join("");
    } catch (e) { toast(e.message, "error"); }
}
function loadAdmin() { loadAdminAudits(); }

// ===== INIT =====
document.addEventListener("DOMContentLoaded", function() {
    try {
        var savedKey = localStorage.getItem("apiKey");
        if (savedKey) { var k = document.getElementById("in-api-key"); if (k) k.value = savedKey; }
        var savedAdmin = localStorage.getItem("adminKey");
        if (savedAdmin) { var a = document.getElementById("in-admin-key"); if (a) a.value = savedAdmin; }
    } catch(e) {}
    showView("dashboard");
});

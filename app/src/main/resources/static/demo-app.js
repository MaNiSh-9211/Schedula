// Demo app JavaScript - interactive API tester
const API = "/v1";
let apiKey = "sk_00000000-0000-0000-0000-000000000001_devkey123";

async function call(method, path, body) {
    const opts = { method, headers: { "Content-Type": "application/json", "X-API-Key": apiKey } };
    if (body) opts.body = JSON.stringify(body);
    const res = await fetch(API + path, opts);
    if (!res.ok) throw new Error(res.status + " " + await res.text());
    return res.status === 204 ? null : res.json();
}

// Toast
function toast(msg, type) {
    const c = document.getElementById("toast-container") || createToastContainer();
    const t = document.createElement("div");
    t.className = "toast " + (type || "info");
    t.textContent = msg;
    c.appendChild(t);
    setTimeout(() => t.remove(), 3000);
}
function createToastContainer() {
    const c = document.createElement("div");
    c.id = "toast-container";
    c.style.cssText = "position:fixed;bottom:20px;right:20px;z-index:9999";
    document.body.appendChild(c);
    return c;
}

// Navigation
document.querySelectorAll("[data-view]").forEach(btn => {
    btn.addEventListener("click", () => {
        document.querySelectorAll("[data-view]").forEach(b => b.classList.remove("active"));
        document.querySelectorAll(".view").forEach(v => v.hidden = true);
        btn.classList.add("active");
        const view = document.getElementById("view-" + btn.dataset.view);
        if (view) view.hidden = false;
        if (btn.dataset.view === "jobs") loadJobs();
        if (btn.dataset.view === "workflows") loadWorkflows();
        if (btn.dataset.view === "schedules") loadSchedules();
        if (btn.dataset.view === "dlq") loadDlq();
        if (btn.dataset.view === "analytics") loadAnalytics();
    });
});

// Jobs
async function loadJobs() {
    try {
        const jobs = await call("GET", "/jobs?limit=50");
        const tbody = document.getElementById("jobs-tbody");
        tbody.innerHTML = jobs.map(j => `<tr>
            <td>${j.id.slice(0,8)}</td>
            <td>${j.jobType}</td>
            <td><span class="badge s-${j.status}">${j.status}</span></td>
            <td>${j.priority}</td>
            <td>${j.attemptsMade}/${j.maxAttempts}</td>
            <td>
                <button class="btn-sm" onclick="viewJobDetail('${j.id}')">View</button>
                ${["QUEUED","RUNNING","RETRY_WAIT"].includes(j.status) ? `<button class="btn-sm danger" onclick="cancelJob('${j.id}')">Cancel</button>` : ""}
                ${["DEAD","FAILED_TERMINAL"].includes(j.status) ? `<button class="btn-sm" onclick="retryJob('${j.id}')">Retry</button>` : ""}
            </td>
        </tr>`).join("");
    } catch (e) { toast("Load jobs failed: " + e.message, "error"); }
}

async function submitDemoJob() {
    document.getElementById("submit-modal").hidden = false;
}

async function doSubmitJob() {
    const type = document.getElementById("new-type").value;
    const payload = document.getElementById("new-payload").value;
    const queue = document.getElementById("new-queue").value;
    const priority = parseInt(document.getElementById("new-priority").value) || 0;
    try {
        const job = await call("POST", "/jobs", { jobType: type, payload: JSON.parse(payload), queueName: queue, priority });
        toast("Job submitted: " + job.id.slice(0,8));
        closeModal("submit-modal");
        loadJobs();
    } catch (e) { toast("Submit failed: " + e.message, "error"); }
}

async function cancelJob(id) {
    try { await call("POST", "/jobs/" + id + "/cancel"); toast("Cancelled"); loadJobs(); }
    catch (e) { toast("Cancel failed", "error"); }
}

async function retryJob(id) {
    try { await call("POST", "/jobs/" + id + "/retry"); toast("Retry queued"); loadJobs(); }
    catch (e) { toast("Retry failed", "error"); }
}

// Workflows
async function loadWorkflows() {
    try {
        const execs = await call("GET", "/workflows/executions?limit=25");
        const tbody = document.getElementById("workflows-tbody");
        tbody.innerHTML = execs.map(w => `<tr>
            <td>${w.id.slice(0,8)}</td>
            <td><span class="badge s-${w.status}">${w.status}</span></td>
            <td>${new Date(w.createdAt).toLocaleString()}</td>
            <td><button class="btn-sm" onclick="viewWorkflow('${w.id}')">Tasks</button></td>
        </tr>`).join("");
    } catch (e) { toast("Load workflows failed: " + e.message, "error"); }
}

async function startDemoWorkflow() {
    try {
        const res = await call("POST", "/workflows/demo-order-flow/executions", { input: {} });
        toast("Workflow started: " + res.workflowExecutionId.slice(0,8));
        loadWorkflows();
    } catch (e) { toast("Start failed: " + e.message, "error"); }
}

async function viewWorkflow(id) {
    try {
        const wf = await call("GET", "/workflows/executions/" + id);
        const tbody = document.getElementById("wf-tasks-tbody");
        tbody.innerHTML = (wf.tasks || []).map(t => `<tr>
            <td>${t.key}</td><td>${t.kind}</td>
            <td><span class="badge s-${t.status}">${t.status}</span></td>
            <td>${t.attemptNo}</td>
            <td>${t.jobId ? t.jobId.slice(0,8) : "-"}</td>
            <td class="small">${t.error || ""}</td>
        </tr>`).join("");
        document.getElementById("wf-detail-name").textContent = id.slice(0,8) + "...";
        document.getElementById("view-workflow-detail").hidden = false;
        document.getElementById("view-workflows").hidden = true;
    } catch (e) { toast(e.message, "error"); }
}

// Schedules
async function loadSchedules() {
    // Query schedules from DB via API
    try {
        const res = await call("GET", "/schedules");
        // render
    } catch (e) { console.error(e); }
}

// DLQ
async function loadDlq() {
    try {
        const letters = await call("GET", "/dlq?limit=50");
        const tbody = document.getElementById("dlq-tbody");
        tbody.innerHTML = letters.map(l => `<tr>
            <td>${l.messageId?.slice(0,8)}</td>
            <td>${l.jobId?.slice(0,8)}</td>
            <td>${l.jobType}</td>
            <td>${l.deliverCount}</td>
            <td class="small">${l.errorClass || ""} ${l.errorDetail || ""}</td>
            <td>${l.resolvedAt ? "yes" : "no"}</td>
            <td>
                ${!l.resolvedAt ? `<button class="btn-sm" onclick="retryDlq('${l.messageId}')">Retry</button>` : ""}
            </td>
        </tr>`).join("");
    } catch (e) { toast(e.message, "error"); }
}

async function retryDlq(id) {
    try { await call("POST", "/dlq/" + id + "/retry"); toast("Replayed"); loadDlq(); }
    catch (e) { toast(e.message, "error"); }
}

// Analytics
async function loadAnalytics() {
    try {
        const metrics = await fetch("/actuator/prometheus").then(r => r.text());
        const getMetric = (name) => {
            const m = metrics.match(new RegExp("^" + name + "\\{.*\\}\\s+(\\S+)", "m"));
            return m ? parseFloat(m[1]).toFixed(0) : "-";
        };
        document.getElementById("m-depth").textContent = getMetric("schedula_queue_depth");
        document.getElementById("m-completed").textContent = getMetric("schedula_job_completed_total");
        document.getElementById("m-failed").textContent = getMetric("schedula_job_dead_total");
        document.getElementById("m-running").textContent = getMetric("schedula_job_started_total");
    } catch (e) { console.error(e); }
}

// Modal
function closeModal(id) { document.getElementById(id).hidden = true; }
function hideWorkflowDetail() {
    document.getElementById("view-workflow-detail").hidden = true;
    document.getElementById("view-workflows").hidden = false;
}

// Init
document.addEventListener("DOMContentLoaded", () => {
    // Restore API key
    const saved = localStorage.getItem("apiKey");
    if (saved) { apiKey = saved; document.getElementById("apiKey").value = saved; }
    document.getElementById("apiKey").addEventListener("change", e => {
        apiKey = e.target.value;
        localStorage.setItem("apiKey", apiKey);
    });
    document.getElementById("saveKeys").addEventListener("click", () => {
        localStorage.setItem("apiKey", document.getElementById("apiKey").value);
        toast("API key saved");
    });

    // Load initial view
    loadJobs();

    // Poll for updates every 5s
    setInterval(() => {
        const active = document.querySelector(".tab.active");
        if (active && active.dataset.view === "jobs") loadJobs();
    }, 5000);
});

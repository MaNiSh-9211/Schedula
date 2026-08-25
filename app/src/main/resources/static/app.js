<!-- demo/index.html - Interactive Demo Dashboard -->
<!DOCTYPE html>
<html lang="en" xmlns:th="https://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Schedula Demo</title>
    <link rel="stylesheet" href="/style.css">
    <link rel="icon" type="image/svg+xml" href="/favicon.svg">
</head>
<body>
<div class="app-shell">
    <header class="demo-header">
        <div class="header-content">
            <h1><img src="/logo.svg" width="32" height="32" alt="S"> Schedula Demo</h1>
            <nav class="demo-nav">
                <a href="#jobs" class="tab active" data-view="jobs">Jobs</a>
                <a href="#workflows" data-view="workflows">Workflows</a>
                <a href="#schedules" data-view="schedules">Schedules</a>
                <a href="/demo/dlq" class="">DLQ</a>
                <a href="#" data-view="analytics" class="">Analytics</a>
            </nav>
            <div class="header-actions">
                <input type="text" id="apiKey" placeholder="X-API-Key" value="sk_00000000-0000-0000-0000-000000000001_devkey123" />
                <button id="saveKeyBtn" class="btn primary">Save Key</button>
                <span id="connStatus" class="status disconnected">Disconnected</div>
        </div>
    </header>

    <main class="main-content">
        <!-- Jobs View -->
        <section id="view-jobs" class="view active">
            <div class="toolbar">
                <h2>Jobs</div>
                <div class="toolbar">
                    <select id="jobs-status-filter">
                        <option value="">All Statuses</option>
                        <option value="SCHEDULED">Scheduled</option>
                        <option value="QUEUED">QUEUED</option>
                        <option value="RUNNING">Running</option>
                        <option value="COMPLETED">Completed</option>
                        <option value="FAILED">Failed</option>
                        <option value="DEAD">Dead</option>
                        <option value="CANCELLED">Cancelled</option>
                    </select>
                    <button class="btn primary" onclick="submitJob()">+ Submit Job</button>
                </div>
            </div>
            <table id="jobs-table">
                <thead>
                    <tr>
                        <th>ID</th><th>Type</th><th>Status</th><th>Priority</th>
                        <th>Attempts</th><th>Scheduled</th><th>Actions</th>
                    </tr>
                </thead>
                <tbody id="jobs-tbody"></tbody>
            </table>
        </section>

        <section id="view-workflows" class="view" hidden>
            <h2>Workflows</div>
            <button onclick="loadWorkflows()">Refresh</button>
            <button onclick="showCreateWorkflow()">+ New Workflow</button>
            <table id="workflows-table">
                <thead><tr><th>Name</th><th>Version</th><th>Status</th><th>Created</th><th>Actions</th></tr></thead>
                <tbody id="workflows-tbody"></tbody>
            </table>
        </section>

        <section id="view-schedules" class="view" hidden>
            <h2>Schedules</div>
            <button onclick="showCreateSchedule()">+ New Schedule</button>
            <table id="schedules-table"><thead><tr><th>Name</th><th>Type</th><th>Expression</th><th>Next Fire</th><th>Status</th><th></th></tr></tbody></table>
        </section>

        <section id="view-dlq" class="view" hidden>
            <h2>Dead Letter Queue</div>
            <table id="dlq-table"><thead><tr><th>Job</th><th>Type</th><th>Attempts</th><th>Error</th><th>Actions</th></tr></tbody></table>
        </section>

        <section id="view-analytics" class="view" hidden>
            <h2>Analytics</div>
            <div class="metrics-grid">
                <div class="metric-card"><h3>Jobs/Min</h3><div class="metric-value" id="metric-jobs-min">0</div></div>
                <div class="metric-card"><h3>Success Rate</h3><div class="metric-value" id="success-rate">0%</div></div>
                <div class="metric-card"><h3>Avg Latency</h3><div class="metric-value" id="avg-latency">0ms</div></div>
                <div class="metric-card"><h3>Queue Depth</h3><div class="metric-value" id="queue-depth">0</div></div>
            </div>
        </section>
    </main>

    <script>
    // Global state
    const API_BASE = '/v1';
    let apiKey = localStorage.getItem('apiKey') || 'sk_00000000-0000-0000-0000-000000000001_devkey123';
    let eventSource = null;

    // API helper
    async function api(path, options = {}) {
        const headers = { 'Content-Type': 'application/json', 'X-API-Key': localStorage.getItem('apiKey') || '' };
        const res = await fetch('/v1' + path, { ...options, headers: { 'Content-Type': 'application/json', 'X-API-Key': localStorage.getItem('apiKey') || '' } });
        if (!res.ok) throw new Error(await res.text());
        return res.json();
    }

    // UI State
    let currentView = 'jobs';
    let eventSource = null;
    let jobPollInterval = null;

    // DOM Elements
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    // Toast notifications
    function toast(msg, type = 'info') {
        const container = document.getElementById('toast-container') || createToastContainer();
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = msg;
        document.getElementById('toast-container').appendChild(toast);
        setTimeout(() => toast.remove(), 3000);
    }

    function createToastContainer() {
        const c = document.createElement('div');
        c.id = 'toast-container';
        c.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:8px';
        document.body.appendChild(c);
        return c;
    }

    function showView(view) {
        document.querySelectorAll('.view').forEach(v => v.hidden = true);
        document.getElementById('view-' + view).hidden = false;
        document.querySelectorAll('.demo-nav a').forEach(b => b.classList.toggle('active', b.dataset.view === view));
        currentView = view;
        if (view === 'jobs') loadJobs();
        else if (view === 'workflows') loadWorkflows();
        else if (view === 'schedules') loadSchedules();
    }

    // Navigation
    document.querySelectorAll('[data-view]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.preventDefault();
            showView(btn.dataset.view);
        });
    });

    // API Helpers
    const API = '/v1';
    function api(path, options = {}) {
        return fetch('/v1' + path, {
            headers: { 'Content-Type': 'application/json', 'X-API-Key': localStorage.getItem('apiKey') || '' },
            ...options
        }).then(r => { if (!res.ok) throw new Error(res.status); return res.json(); });
    }

    // Key management
    function getApiKey() { return localStorage.getItem('apiKey') || 'sk_00000000-0000-0000-0000-000000000001_devkey123'; }
    function setApiKey(key) { localStorage.setItem('apiKey', key); document.getElementById('apiKeyInput').value = key; }

    // Toast notifications
    function toast(msg, type = 'info') {
        const container = document.getElementById('toast-container') || createToastContainer();
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = msg;
        document.getElementById('toast-container').appendChild(toast);
        setTimeout(() => toast.remove(), 3000);
    }

    function createToastContainer() {
        const c = document.createElement('div');
        c.id = 'toast-container';
        c.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:8px';
        document.body.appendChild(c);
        return c;
    }

    // Job submission
    async function submitJob() {
        const form = document.getElementById('submit-form');
        const data = {
            jobType: form.jobType.value,
            payload: JSON.parse(form.payload.value || '{}'),
            priority: parseInt(form.priority.value) || 0,
            scheduledFor: form.scheduledFor.value || null,
            maxAttempts: parseInt(form.maxAttempts.value) || 3,
            timeoutMs: parseInt(form.timeoutMs.value) || 60000,
            idempotencyKey: form.idemKey.value || undefined
        };
        try {
            const res = await fetch('/v1/jobs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-API-Key': getApiKey() },
                body: JSON.stringify(body)
            });
            const job = await res.json();
            toast('Job submitted: ' + job.id);
            loadJobs();
        } catch (e) {
            alert('Submit failed: ' + e.message);
        }
    }

    async function loadJobs() {
        try {
            const jobs = await api.get('/jobs?limit=50');
            renderJobTable(jobs);
        } catch (e) { console.error(e); }
    }

    function renderJobTable(jobs) {
        const tbody = document.getElementById('jobs-tbody');
        tbody.innerHTML = jobs.map(job => `
            <tr data-id="${job.id}">
                <td><code>${job.id.slice(0,8)}...</code></td>
                <td>${job.jobType}</td>
                <td><span class="badge ${job.status.toLowerCase()}">${job.status}</span></td>
                <td>${job.priority}</td>
                <td>${job.attemptsMade}/${job.maxAttempts}</td>
                <td>${job.scheduledFor ? new Date(job.scheduledFor).toLocaleString() : 'now'}</td>
                <td>
                    <button class="btn-sm" onclick="viewJob('${job.id}')">View</button>
                    ${job.status === 'QUEUED' || job.status === 'RUNNING' ? `<button class="btn danger" onclick="cancelJob('${job.id}')">Cancel</button>` : ''}
                    ${['FAILED','DEAD'].includes(job.status) ? '<button class="btn secondary" onclick="retryJob(\'' + job.id + '\')">Retry</button>' : ''}
                </td>
            </tr>`).join('');
    }

    async function cancelJob(id) {
        if (!confirm('Cancel this job?')) return;
        await fetch('/v1/jobs/' + id + '/cancel', { method: 'POST', headers: authHeaders() });
        toast('Job cancelled');
        loadJobs();
    }

    async function retryJob(id) {
        await fetch('/v1/jobs/' + id + '/retry', { method: 'POST', headers: authHeaders() });
        toast('Retry queued');
        loadJobs();
    }

    // WebSocket / SSE for live updates
    let eventSource = null;
    function connectSSE() {
        if (eventSource) eventSource.close();
        const es = new EventSource('/v1/events/stream');
        eventSource.onmessage = e => {
            const data = JSON.parse(e.data);
            handleLiveEvent(data);
        };
        eventSource.onerror = () => setTimeout(connectSSE, 5000);
    }

    function handleLiveEvent(data) {
        // Update job row
        const row = document.querySelector(`[data-job-id="${data.jobId}"]`);
        if (row) {
            row.querySelector('.status').textContent = data.status;
            row.querySelector('.status').className = 'badge ' + data.status.toLowerCase();
        }
        // Add to live feed
        addLiveLog(event);
    }

    function addLiveLog(event) {
        const feed = document.getElementById('live-feed');
        const div = document.createElement('div');
        div.className = 'log-entry ' + event.type;
        div.innerHTML = `<span class="time">${new Date().toLocaleTimeString()}</span> <span class="event-type ${event.type}">${event.type}</span> <span class="job-id">${event.jobId?.slice(0,8)}</span> ${JSON.stringify(event.payload || {})}`;
        document.getElementById('live-feed').prepend(div);
        if (feed.children.length > 50) feed.lastChild.remove();
    }

    // Auth key management
    function saveKey() {
        const key = document.getElementById('apiKeyInput').value.trim();
        if (key) { localStorage.setItem('apiKey', key); toast('API Key saved'); }
    }

    // Initialize
    document.addEventListener('DOMContentLoaded', () => {
        // Restore API key
        const savedKey = localStorage.getItem('apiKey');
        if (savedKey) document.getElementById('apiKeyInput').value = savedKey;

        document.getElementById('saveKeyBtn')?.addEventListener('click', () => {
            localStorage.setItem('apiKey', document.getElementById('apiKeyInput').value);
            toast('API Key saved');
        });

        // Tab switching
        document.querySelectorAll('[data-view]').forEach(btn => {
            btn.addEventListener('click', () => showView(btn.dataset.view));
        });

        // Form handlers
        document.getElementById('submit-form')?.addEventListener('submit', submitJob);
        document.getElementById('wf-start-form')?.addEventListener('submit', startWorkflow);
        document.getElementById('wf-register-form')?.addEventListener('submit', registerWorkflow);
        document.getElementById('sched-form')?.addEventListener('submit', createSchedule);

        // Live updates
        // connectSSE();

        // Initial loads
        loadJobs();
        loadWorkflows();
        loadSchedules();
    });

    // ... rest of the JS implementation continues
    // (The full app.js would be ~500 lines with all handlers)

    // Toast notifications
    function toast(msg, type = 'info') {
        const container = document.getElementById('toast-container') || createToastContainer();
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = msg;
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 3000);
    }

    function createToastContainer() {
        const c = document.createElement('div');
        c.id = 'toast-container';
        c.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:8px';
        document.body.appendChild(c);
        return c;
    }

    function formatDate(iso) {
        return new Date(iso).toLocaleString();
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Initialize on load
    document.addEventListener('DOMContentLoaded', () => {
        // Restore API key
        const savedKey = localStorage.getItem('apiKey');
        if (savedKey) document.getElementById('apiKeyInput').value = savedKey;

        // Tab switching
        document.querySelectorAll('[data-view]').forEach(btn => {
            btn.addEventListener('click', () => showView(btn.dataset.view));
        });

        // Form handlers
        document.getElementById('submit-form')?.addEventListener('submit', submitJob);
        document.getElementById('wf-start-form')?.addEventListener('submit', startWorkflow);
        document.getElementById('wf-register-form')?.addEventListener('submit', registerWorkflow);
        document.getElementById('sched-form')?.addEventListener('submit', createSchedule);

        // Load initial data
        loadJobs();
        loadWorkflows();
        loadSchedules();
    });
</script>
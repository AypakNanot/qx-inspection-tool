/**
 * 任务进度模块
 * 展示巡检任务实时进度、失败设备、本轮摘要、历史轮次
 */

import { get } from './api.js';

/** 轮询定时器 */
let progressPollTimer = null;

/** 创建文本单元格 */
function createTextCell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
}

/** 加载任务进度 */
export async function loadProgress() {
    try {
        const d = await get('/inspection/progress');
        renderProgress(d);
    } catch (e) { console.error('loadProgress', e); }
}

/** 渲染进度状态 */
function renderProgress(d) {
    const statusEl = document.getElementById('progStatus');
    const panel = document.getElementById('progProgressPanel');
    if (!d.running) {
        statusEl.textContent = '空闲';
        statusEl.style.color = '#6b7280';
        panel.style.display = 'none';
        stopProgressPoll();
        loadSummary();
        loadRounds();
        return;
    }
    statusEl.textContent = '进行中';
    statusEl.style.color = '#1a73e8';
    panel.style.display = '';
    startProgressPoll();
    const pct = d.total > 0 ? Math.round((d.done / d.total) * 100) : 0;
    document.getElementById('progFill').style.width = pct + '%';
    document.getElementById('progNum').textContent = d.done + ' / ' + d.total;
    document.getElementById('progDone').textContent = d.done;
    document.getElementById('progFail').textContent = d.failures;
    document.getElementById('progCurrentNe').textContent = d.currentNe ? '当前: ' + d.currentNe : '';
    renderFailList(d.failures_list || []);
}

/** 渲染失败设备列表 */
function renderFailList(failures) {
    const panel = document.getElementById('progFailPanel');
    const list = document.getElementById('progFailList');
    if (!failures || failures.length === 0) {
        panel.style.display = 'none';
        return;
    }
    panel.style.display = '';
    list.textContent = '';
    failures.forEach(f => {
        const div = document.createElement('div');
        div.style.cssText = 'padding:6px 0;border-bottom:1px solid #f1f5f9;font-size:13px;';
        const dot = document.createElement('span');
        dot.className = 'status-dot failed';
        div.appendChild(dot);
        // 兼容新格式（对象）和旧格式（字符串）
        if (typeof f === 'object' && f !== null) {
            div.appendChild(document.createElement('strong')).textContent = f.device || '';
            if (f.reason) {
                const reasonSpan = document.createElement('span');
                reasonSpan.style.cssText = 'color:#dc2626;margin-left:8px;font-size:12px;';
                reasonSpan.textContent = f.reason;
                div.appendChild(reasonSpan);
            }
            if (f.time) {
                const timeSpan = document.createElement('span');
                timeSpan.style.cssText = 'color:#9ca3af;margin-left:8px;font-size:11px;';
                timeSpan.textContent = f.time;
                div.appendChild(timeSpan);
            }
        } else {
            div.appendChild(document.createTextNode(f));
        }
        list.appendChild(div);
    });
}

/** 启动进度轮询（每2秒） */
function startProgressPoll() {
    if (progressPollTimer) return;
    progressPollTimer = setInterval(loadProgress, 2000);
}

/** 停止进度轮询 */
export function stopProgressPoll() {
    if (progressPollTimer) { clearInterval(progressPollTimer); progressPollTimer = null; }
}

/** 加载本轮摘要 */
async function loadSummary() {
    try {
        const d = await get('/inspection/summary');
        if (!d.hasData) {
            document.getElementById('progSummaryPanel').style.display = 'none';
            return;
        }
        document.getElementById('progSummaryPanel').style.display = '';
        document.getElementById('sumPorts').textContent = d.totalPorts || 0;
        document.getElementById('sumSupported').textContent = d.supportedPorts || 0;
        document.getElementById('sumOver').textContent = d.overThresholdPorts || 0;
        renderModuleSummary(d.byModuleType || {});
    } catch (e) { console.error('loadSummary', e); }
}

/** 渲染按模块类型统计 */
function renderModuleSummary(byModuleType) {
    const tbody = document.getElementById('sumModuleTable');
    tbody.textContent = '';
    const entries = Object.entries(byModuleType);
    if (entries.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 3; td.className = 'empty'; td.textContent = '暂无数据';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }
    entries.forEach(e => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(e[0]));
        tr.appendChild(createTextCell(String(e[1].count || 0)));
        const overTd = createTextCell(String(e[1].overThreshold || 0));
        if (e[1].overThreshold > 0) overTd.style.color = '#dc2626';
        tr.appendChild(overTd);
        tbody.appendChild(tr);
    });
}

/** 加载历史轮次列表 */
async function loadRounds() {
    try {
        const data = await get('/inspection/rounds');
        renderRoundsTable(data);
    } catch (e) { console.error('loadRounds', e); }
}

/** 格式化耗时 */
function formatDuration(startTime, endTime) {
    if (!startTime || !endTime) return '-';
    const start = new Date(startTime);
    const end = new Date(endTime);
    const diffMs = end - start;
    if (diffMs < 0) return '-';
    const diffSec = Math.floor(diffMs / 1000);
    const h = Math.floor(diffSec / 3600);
    const m = Math.floor((diffSec % 3600) / 60);
    const s = diffSec % 60;
    if (h > 0) return h + '时' + m + '分' + s + '秒';
    if (m > 0) return m + '分' + s + '秒';
    return s + '秒';
}

/** 渲染历史轮次表格 */
function renderRoundsTable(rounds) {
    const tbody = document.getElementById('roundsTable');
    tbody.textContent = '';
    if (!rounds || rounds.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 9; td.className = 'empty'; td.textContent = '暂无巡检记录';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }
    rounds.slice(0, 20).forEach(r => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell('#' + r.id));
        tr.appendChild(createTextCell(r.triggerType));
        tr.appendChild(createTextCell(r.scopeType + (r.scopeParam ? '(' + r.scopeParam + ')' : '')));
        const statusTd = document.createElement('td');
        const badgeCls = r.status === 'COMPLETED' ? 'badge-online' : r.status === 'RUNNING' ? '' : 'badge-failed';
        const badgeText = r.status === 'COMPLETED' ? '已完成' : r.status === 'RUNNING' ? '进行中' : '失败';
        const badge = document.createElement('span');
        badge.className = 'badge ' + badgeCls;
        badge.textContent = badgeText;
        statusTd.appendChild(badge);
        tr.appendChild(statusTd);
        tr.appendChild(createTextCell(String(r.totalCount || 0)));
        tr.appendChild(createTextCell(String(r.doneCount || 0)));
        tr.appendChild(createTextCell(String(r.failCount || 0)));
        tr.appendChild(createTextCell(r.startTime || '-'));
        tr.appendChild(createTextCell(formatDuration(r.startTime, r.endTime)));
        tbody.appendChild(tr);
    });
}

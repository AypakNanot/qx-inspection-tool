/**
 * 数据查询模块
 * 查询巡检结果，支持按轮次/网络/网元/状态筛选，按网元分组折叠，分页浏览，列排序，文本搜索，导出Excel
 */

import { get, post, API } from './api.js';

/** 全量查询结果 */
let allResults = [];
/** 筛选后结果 */
let filteredResults = [];
/** 分页状态 */
let currentPage = 1;
let pageSize = 20;
let sortField = '';
let sortOrder = 'asc';
let searchText = '';
/** 展开的网元组（neId Set） */
let expandedGroups = new Set();
/** 关注端口集合 "neId:slotNo:portNo" */
let watchedKeys = new Set();

/** 格式化时间：去掉T和毫秒 */
function formatTime(t) {
    if (!t) return '-';
    return t.replace('T', ' ').replace(/\.\d+$/, '');
}

/** 加载关注端口列表 */
async function loadWatchedPorts() {
    try {
        const list = await get('/inspection/port/watched');
        watchedKeys = new Set(list.map(p => p.neId + ':' + p.slotNo + ':' + p.portNo));
    } catch (e) { console.error('loadWatchedPorts', e); }
}

/** 生成端口关注键 */
function portKey(r) {
    return r.neId + ':' + r.slotNo + ':' + r.portNo;
}

/** 切换端口关注状态 */
export async function togglePortWatched(r) {
    try {
        const res = await post('/inspection/port/watched/toggle', {
            neId: r.neId,
            slotNo: r.slotNo,
            portNo: r.portNo,
            portName: r.portName,
            neName: r.neName
        });
        if (res.watched) {
            watchedKeys.add(portKey(r));
        } else {
            watchedKeys.delete(portKey(r));
        }
        renderQueryTable();
    } catch (e) { console.error('togglePortWatched', e); }
}

/** 检查端口是否被关注 */
function isWatched(r) {
    return watchedKeys.has(portKey(r));
}

/** 创建文本单元格 */
function createTextCell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
}

/** 加载轮次列表 */
export async function loadQueryRounds() {
    try {
        const rounds = await get('/inspection/rounds');
        const sel = document.getElementById('queryRound');
        const current = sel.value;
        sel.textContent = '';
        const opt0 = document.createElement('option');
        opt0.value = ''; opt0.textContent = '最新轮次';
        sel.appendChild(opt0);
        rounds.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r.id;
            opt.textContent = '#' + r.id + ' ' + formatTime(r.startTime) + ' (' + r.status + ')';
            sel.appendChild(opt);
        });
        sel.value = current;
    } catch (e) { console.error('loadQueryRounds', e); }
}

/** 加载筛选条件（网络+网元列表） */
export async function loadQueryFilters() {
    try {
        const data = await get('/inventory/networks');
        const dl = document.getElementById('queryNetworkList');
        dl.textContent = '';
        data.forEach(n => {
            const opt = document.createElement('option');
            opt.value = n; dl.appendChild(opt);
        });
    } catch (e) { console.error('loadQueryFilters', e); }
    try {
        const devices = await get('/connection/status');
        const dl = document.getElementById('queryNeList');
        dl.textContent = '';
        devices.forEach(d => {
            const opt = document.createElement('option');
            opt.value = d.neId; opt.textContent = d.neName;
            dl.appendChild(opt);
        });
    } catch (e) { console.error('loadQueryDevices', e); }
}

/** 加载查询结果 */
export async function loadQueryResults() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const neId = document.getElementById('queryNe').value.trim();
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);
    if (neId) params.set('neId', neId);
    try {
        allResults = await get('/inspection/results' + (params.toString() ? '?' + params : ''));
        currentPage = 1;
        expandedGroups = new Set();
        await loadWatchedPorts();
        applyFilterAndSort();
    } catch (e) { console.error('loadQueryResults', e); }
}

/** 应用筛选和排序 */
function applyFilterAndSort() {
    const statusFilter = document.getElementById('queryStatus').value;
    const showInvalid = document.getElementById('queryShowInvalid').checked;
    const watchedOnly = document.getElementById('queryWatchedOnly').checked;

    filteredResults = allResults.filter(r => {
        // 显示无效记录开关
        if (!showInvalid && !r.supported) return false;

        // 仅关注
        if (watchedOnly && !isWatched(r)) return false;

        if (statusFilter !== '') {
            if (statusFilter === '-1') {
                if (r.supported) return false;
            } else {
                if (!r.supported) return false;
                const st = parseInt(statusFilter);
                if (r.txPowerStatus !== st && r.rxPowerStatus !== st) return false;
            }
        }
        if (searchText) {
            const text = searchText.toLowerCase();
            const match = (r.neName || '').toLowerCase().includes(text) ||
                         (r.neId || '').toLowerCase().includes(text) ||
                         (r.portName || '').toLowerCase().includes(text) ||
                         (r.laserWave || '').toLowerCase().includes(text) ||
                         (r.moduleTypeKey || '').toLowerCase().includes(text) ||
                         (r.slotNo != null && String(r.slotNo).includes(text)) ||
                         (r.portNo != null && String(r.portNo).includes(text));
            if (!match) return false;
        }
        return true;
    });

    // 排序：先按网元名分组排序，组内按槽位+端口号排序
    if (sortField) {
        if (sortField === 'neName') {
            filteredResults.sort((a, b) => {
                let va = a.neName || ''; let vb = b.neName || '';
                va = va.toLowerCase(); vb = vb.toLowerCase();
                if (va < vb) return sortOrder === 'asc' ? -1 : 1;
                if (va > vb) return sortOrder === 'asc' ? 1 : -1;
                return 0;
            });
        } else {
            const grouped = groupByNe(filteredResults);
            filteredResults = [];
            const groups = [...grouped.values()].sort((a, b) => {
                let va = a[0].neName || ''; let vb = b[0].neName || '';
                va = va.toLowerCase(); vb = vb.toLowerCase();
                if (va < vb) return -1; if (va > vb) return 1; return 0;
            });
            for (const group of groups) {
                group.sort((a, b) => {
                    let va = a[sortField]; let vb = b[sortField];
                    if (va == null) va = ''; if (vb == null) vb = '';
                    if (typeof va === 'number' && typeof vb === 'number') {
                        return sortOrder === 'asc' ? va - vb : vb - va;
                    }
                    if (typeof va === 'string') va = va.toLowerCase();
                    if (typeof vb === 'string') vb = vb.toLowerCase();
                    if (va < vb) return sortOrder === 'asc' ? -1 : 1;
                    if (va > vb) return sortOrder === 'asc' ? 1 : -1;
                    return 0;
                });
                filteredResults.push(...group);
            }
        }
    }

    // 默认按网元名排序（无排序字段时）
    if (!sortField) {
        filteredResults.sort((a, b) => {
            let va = a.neName || ''; let vb = b.neName || '';
            va = va.toLowerCase(); vb = vb.toLowerCase();
            if (va < vb) return -1; if (va > vb) return 1;
            return (a.slotNo || 0) - (b.slotNo || 0) || (a.portNo || 0) - (b.portNo || 0);
        });
    }

    renderQueryTable();
}

/** 按网元分组 */
function groupByNe(results) {
    const map = new Map();
    for (const r of results) {
        const key = r.neId || 'unknown';
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(r);
    }
    return map;
}

/** 搜索文本 */
export function searchQuery(text) {
    searchText = text.trim();
    currentPage = 1;
    applyFilterAndSort();
}

/** 切换排序字段 */
export function sortQueryBy(field) {
    if (sortField === field) {
        sortOrder = sortOrder === 'asc' ? 'desc' : 'asc';
    } else {
        sortField = field;
        sortOrder = 'asc';
    }
    applyFilterAndSort();
}

/** 获取端口状态（正常/劣化/过载/无效） */
function getPortStatus(r) {
    if (!r.supported) return { text: '无效', cls: 'badge-offline' };
    if (r.txPowerStatus > 0 || r.rxPowerStatus > 0) {
        const isOver = (r.txPowerStatus === 2 || r.rxPowerStatus === 2);
        return { text: isOver ? '过载' : '劣化', cls: 'badge-failed' };
    }
    return { text: '正常', cls: 'badge-online' };
}

/** 渲染查询结果表格（按网元分组，可展开收缩） */
function renderQueryTable() {
    const tbody = document.getElementById('queryTable');
    tbody.textContent = '';
    document.getElementById('queryCount').textContent =
        '筛选结果：' + filteredResults.length + ' / ' + allResults.length + ' 条';

    if (!filteredResults || filteredResults.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 15; td.className = 'empty'; td.textContent = '暂无巡检数据，请先执行一次巡检，完成后在此查看结果';
        tr.appendChild(td); tbody.appendChild(tr);
        renderQueryPagination(0);
        return;
    }

    // 按网元分组
    const groups = groupByNe(filteredResults);
    const groupEntries = [...groups.entries()];

    // 分页：按网元组分页
    const totalGroups = groupEntries.length;
    const totalGroupPages = Math.ceil(totalGroups / pageSize);
    if (currentPage > totalGroupPages) currentPage = totalGroupPages;
    const start = (currentPage - 1) * pageSize;
    const end = Math.min(start + pageSize, totalGroups);
    const pageGroups = groupEntries.slice(start, end);

    pageGroups.forEach(([neId, ports]) => {
        const first = ports[0];
        const expanded = expandedGroups.has(neId);

        // 统计该网元下的端口状态
        let normal = 0, abnormal = 0, invalid = 0;
        for (const p of ports) {
            if (!p.supported) { invalid++; continue; }
            if (p.txPowerStatus > 0 || p.rxPowerStatus > 0) { abnormal++; continue; }
            normal++;
        }

        // === 网元汇总行 ===
        const groupTr = document.createElement('tr');
        groupTr.style.cssText = 'background:#f8fafc;cursor:pointer;font-weight:600;';

        // 展开图标
        const arrowTd = document.createElement('td');
        arrowTd.style.cssText = 'width:30px;text-align:center;color:#6b7280;';
        arrowTd.textContent = expanded ? '▼' : '▶';
        groupTr.appendChild(arrowTd);

        // 关注列占位
        groupTr.appendChild(createTextCell(''));

        // 网元名 + 端口数
        const nameTd = document.createElement('td');
        nameTd.colSpan = 3;
        nameTd.textContent = (first.neName || '-') + '（' + ports.length + ' 个端口）';
        groupTr.appendChild(nameTd);

        // 状态摘要（安全拼接，不用 innerHTML）
        const statusTd = document.createElement('td');
        statusTd.colSpan = 2;
        statusTd.style.cssText = 'font-size:12px;font-weight:400;color:#6b7280;';
        if (normal > 0) {
            const s = document.createElement('span');
            s.textContent = '正常 ' + normal;
            statusTd.appendChild(s);
        }
        if (abnormal > 0) {
            if (statusTd.childNodes.length > 0) statusTd.appendChild(document.createTextNode(' / '));
            const s = document.createElement('span');
            s.style.color = '#dc2626';
            s.textContent = '异常 ' + abnormal;
            statusTd.appendChild(s);
        }
        if (invalid > 0) {
            if (statusTd.childNodes.length > 0) statusTd.appendChild(document.createTextNode(' / '));
            const s = document.createElement('span');
            s.textContent = '无效 ' + invalid;
            statusTd.appendChild(s);
        }
        if (statusTd.childNodes.length === 0) statusTd.textContent = '-';
        groupTr.appendChild(statusTd);

        // 空列占位（moduleTypeKey, laserState, vendorName, txPower, rxPower, txThreshold, rxThreshold）
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));
        groupTr.appendChild(createTextCell(''));

        // 巡检时间
        groupTr.appendChild(createTextCell(formatTime(first.inspectionTime)));

        groupTr.onclick = () => toggleGroup(neId);
        tbody.appendChild(groupTr);

        // === 端口子行 ===
        if (expanded) {
            ports.forEach(r => {
                const tr = document.createElement('tr');
                tr.style.cssText = 'background:#ffffff;';

                // 缩进占位
                const indentTd = document.createElement('td');
                indentTd.style.cssText = 'width:30px;';
                tr.appendChild(indentTd);

                // 关注星标
                const starTd = document.createElement('td');
                starTd.style.cssText = 'width:24px;text-align:center;cursor:pointer;font-size:14px;';
                starTd.textContent = isWatched(r) ? '★' : '☆';
                starTd.style.color = isWatched(r) ? '#f59e0b' : '#d1d5db';
                starTd.onclick = (e) => { e.stopPropagation(); togglePortWatched(r); };
                starTd.title = isWatched(r) ? '取消关注' : '关注此端口';
                tr.appendChild(starTd);

                tr.appendChild(createTextCell(r.slotNo != null ? String(r.slotNo) : '-'));
                tr.appendChild(createTextCell(r.portNo != null ? String(r.portNo) : '-'));
                tr.appendChild(createTextCell(r.portName || '-'));
                tr.appendChild(createTextCell(r.laserWave || '-'));
                tr.appendChild(createTextCell(r.moduleTypeKey || '-'));

                // 激光器状态
                const lsTd = document.createElement('td');
                const lsText = r.laserState === 1 ? '开' : r.laserState === 2 ? '关' : '--';
                lsTd.textContent = lsText;
                if (r.laserState === 2) lsTd.style.color = '#dc2626';
                tr.appendChild(lsTd);

                tr.appendChild(createTextCell(r.vendorName || '--'));

                // 发送功率
                const txTd = document.createElement('td');
                if (r.supported && r.txPower != null) {
                    txTd.textContent = r.txPower.toFixed(1);
                    if (r.txPowerStatus > 0) txTd.style.color = '#dc2626';
                } else {
                    txTd.textContent = '--';
                    txTd.style.color = '#9ca3af';
                }
                tr.appendChild(txTd);

                // 接收功率
                const rxTd = document.createElement('td');
                if (r.supported && r.rxPower != null) {
                    rxTd.textContent = r.rxPower.toFixed(1);
                    if (r.rxPowerStatus > 0) rxTd.style.color = '#dc2626';
                } else {
                    rxTd.textContent = '--';
                    rxTd.style.color = '#9ca3af';
                }
                tr.appendChild(rxTd);

                // 状态
                const stTd = document.createElement('td');
                const status = getPortStatus(r);
                const badge = document.createElement('span');
                badge.className = 'badge ' + status.cls;
                badge.textContent = status.text;
                stTd.appendChild(badge);
                tr.appendChild(stTd);

                tr.appendChild(createTextCell(r.txLowThreshold != null ? r.txLowThreshold + '~' + r.txHighThreshold : '-'));
                tr.appendChild(createTextCell(r.lowThreshold != null ? r.lowThreshold + '~' + r.highThreshold : '-'));
                tr.appendChild(createTextCell(formatTime(r.inspectionTime)));
                tbody.appendChild(tr);
            });
        }
    });

    renderQueryPagination(totalGroups);
}

/** 切换网元组展开/收缩 */
function toggleGroup(neId) {
    if (expandedGroups.has(neId)) {
        expandedGroups.delete(neId);
    } else {
        expandedGroups.add(neId);
    }
    renderQueryTable();
}

/** 全部展开 */
export function expandAll() {
    const groups = groupByNe(filteredResults);
    for (const neId of groups.keys()) expandedGroups.add(neId);
    renderQueryTable();
}

/** 全部收缩 */
export function collapseAll() {
    expandedGroups.clear();
    renderQueryTable();
}

/** 渲染分页控件 */
function renderQueryPagination(totalGroups) {
    const containers = [
        document.getElementById('queryPaginationTop'),
        document.getElementById('queryPagination')
    ].filter(Boolean);

    const totalGroupPages = Math.ceil(totalGroups / pageSize);
    const showPagination = totalGroupPages > 1;

    containers.forEach(container => {
        container.textContent = '';
        container.style.display = showPagination ? 'flex' : 'none';

        if (!showPagination) return;

        // 每页条数选择
        const sizeSelect = document.createElement('select');
        sizeSelect.style.cssText = 'padding:4px 8px;border:1px solid #d1d5db;border-radius:4px;font-size:12px;margin-right:12px;';
        [10, 20, 50, 100].forEach(size => {
            const opt = document.createElement('option');
            opt.value = size;
            opt.textContent = size + '组/页';
            if (size === pageSize) opt.selected = true;
            sizeSelect.appendChild(opt);
        });
        sizeSelect.onchange = () => {
            pageSize = parseInt(sizeSelect.value);
            currentPage = 1;
            renderQueryTable();
        };
        container.appendChild(sizeSelect);

        // 页码信息
        const info = document.createElement('span');
        info.style.cssText = 'font-size:12px;color:#6b7280;line-height:28px;margin-right:12px;';
        info.textContent = '第 ' + currentPage + '/' + totalGroupPages + ' 页，共 ' + totalGroups + ' 个网元';
        container.appendChild(info);

        // 上一页
        const prevBtn = document.createElement('button');
        prevBtn.className = 'btn btn-outline btn-sm';
        prevBtn.textContent = '上一页';
        prevBtn.disabled = currentPage <= 1;
        prevBtn.onclick = () => { currentPage--; renderQueryTable(); };
        container.appendChild(prevBtn);

        // 下一页
        const nextBtn = document.createElement('button');
        nextBtn.className = 'btn btn-outline btn-sm';
        nextBtn.textContent = '下一页';
        nextBtn.disabled = currentPage >= totalGroupPages;
        nextBtn.onclick = () => { currentPage++; renderQueryTable(); };
        container.appendChild(nextBtn);
    });
}

/** 导出Excel */
export function exportExcel() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const showInvalid = document.getElementById('queryShowInvalid').checked;
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);
    params.set('showInvalid', showInvalid);
    window.open(API + '/inspection/export' + (params.toString() ? '?' + params : ''), '_blank');
}

// ========== 趋势分析 ==========

let trendChart = null;
let trendData = null;
let trendChartMode = 'rx';

/** 打开趋势分析弹窗 */
export async function openTrendModal() {
    document.getElementById('trendModal').classList.remove('hidden');
    document.getElementById('trendTable').textContent = '';
    document.getElementById('trendSummary').textContent = '';
    trendData = null;
    if (trendChart) { trendChart.dispose(); trendChart = null; }

    // 加载轮次列表
    const rounds = await get('/inspection/rounds');
    const list = document.getElementById('trendRoundList');
    list.textContent = '';
    rounds.forEach(r => {
        const label = document.createElement('label');
        label.style.cssText = 'display:flex;align-items:center;gap:6px;padding:3px 0;cursor:pointer;color:var(--text,#374151);';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.className = 'trend-round-cb';
        cb.value = r.id;
        // 默认选中最近5个
        const span = document.createElement('span');
        span.textContent = '#' + r.id + ' ' + formatTime(r.startTime);
        label.appendChild(cb);
        label.appendChild(span);
        list.appendChild(label);
    });
    // 默认选中最近5个
    const cbs = list.querySelectorAll('.trend-round-cb');
    const selectCount = Math.min(5, cbs.length);
    for (let i = 0; i < selectCount; i++) cbs[i].checked = true;

    // 加载筛选下拉
    try {
        const networks = await get('/inventory/networks');
        const dl = document.getElementById('trendNetworkList');
        dl.textContent = '';
        networks.forEach(n => { const o = document.createElement('option'); o.value = n; dl.appendChild(o); });
    } catch (e) { /* ignore */ }
    try {
        const devices = await get('/connection/status');
        const dl = document.getElementById('trendNeList');
        dl.textContent = '';
        devices.forEach(d => { const o = document.createElement('option'); o.value = d.neId; o.textContent = d.neName; dl.appendChild(o); });
    } catch (e) { /* ignore */ }

    setTimeout(() => { if (trendChart) trendChart.resize(); }, 200);
}

/** 关闭趋势弹窗 */
export function closeTrendModal() {
    document.getElementById('trendModal').classList.add('hidden');
    if (trendChart) { trendChart.dispose(); trendChart = null; }
}

/** 全选轮次 */
export function trendSelectAllRounds() {
    document.querySelectorAll('.trend-round-cb').forEach(cb => cb.checked = true);
}
/** 清空轮次 */
export function trendDeselectAllRounds() {
    document.querySelectorAll('.trend-round-cb').forEach(cb => cb.checked = false);
}

/** 执行趋势查询 */
export async function runTrend() {
    const checked = [...document.querySelectorAll('.trend-round-cb:checked')].map(cb => parseInt(cb.value, 10));
    if (checked.length < 2) { showToast('请至少选择2个轮次', 'error'); return; }
    if (checked.length > 50) { showToast('最多选择50个轮次', 'error'); return; }

    const neId = document.getElementById('trendNe').value.trim();
    if (!neId) { showToast('请选择一个网元', 'error'); return; }

    const network = document.getElementById('trendNetwork').value.trim();
    let url = '/inspection/trend/multi?roundIds=' + checked.join(',');
    if (network) url += '&network=' + encodeURIComponent(network);
    if (neId) url += '&neId=' + encodeURIComponent(neId);

    const data = await get(url);
    trendData = data;

    document.getElementById('trendSummary').textContent =
        '时间轴 ' + data.timeline.length + ' 个轮次，共 ' + data.ports.length + ' 个端口';

    renderTrendTable(data);
    renderTrendChart(data);
}

/** 切换图表模式 */
export function switchTrendChart(mode) {
    trendChartMode = mode;
    if (trendData) renderTrendChart(trendData);
}

/** 渲染趋势 ECharts 折线图 */
function renderTrendChart(data) {
    const container = document.getElementById('trendChart');
    if (!trendChart) {
        trendChart = echarts.init(container);
        setTimeout(() => trendChart && trendChart.resize(), 100);
    } else {
        trendChart.resize();
    }

    const xLabels = data.timeline.map(t => formatTime(t.time));
    const isDark = document.body.getAttribute('data-theme') === 'dark';
    const textColor = isDark ? '#94a3b8' : '#6b7280';
    const splitColor = isDark ? '#334155' : '#e5e7eb';

    const colors = [
        '#3b82f6', '#ef4444', '#10b981', '#f59e0b', '#8b5cf6',
        '#ec4899', '#06b6d4', '#f97316', '#6366f1', '#14b8a6',
        '#e11d48', '#84cc16', '#0ea5e9', '#a855f7', '#64748b'
    ];

    // 构建series：每个端口两条线（tx/rx）取决于模式
    const series = [];
    const legendNames = [];
    let portIdx = 0;

    // 收集门限值（取第一个有数据的端口的门限，同网元同类型端口门限一致）
    let rxLow = null, rxHigh = null, txLow = null, txHigh = null;
    for (const port of data.ports) {
        for (const d of port.roundData) {
            if (d) {
                if (rxLow === null && d.rxLow != null) rxLow = d.rxLow;
                if (rxHigh === null && d.rxHigh != null) rxHigh = d.rxHigh;
                if (txLow === null && d.txLow != null) txLow = d.txLow;
                if (txHigh === null && d.txHigh != null) txHigh = d.txHigh;
                if (rxLow !== null && rxHigh !== null && txLow !== null && txHigh !== null) break;
            }
        }
        if (rxLow !== null && rxHigh !== null && txLow !== null && txHigh !== null) break;
    }

    for (const port of data.ports) {
        if (portIdx >= 20) break;
        const label = (port.portName && port.portName !== '-')
            ? (port.neName || '').substring(0, 10) + ' ' + port.portName
            : (port.neName || '').substring(0, 10) + ' 槽位' + port.slotNo + ' 端口' + port.portNo;
        const color = colors[portIdx % colors.length];

        if (trendChartMode === 'rx' || trendChartMode === 'both') {
            const rxData = port.roundData.map(d => d ? d.rxPower : null);
            const name = label + ' (Rx)';
            legendNames.push(name);
            const rxSeries = {
                name, type: 'line', data: rxData,
                smooth: true, symbol: 'circle', symbolSize: 6,
                lineStyle: { width: 2, color },
                itemStyle: { color },
                connectNulls: true
            };
            // 第一条 Rx 线加门限标线
            if (portIdx === 0 && (rxLow !== null || rxHigh !== null)) {
                const markLines = [];
                if (rxLow !== null) markLines.push({ yAxis: rxLow, lineStyle: { color: '#f59e0b', type: 'dashed', width: 1 }, label: { formatter: 'Rx低 ' + rxLow, color: '#f59e0b', fontSize: 10, position: 'insideEndTop' } });
                if (rxHigh !== null) markLines.push({ yAxis: rxHigh, lineStyle: { color: '#ef4444', type: 'dashed', width: 1 }, label: { formatter: 'Rx高 ' + rxHigh, color: '#ef4444', fontSize: 10, position: 'insideEndTop' } });
                rxSeries.markLine = { silent: true, data: markLines };
            }
            series.push(rxSeries);
        }
        if (trendChartMode === 'tx' || trendChartMode === 'both') {
            const txData = port.roundData.map(d => d ? d.txPower : null);
            const name = label + ' (Tx)';
            legendNames.push(name);
            const txSeries = {
                name, type: 'line', data: txData,
                smooth: true, symbol: 'diamond', symbolSize: 6,
                lineStyle: { width: 2, color, type: trendChartMode === 'both' ? 'dashed' : 'solid' },
                itemStyle: { color },
                connectNulls: true
            };
            if (portIdx === 0 && (txLow !== null || txHigh !== null)) {
                const markLines = [];
                if (txLow !== null) markLines.push({ yAxis: txLow, lineStyle: { color: '#06b6d4', type: 'dashed', width: 1 }, label: { formatter: 'Tx低 ' + txLow, color: '#06b6d4', fontSize: 10, position: 'insideEndBottom' } });
                if (txHigh !== null) markLines.push({ yAxis: txHigh, lineStyle: { color: '#8b5cf6', type: 'dashed', width: 1 }, label: { formatter: 'Tx高 ' + txHigh, color: '#8b5cf6', fontSize: 10, position: 'insideEndBottom' } });
                txSeries.markLine = { silent: true, data: markLines };
            }
            series.push(txSeries);
        }
        portIdx++;
    }

    const option = {
        tooltip: {
            trigger: 'axis',
            formatter: function(params) {
                const idx = params[0].dataIndex;
                const time = xLabels[idx];
                let s = '<b>' + time + '</b><br/>';
                params.forEach(p => {
                    if (p.value != null) {
                        s += p.marker + p.seriesName + ': <b>' + p.value.toFixed(2) + ' dBm</b><br/>';
                    }
                });
                return s;
            }
        },
        legend: {
            data: legendNames,
            textStyle: { color: textColor, fontSize: 10 },
            type: 'scroll', pageTextStyle: { color: textColor },
            top: 0, bottom: 20
        },
        grid: { left: 60, right: 20, top: 40, bottom: 30 },
        xAxis: {
            type: 'category', data: xLabels,
            axisLabel: { color: textColor, fontSize: 10, rotate: xLabels.length > 8 ? 30 : 0 },
            axisLine: { lineStyle: { color: splitColor } }
        },
        yAxis: {
            type: 'value', name: '功率 (dBm)',
            nameTextStyle: { color: textColor, fontSize: 11 },
            axisLabel: { color: textColor },
            splitLine: { lineStyle: { color: splitColor } }
        },
        series
    };

    trendChart.setOption(option, true);
}

/** 渲染趋势详情表格 */
function renderTrendTable(data) {
    const tbody = document.getElementById('trendTable');
    tbody.textContent = '';

    if (data.ports.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 7; td.className = 'empty'; td.textContent = '所选条件下无数据';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }

    for (const port of data.ports) {
        const tr = document.createElement('tr');

        tr.appendChild(createTextCell(port.neName || '-'));
        tr.appendChild(createTextCell(String(port.slotNo)));
        tr.appendChild(createTextCell(String(port.portNo)));
        tr.appendChild(createTextCell(port.portName || '-'));
        tr.appendChild(createTextCell(port.moduleTypeKey || '-'));

        // 发送趋势摘要：min ~ max
        const txValues = port.roundData.filter(d => d && d.txPower != null).map(d => d.txPower);
        const txTd = document.createElement('td');
        txTd.style.textAlign = 'right';
        if (txValues.length > 0) {
            const min = Math.min(...txValues).toFixed(1);
            const max = Math.max(...txValues).toFixed(1);
            txTd.textContent = txValues.length > 1 ? min + ' ~ ' + max : min;
            const delta = Math.max(...txValues) - Math.min(...txValues);
            if (delta > 3) txTd.style.color = '#dc2626';
        } else {
            txTd.textContent = '--';
        }
        tr.appendChild(txTd);

        // 接收趋势摘要
        const rxValues = port.roundData.filter(d => d && d.rxPower != null).map(d => d.rxPower);
        const rxTd = document.createElement('td');
        rxTd.style.textAlign = 'right';
        if (rxValues.length > 0) {
            const min = Math.min(...rxValues).toFixed(1);
            const max = Math.max(...rxValues).toFixed(1);
            rxTd.textContent = rxValues.length > 1 ? min + ' ~ ' + max : min;
            const delta = Math.max(...rxValues) - Math.min(...rxValues);
            if (delta > 3) rxTd.style.color = '#dc2626';
        } else {
            rxTd.textContent = '--';
        }
        tr.appendChild(rxTd);

        tbody.appendChild(tr);
    }
}

function formatPower(v) {
    return v != null ? v.toFixed(1) : '--';
}

function formatDelta(v) {
    if (v == null) return '--';
    const s = v > 0 ? '+' + v.toFixed(1) : v.toFixed(1);
    return s + ' dB';
}

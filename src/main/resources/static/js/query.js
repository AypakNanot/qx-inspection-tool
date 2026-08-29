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

// ========== 轮次对比 ==========

/** 打开对比弹窗 */
/** 对比图表实例 */
let compareChart = null;
let compareData = null;
let compareChartMode = 'tx';

export async function openCompareModal() {
    document.getElementById('compareModal').classList.remove('hidden');
    const rounds = await get('/inspection/rounds');
    ['compareRoundA', 'compareRoundB'].forEach((id, idx) => {
        const sel = document.getElementById(id);
        sel.textContent = '';
        rounds.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r.id;
            opt.textContent = '#' + r.id + ' ' + formatTime(r.startTime) + ' (' + r.status + ')';
            sel.appendChild(opt);
        });
        if (rounds.length > idx && sel.options.length > idx) {
            sel.selectedIndex = idx;
        }
    });
    document.getElementById('compareTable').textContent = '';
    document.getElementById('compareSummary').textContent = '';
    document.getElementById('compareChartBox').style.display = 'none';
    compareData = null;
    if (compareChart) { compareChart.dispose(); compareChart = null; }
    // 延迟 resize 让容器尺寸确定后再初始化图表
    setTimeout(() => { if (compareChart) compareChart.resize(); }, 200);
}

/** 关闭对比弹窗 */
export function closeCompareModal() {
    document.getElementById('compareModal').classList.add('hidden');
    if (compareChart) { compareChart.dispose(); compareChart = null; }
}

/** 切换图表模式 */
export function switchCompareChart(mode, btn) {
    compareChartMode = mode;
    document.querySelectorAll('#compareChartBox .stats-chart-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    if (compareData) renderCompareChart(compareData);
}

/** 执行对比 */
export async function runCompare() {
    const roundA = document.getElementById('compareRoundA').value;
    const roundB = document.getElementById('compareRoundB').value;
    if (!roundA || !roundB) { showToast('请选择两个轮次', 'error'); return; }
    if (roundA === roundB) { showToast('请选择不同的轮次', 'error'); return; }

    const data = await get('/inspection/compare?roundA=' + roundA + '&roundB=' + roundB);
    compareData = data;
    const tbody = document.getElementById('compareTable');
    tbody.textContent = '';

    document.getElementById('compareSummary').textContent =
        '基准 #' + data.roundA + ' (' + data.totalA + ' 条) vs 对比 #' + data.roundB + ' (' + data.totalB + ' 条)，变化 ' + data.changes.length + ' 条';

    if (data.changes.length === 0) {
        document.getElementById('compareChartBox').style.display = 'none';
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 11; td.className = 'empty'; td.textContent = '两次巡检结果无显著变化';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }

    // 渲染图表
    document.getElementById('compareChartBox').style.display = '';
    renderCompareChart(data);

    // 渲染表格
    data.changes.forEach(c => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(c.neName || '-'));
        tr.appendChild(createTextCell(c.portName || '-'));

        const typeTd = document.createElement('td');
        if (c.type === 'status_change') {
            typeTd.textContent = '状态变化';
            typeTd.style.color = '#dc2626';
            typeTd.style.fontWeight = '600';
        } else if (c.type === 'new') {
            typeTd.textContent = '新增';
            typeTd.style.color = '#16a34a';
        } else {
            typeTd.textContent = '功率变化';
            typeTd.style.color = '#d97706';
        }
        tr.appendChild(typeTd);

        tr.appendChild(createTextCell(formatPower(c.txPowerA)));
        tr.appendChild(createTextCell(formatPower(c.txPowerB)));
        const txDeltaTd = createTextCell(formatDelta(c.txDelta));
        if (c.txDelta != null && Math.abs(c.txDelta) > 2) txDeltaTd.style.color = '#dc2626';
        tr.appendChild(txDeltaTd);

        tr.appendChild(createTextCell(formatPower(c.rxPowerA)));
        tr.appendChild(createTextCell(formatPower(c.rxPowerB)));
        const rxDeltaTd = createTextCell(formatDelta(c.rxDelta));
        if (c.rxDelta != null && Math.abs(c.rxDelta) > 2) rxDeltaTd.style.color = '#dc2626';
        tr.appendChild(rxDeltaTd);

        const stATd = createTextCell(c.statusA || '-');
        if (c.statusA === '劣化' || c.statusA === '过载') stATd.style.color = '#dc2626';
        tr.appendChild(stATd);
        const stBTd = createTextCell(c.statusB || '-');
        if (c.statusB === '劣化' || c.statusB === '过载') stBTd.style.color = '#dc2626';
        tr.appendChild(stBTd);

        tbody.appendChild(tr);
    });
}

/** 渲染对比 ECharts 图表 */
function renderCompareChart(data) {
    const container = document.getElementById('compareChart');
    if (!compareChart) {
        compareChart = echarts.init(container);
        setTimeout(() => compareChart && compareChart.resize(), 100);
    } else {
        compareChart.resize();
    }

    const changes = data.changes;
    // 只显示前30条变化最大的
    const sorted = [...changes].sort((a, b) => {
        const da = Math.max(Math.abs(a.txDelta || 0), Math.abs(a.rxDelta || 0));
        const db = Math.max(Math.abs(b.txDelta || 0), Math.abs(b.rxDelta || 0));
        return db - da;
    }).slice(0, 30);

    const labels = sorted.map(c => (c.neName || '').substring(0, 8) + '\n' + (c.portName || c.portNo || ''));
    const txDeltas = sorted.map(c => c.txDelta != null ? parseFloat(c.txDelta.toFixed(2)) : 0);
    const rxDeltas = sorted.map(c => c.rxDelta != null ? parseFloat(c.rxDelta.toFixed(2)) : 0);

    const isDark = document.body.getAttribute('data-theme') === 'dark';
    const textColor = isDark ? '#94a3b8' : '#6b7280';
    const splitColor = isDark ? '#334155' : '#e5e7eb';

    let option;
    if (compareChartMode === 'tx' || compareChartMode === 'rx') {
        const deltas = compareChartMode === 'tx' ? txDeltas : rxDeltas;
        const label = compareChartMode === 'tx' ? '发送功率变化 (dB)' : '接收功率变化 (dB)';
        option = {
            tooltip: {
                trigger: 'axis',
                formatter: function(params) {
                    const p = params[0];
                    const idx = p.dataIndex;
                    const c = sorted[idx];
                    return '<b>' + (c.neName || '') + '</b><br/>' +
                        '端口: ' + (c.portName || c.portNo || '-') + '<br/>' +
                        label + ': <b style="color:' + (Math.abs(p.value) > 2 ? '#dc2626' : '#1a73e8') + '">' +
                        (p.value > 0 ? '+' : '') + p.value.toFixed(2) + ' dB</b>';
                }
            },
            grid: { left: 60, right: 20, top: 30, bottom: 80 },
            xAxis: {
                type: 'category', data: labels,
                axisLabel: { color: textColor, fontSize: 10, interval: 0, rotate: 45 },
                axisLine: { lineStyle: { color: splitColor } }
            },
            yAxis: {
                type: 'value', name: label,
                nameTextStyle: { color: textColor, fontSize: 11 },
                axisLabel: { color: textColor },
                splitLine: { lineStyle: { color: splitColor } }
            },
            series: [{
                type: 'bar', data: deltas,
                itemStyle: {
                    color: function(params) {
                        return params.value >= 0 ? '#f59e0b' : '#3b82f6';
                    },
                    borderRadius: [3, 3, 0, 0]
                },
                markLine: {
                    silent: true,
                    data: [
                        { yAxis: 2, lineStyle: { color: '#dc2626', type: 'dashed', width: 1 }, label: { formatter: '+2dB', color: '#dc2626', fontSize: 10 } },
                        { yAxis: -2, lineStyle: { color: '#3b82f6', type: 'dashed', width: 1 }, label: { formatter: '-2dB', color: '#3b82f6', fontSize: 10 } }
                    ]
                }
            }]
        };
    } else {
        // 综合对比：发送和接收并列
        option = {
            tooltip: {
                trigger: 'axis',
                formatter: function(params) {
                    const idx = params[0].dataIndex;
                    const c = sorted[idx];
                    let s = '<b>' + (c.neName || '') + '</b><br/>端口: ' + (c.portName || c.portNo || '-') + '<br/>';
                    params.forEach(p => {
                        s += p.marker + p.seriesName + ': <b>' + (p.value > 0 ? '+' : '') + p.value.toFixed(2) + ' dB</b><br/>';
                    });
                    return s;
                }
            },
            legend: {
                data: ['发送变化', '接收变化'],
                textStyle: { color: textColor, fontSize: 11 },
                top: 0
            },
            grid: { left: 60, right: 20, top: 30, bottom: 80 },
            xAxis: {
                type: 'category', data: labels,
                axisLabel: { color: textColor, fontSize: 10, interval: 0, rotate: 45 },
                axisLine: { lineStyle: { color: splitColor } }
            },
            yAxis: {
                type: 'value', name: '功率变化 (dB)',
                nameTextStyle: { color: textColor, fontSize: 11 },
                axisLabel: { color: textColor },
                splitLine: { lineStyle: { color: splitColor } }
            },
            series: [
                {
                    name: '发送变化', type: 'bar', data: txDeltas,
                    itemStyle: { color: '#f59e0b', borderRadius: [3, 3, 0, 0] }
                },
                {
                    name: '接收变化', type: 'bar', data: rxDeltas,
                    itemStyle: { color: '#3b82f6', borderRadius: [3, 3, 0, 0] }
                }
            ]
        };
    }

    compareChart.setOption(option, true);
}

function formatPower(v) {
    return v != null ? v.toFixed(1) : '--';
}

function formatDelta(v) {
    if (v == null) return '--';
    const s = v > 0 ? '+' + v.toFixed(1) : v.toFixed(1);
    return s + ' dB';
}

/**
 * 数据查询模块
 * 查询巡检结果，支持按轮次/网络/网元/状态筛选，按网元分组折叠，分页浏览，列排序，文本搜索，导出Excel
 */

import { get, API } from './api.js';

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

/** 格式化时间：去掉T和毫秒 */
function formatTime(t) {
    if (!t) return '-';
    return t.replace('T', ' ').replace(/\.\d+$/, '');
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
        applyFilterAndSort();
    } catch (e) { console.error('loadQueryResults', e); }
}

/** 应用筛选和排序 */
function applyFilterAndSort() {
    const statusFilter = document.getElementById('queryStatus').value;
    const showInvalid = document.getElementById('queryShowInvalid').checked;

    filteredResults = allResults.filter(r => {
        // 显示无效记录开关
        if (!showInvalid && !r.supported) return false;

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
        td.colSpan = 14; td.className = 'empty'; td.textContent = '暂无数据';
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

        // 空列占位（moduleTypeKey, laserState, vendorName, txPower, rxPower, status, txThreshold, rxThreshold）
        groupTr.appendChild(createTextCell(''));
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

/**
 * 数据查询模块
 * 查询巡检结果，支持按轮次/网络/网元/状态筛选，分页浏览，列排序，文本搜索，导出Excel
 */

import { get, API } from './api.js';

/** 全量查询结果 */
let allResults = [];
/** 筛选后结果 */
let filteredResults = [];
/** 分页状态 */
let currentPage = 1;
let pageSize = 100;
let sortField = '';
let sortOrder = 'asc';
let searchText = '';

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
            opt.textContent = '#' + r.id + ' ' + (r.startTime || '') + ' (' + r.status + ')';
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
        applyFilterAndSort();
    } catch (e) { console.error('loadQueryResults', e); }
}

/** 按状态筛选 */
function filterByStatus(data, status) {
    if (status === -1) return data.filter(r => !r.supported || r.supported === false);
    return data.filter(r => r.txPowerStatus === status || r.rxPowerStatus === status);
}

/** 应用筛选和排序 */
function applyFilterAndSort() {
    const statusFilter = document.getElementById('queryStatus').value;

    filteredResults = allResults.filter(r => {
        // 状态筛选
        if (statusFilter !== '') {
            if (statusFilter === '-1') {
                if (r.supported && r.supported !== false) return false;
            } else {
                const st = parseInt(statusFilter);
                if (r.txPowerStatus !== st && r.rxPowerStatus !== st) return false;
            }
        }
        // 文本搜索
        if (searchText) {
            const text = searchText.toLowerCase();
            const match = (r.neName || '').toLowerCase().includes(text) ||
                         (r.neId || '').toLowerCase().includes(text) ||
                         (r.portName || '').toLowerCase().includes(text) ||
                         (r.moduleTypeKey || '').toLowerCase().includes(text) ||
                         (r.slotNo != null && String(r.slotNo).includes(text)) ||
                         (r.portNo != null && String(r.portNo).includes(text));
            if (!match) return false;
        }
        return true;
    });

    // 排序
    if (sortField) {
        filteredResults.sort((a, b) => {
            let va = a[sortField];
            let vb = b[sortField];
            if (va == null) va = '';
            if (vb == null) vb = '';
            if (typeof va === 'number' && typeof vb === 'number') {
                return sortOrder === 'asc' ? va - vb : vb - va;
            }
            if (typeof va === 'string') va = va.toLowerCase();
            if (typeof vb === 'string') vb = vb.toLowerCase();
            if (va < vb) return sortOrder === 'asc' ? -1 : 1;
            if (va > vb) return sortOrder === 'asc' ? 1 : -1;
            return 0;
        });
    }

    renderQueryTable();
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

/** 渲染查询结果表格（带分页） */
function renderQueryTable() {
    const tbody = document.getElementById('queryTable');
    tbody.textContent = '';
    document.getElementById('queryCount').textContent =
        '筛选结果：' + filteredResults.length + ' / ' + allResults.length + ' 条';

    if (!filteredResults || filteredResults.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 11; td.className = 'empty'; td.textContent = '暂无数据';
        tr.appendChild(td); tbody.appendChild(tr);
        renderQueryPagination(0);
        return;
    }

    // 分页切片
    const totalPages = Math.ceil(filteredResults.length / pageSize);
    if (currentPage > totalPages) currentPage = totalPages;
    const start = (currentPage - 1) * pageSize;
    const end = Math.min(start + pageSize, filteredResults.length);
    const pageData = filteredResults.slice(start, end);

    pageData.forEach(r => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(r.neName || '-'));
        tr.appendChild(createTextCell(r.slotNo != null ? String(r.slotNo) : '-'));
        tr.appendChild(createTextCell(r.portNo != null ? String(r.portNo) : '-'));
        tr.appendChild(createTextCell(r.portName || '-'));
        tr.appendChild(createTextCell(r.moduleTypeKey || '--'));

        const txTd = document.createElement('td');
        if (r.supported && r.txPower != null) {
            txTd.textContent = r.txPower.toFixed(1);
            if (r.txPowerStatus > 0) txTd.style.color = '#dc2626';
        } else {
            txTd.textContent = '--';
            txTd.style.color = '#9ca3af';
        }
        tr.appendChild(txTd);

        const rxTd = document.createElement('td');
        if (r.supported && r.rxPower != null) {
            rxTd.textContent = r.rxPower.toFixed(1);
            if (r.rxPowerStatus > 0) rxTd.style.color = '#dc2626';
        } else {
            rxTd.textContent = '--';
            rxTd.style.color = '#9ca3af';
        }
        tr.appendChild(rxTd);

        const statusTd = document.createElement('td');
        const status = getPortStatus(r);
        const badge = document.createElement('span');
        badge.className = 'badge ' + status.cls;
        badge.textContent = status.text;
        statusTd.appendChild(badge);
        tr.appendChild(statusTd);

        tr.appendChild(createTextCell(r.txLowThreshold != null ? r.txLowThreshold + '~' + r.txHighThreshold : '-'));
        tr.appendChild(createTextCell(r.lowThreshold != null ? r.lowThreshold + '~' + r.highThreshold : '-'));
        tr.appendChild(createTextCell(r.inspectionTime || '-'));
        tbody.appendChild(tr);
    });

    renderQueryPagination(filteredResults.length);
}

/** 渲染分页控件 */
function renderQueryPagination(total) {
    const containers = [
        document.getElementById('queryPaginationTop'),
        document.getElementById('queryPagination')
    ].filter(Boolean);

    const totalPages = Math.ceil(total / pageSize);
    const showPagination = totalPages > 1;

    containers.forEach(container => {
        container.textContent = '';
        container.style.display = showPagination ? 'flex' : 'none';

        if (!showPagination) return;

        // 每页条数选择
        const sizeSelect = document.createElement('select');
        sizeSelect.style.cssText = 'padding:4px 8px;border:1px solid #d1d5db;border-radius:4px;font-size:12px;margin-right:12px;';
        [20, 50, 100, 200, 500, 1000].forEach(size => {
            const opt = document.createElement('option');
            opt.value = size;
            opt.textContent = size + '条/页';
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
        info.textContent = '第 ' + currentPage + '/' + totalPages + ' 页，共 ' + total + ' 条';
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
        nextBtn.disabled = currentPage >= totalPages;
        nextBtn.onclick = () => { currentPage++; renderQueryTable(); };
        container.appendChild(nextBtn);
    });
}

/** 导出Excel */
export function exportExcel() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);
    window.open(API + '/inspection/export' + (params.toString() ? '?' + params : ''), '_blank');
}

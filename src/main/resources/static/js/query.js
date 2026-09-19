/**
 * 数据查询模块
 * 按链路查询巡检结果：A/Z 端各 12 列（网元3 + 光模块5 + 误码1 + 带宽3）
 * 支持轮次/网络筛选、文本搜索、分页浏览、导出 Excel
 */

import { get, API } from './api.js';

/** 全量链路巡检结果 */
let allLinkResults = [];
/** 筛选后的链路巡检结果 */
let filteredLinkResults = [];
/** 分页状态 */
let currentPage = 1;
let pageSize = 20;
let searchText = '';

/** 3 行合并表头结构（与导出 Excel 一致） */
const HEADER_ROWS = [
    [
        { text: '序号', rowspan: 3, width: '50px' },
        { text: '链路名称', rowspan: 3, minWidth: '150px' },
        { text: 'A端', colspan: 12 },
        { text: 'Z端', colspan: 12 }
    ],
    [
        { text: '网元', colspan: 3 },
        { text: '光模块', colspan: 5 },
        { text: '误码' },
        { text: '带宽', colspan: 3 },
        { text: '网元', colspan: 3 },
        { text: '光模块', colspan: 5 },
        { text: '误码' },
        { text: '带宽', colspan: 3 }
    ],
    [
        { text: '名称' }, { text: '类型' }, { text: '端口' },
        { text: '类型' }, { text: 'TX\n(dBm)' }, { text: 'RX\n(dBm)' },
        { text: 'TX\n状态' }, { text: 'RX\n状态' }, { text: 'RS\n错误秒' },
        { text: '总\n(Mbps)' }, { text: '已用\n(Mbps)' }, { text: '利用率' },
        { text: '名称' }, { text: '类型' }, { text: '端口' },
        { text: '类型' }, { text: 'TX\n(dBm)' }, { text: 'RX\n(dBm)' },
        { text: 'TX\n状态' }, { text: 'RX\n状态' }, { text: 'RS\n错误秒' },
        { text: '总\n(Mbps)' }, { text: '已用\n(Mbps)' }, { text: '利用率' }
    ]
];

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

/** 功率单元格：正常显示一位小数，异常标红 */
function powerCell(value, status) {
    const td = document.createElement('td');
    if (value == null) {
        td.textContent = '--';
        td.style.color = '#9ca3af';
        return td;
    }
    td.textContent = value.toFixed(1);
    if (status && status !== '正常' && status !== '--') td.style.color = '#dc2626';
    return td;
}

/** 状态单元格：正常为绿色，其余标红 */
function statusCell(text) {
    const td = document.createElement('td');
    const value = text || '--';
    td.textContent = value;
    if (value === '正常') td.style.color = '#16a34a';
    else if (value !== '--') td.style.color = '#dc2626';
    return td;
}

/** 数值单元格：整数原样，空值显示 -- */
function numberCell(value) {
    const td = document.createElement('td');
    if (value == null) {
        td.textContent = '--';
        td.style.color = '#9ca3af';
    } else {
        td.textContent = String(value);
    }
    return td;
}

/** 利用率单元格：不带 % 后缀，与导出 Excel 一致 */
function usageCell(value) {
    const td = document.createElement('td');
    if (value == null) {
        td.textContent = '--';
        td.style.color = '#9ca3af';
        return td;
    }
    td.textContent = value.toFixed(1);
    if (value >= 90) td.style.color = '#dc2626';
    else if (value >= 70) td.style.color = '#f59e0b';
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

/** 加载筛选条件（网络 + 网元列表） */
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
            opt.value = d.neName; opt.textContent = d.neName;
            dl.appendChild(opt);
        });
    } catch (e) { console.error('loadQueryDevices', e); }
}

/** 渲染 3 行合并表头 */
function updateTableHeader() {
    const thead = document.getElementById('queryTableHead');
    if (!thead) return;

    thead.textContent = '';
    HEADER_ROWS.forEach(cells => {
        const tr = document.createElement('tr');
        cells.forEach(h => {
            const th = document.createElement('th');
            if (h.rowspan) th.rowSpan = h.rowspan;
            if (h.colspan) th.colSpan = h.colspan;
            if (h.width) th.style.width = h.width;
            if (h.minWidth) th.style.minWidth = h.minWidth;
            const lines = h.text.split('\n');
            lines.forEach((line, i) => {
                if (i > 0) th.appendChild(document.createElement('br'));
                th.appendChild(document.createTextNode(line));
            });
            tr.appendChild(th);
        });
        thead.appendChild(tr);
    });
}

/** 加载查询结果（链路口径） */
export async function loadQueryResults() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);

    try {
        allLinkResults = await get('/inspection/link-results' + (params.toString() ? '?' + params : ''));
        allLinkResults.sort((a, b) => (a.seqNo || 0) - (b.seqNo || 0));
        currentPage = 1;
        updateTableHeader();
        applyFilterAndSort();
    } catch (e) { console.error('loadQueryResults', e); }
}

/** 文本搜索 */
export function searchQuery(text) {
    searchText = text.trim();
    currentPage = 1;
    applyFilterAndSort();
}

/** 应用筛选（链路名称 / 网元名 / 端口名 / 网元筛选框） */
function applyFilterAndSort() {
    const neFilter = (document.getElementById('queryNe').value || '').trim().toLowerCase();
    filteredLinkResults = allLinkResults.filter(r => {
        if (neFilter) {
            const hitNe = [r.aNeName, r.zNeName, r.aNeId, r.zNeId]
                .some(v => v && v.toLowerCase().includes(neFilter));
            if (!hitNe) return false;
        }
        if (searchText) {
            const text = searchText.toLowerCase();
            return [r.linkName, r.aNeName, r.aPortName, r.zNeName, r.zPortName]
                .some(v => v && v.toLowerCase().includes(text));
        }
        return true;
    });
    renderLinkQueryTable();
}

/** 渲染单端 12 列 */
function appendSide(row, r, prefix) {
    row.appendChild(createTextCell(r[prefix + 'NeName'] || '-'));
    row.appendChild(createTextCell(r[prefix + 'NeTypeName'] || '-'));
    row.appendChild(createTextCell(r[prefix + 'PortName'] || '-'));
    row.appendChild(createTextCell(r[prefix + 'ModuleType'] || '--'));
    row.appendChild(powerCell(r[prefix + 'TxPower'], r[prefix + 'TxStatus']));
    row.appendChild(powerCell(r[prefix + 'RxPower'], r[prefix + 'RxStatus']));
    row.appendChild(statusCell(r[prefix + 'TxStatus']));
    row.appendChild(statusCell(r[prefix + 'RxStatus']));
    row.appendChild(numberCell(r[prefix + 'RsErrorSec']));
    row.appendChild(numberCell(r[prefix + 'TotalBandwidth']));
    row.appendChild(numberCell(r[prefix + 'UsedBandwidth']));
    row.appendChild(usageCell(r[prefix + 'BandwidthUsage']));
}

/** 渲染链路查询结果表格 */
function renderLinkQueryTable() {
    const tbody = document.getElementById('queryTable');
    tbody.textContent = '';
    document.getElementById('queryCount').textContent =
        '筛选结果：' + filteredLinkResults.length + ' / ' + allLinkResults.length + ' 条';

    if (filteredLinkResults.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 26;
        td.className = 'empty';
        td.textContent = '暂无链路巡检数据，请先执行一次巡检，完成后在此查看结果';
        tr.appendChild(td); tbody.appendChild(tr);
        renderPagination(0);
        return;
    }

    const total = filteredLinkResults.length;
    const totalPages = Math.ceil(total / pageSize);
    if (currentPage > totalPages) currentPage = totalPages;
    const start = (currentPage - 1) * pageSize;
    const pageData = filteredLinkResults.slice(start, start + pageSize);

    pageData.forEach(r => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(r.seqNo || ''));
        const linkTd = document.createElement('td');
        linkTd.textContent = r.linkName || '-';
        linkTd.title = r.linkName || '';
        tr.appendChild(linkTd);
        appendSide(tr, r, 'a');
        appendSide(tr, r, 'z');
        tbody.appendChild(tr);
    });

    renderPagination(total);
}

/** 渲染分页控件 */
function renderPagination(total) {
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
        sizeSelect.style.cssText = 'padding:4px 8px;border:1px solid #d1d5db;border-radius:4px;font-size:12px;';
        [10, 20, 50, 100].forEach(size => {
            const opt = document.createElement('option');
            opt.value = size;
            opt.textContent = size + '条/页';
            if (size === pageSize) opt.selected = true;
            sizeSelect.appendChild(opt);
        });
        sizeSelect.onchange = () => {
            pageSize = parseInt(sizeSelect.value, 10);
            currentPage = 1;
            renderLinkQueryTable();
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
        prevBtn.onclick = () => { currentPage--; renderLinkQueryTable(); };
        container.appendChild(prevBtn);

        // 下一页
        const nextBtn = document.createElement('button');
        nextBtn.className = 'btn btn-outline btn-sm';
        nextBtn.textContent = '下一页';
        nextBtn.disabled = currentPage >= totalPages;
        nextBtn.onclick = () => { currentPage++; renderLinkQueryTable(); };
        container.appendChild(nextBtn);
    });
}

/** 导出 Excel（链路口径） */
export function exportExcel() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);
    window.open(API + '/inspection/export-link' + (params.toString() ? '?' + params : ''), '_blank');
}

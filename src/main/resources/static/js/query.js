/**
 * 数据查询模块
 * 查询巡检结果，支持按轮次/网络/网元/状态筛选，导出Excel
 */

import { get, API } from './api.js';

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
        let data = await get('/inspection/results' + (params.toString() ? '?' + params : ''));
        const statusFilter = document.getElementById('queryStatus').value;
        if (statusFilter !== '') {
            data = filterByStatus(data, parseInt(statusFilter));
        }
        renderQueryTable(data);
    } catch (e) { console.error('loadQueryResults', e); }
}

/** 按状态筛选结果 */
function filterByStatus(data, status) {
    return data.filter(r => {
        if (status === -1) return !r.supported || r.supported === false;
        return r.txPowerStatus === status || r.rxPowerStatus === status;
    });
}

/** 渲染查询结果表格 */
function renderQueryTable(data) {
    const tbody = document.getElementById('queryTable');
    tbody.textContent = '';
    document.getElementById('queryCount').textContent = '共 ' + data.length + ' 条记录';
    if (!data || data.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 10; td.className = 'empty'; td.textContent = '暂无数据';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }
    data.slice(0, 500).forEach(r => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(r.neName || '-'));
        tr.appendChild(createTextCell(r.slotNo != null ? String(r.slotNo) : '-'));
        tr.appendChild(createTextCell(r.portNo != null ? String(r.portNo) : '-'));
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

/** 导出Excel */
export function exportExcel() {
    const roundId = document.getElementById('queryRound').value;
    const network = document.getElementById('queryNetwork').value.trim();
    const params = new URLSearchParams();
    if (roundId) params.set('roundId', roundId);
    if (network) params.set('network', network);
    window.open(API + '/inspection/export' + (params.toString() ? '?' + params : ''), '_blank');
}

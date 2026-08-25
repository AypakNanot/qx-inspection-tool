/**
 * 任务配置模块
 * 管理定时巡检配置、手动触发巡检
 */

import { get, post } from './api.js';

/** 加载定时巡检状态 */
export async function loadScheduleStatus() {
    try {
        const d = await get('/inspection/schedule');
        document.getElementById('schedEnabled').checked = d.enabled;
        const statusText = '上次运行: ' + (d.lastRunTime || '从未') + ' | 状态: ' + (d.lastRunStatus || '-');
        document.getElementById('schedStatus').textContent = statusText;
    } catch (e) { console.error('loadScheduleStatus', e); }
}

/** 启用/禁用定时巡检 */
export async function toggleSchedule(enabled) {
    try {
        await post('/inspection/schedule/toggle?enabled=' + enabled);
        loadScheduleStatus();
    } catch (e) { alert('设置失败: ' + e.message); }
}

/** 切换定时巡检范围（全网/指定网络） */
export function toggleSchedScope(val) {
    document.getElementById('schedNetworkGroup').style.display = val === 'NETWORK' ? '' : 'none';
}

/** 切换手动巡检范围（全网/指定网络/指定网元） */
export function toggleManualScope(val) {
    document.getElementById('manualNetworkGroup').style.display = val === 'NETWORK' ? '' : 'none';
    document.getElementById('manualNeGroup').style.display = val === 'SINGLE' ? '' : 'none';
}

/** 加载网络列表（定时+手动共用） */
export async function loadTaskNetworks() {
    try {
        const data = await get('/inventory/networks');
        ['schedNetworkList', 'manualNetworkList'].forEach(id => {
            const dl = document.getElementById(id);
            dl.textContent = '';
            data.forEach(n => {
                const opt = document.createElement('option');
                opt.value = n;
                dl.appendChild(opt);
            });
        });
    } catch (e) { console.error('loadTaskNetworks', e); }
}

/** 加载设备列表（手动巡检-指定网元） */
export async function loadTaskDevices() {
    try {
        const devices = await get('/connection/status');
        const dl = document.getElementById('manualNeList');
        dl.textContent = '';
        devices.forEach(d => {
            const opt = document.createElement('option');
            opt.value = d.neId;
            opt.textContent = d.neName + ' (' + d.ipAddr + ')';
            dl.appendChild(opt);
        });
    } catch (e) { console.error('loadTaskDevices', e); }
}

/** 启动手动巡检 */
export async function startManualInspection(switchPageFn) {
    const btn = document.getElementById('btnManualInspection');
    btn.disabled = true;
    btn.textContent = '启动中...';
    const scope = document.getElementById('manualScope').value;
    const params = new URLSearchParams();
    if (scope === 'NETWORK') {
        const network = document.getElementById('manualNetwork').value.trim();
        if (!network) { alert('请选择网络'); btn.disabled = false; btn.textContent = '立即巡检'; return; }
        params.set('network', network);
    } else if (scope === 'SINGLE') {
        const neId = document.getElementById('manualNe').value.trim();
        if (!neId) { alert('请选择网元'); btn.disabled = false; btn.textContent = '立即巡检'; return; }
        params.set('neId', neId);
    }
    try {
        const d = await post('/inspection/start' + (params.toString() ? '?' + params : ''));
        alert('巡检已启动: 轮次 #' + d.roundId + ', ' + d.totalDevices + ' 台设备');
        switchPageFn(document.querySelector('[data-page="page-progress"]'));
    } catch (e) { alert('启动失败: ' + e.message); }
    btn.disabled = false;
    btn.textContent = '立即巡检';
}

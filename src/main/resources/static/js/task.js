/**
 * 任务配置模块
 * 管理定时巡检配置、手动触发巡检
 */

import { get, post } from './api.js';

/** 预设 Cron 表达式列表，用于匹配当前值 */
const PRESET_CRONS = [
    '0 0 2 * * ?',      // 每天凌晨 2:00
    '0 0 */2 * * ?',    // 每 2 小时
    '0 0 * * * ?',      // 每小时整点
    '0 */30 * * * ?',   // 每 30 分钟
    '0 0 8 * * 1-5',   // 工作日早 8:00
    '0 0 2 * * 1',     // 每周一凌晨 2:00
    '0 0 2 1 * ?'      // 每月 1 号凌晨 2:00
];

/** 预设对应的中文描述 */
const PRESET_DESC = {
    '0 0 2 * * ?': '每天凌晨 2:00',
    '0 0 */2 * * ?': '每 2 小时',
    '0 0 * * * ?': '每小时整点',
    '0 */30 * * * ?': '每 30 分钟',
    '0 0 8 * * 1-5': '工作日早 8:00',
    '0 0 2 * * 1': '每周一凌晨 2:00',
    '0 0 2 1 * ?': '每月 1 号凌晨 2:00'
};

/** 加载定时巡检状态 */
export async function loadScheduleStatus() {
    try {
        const d = await get('/inspection/schedule');
        document.getElementById('schedEnabled').checked = d.enabled;

        // 根据当前 cron 匹配预设
        const cron = d.cronExpression || '0 0 2 * * ?';
        const preset = document.getElementById('schedPreset');
        if (PRESET_CRONS.includes(cron)) {
            preset.value = cron;
            document.getElementById('schedCronGroup').style.display = 'none';
        } else {
            preset.value = 'custom';
            document.getElementById('schedCronGroup').style.display = '';
            document.getElementById('schedCron').value = cron;
        }

        // 显示状态信息
        const enableStatus = d.enabled ? '已启用' : '未启用';
        const enableColor = d.enabled ? '#16a34a' : '#6b7280';
        const freqDesc = PRESET_DESC[cron] || cron;
        const scopeDesc = d.scope === 'NETWORK' ? '指定网络' : '全网';
        const lastRun = d.lastRunTime || '从未';
        const lastStatus = d.lastRunStatus || '-';

        const statusEl = document.getElementById('schedStatus');
        while (statusEl.firstChild) statusEl.removeChild(statusEl.firstChild);
        statusEl.style.lineHeight = '1.8';

        const line1 = document.createElement('div');
        const s1 = document.createElement('span');
        s1.textContent = '状态: ';
        line1.appendChild(s1);
        const s1v = document.createElement('span');
        s1v.style.color = enableColor;
        s1v.style.fontWeight = '600';
        s1v.textContent = enableStatus;
        line1.appendChild(s1v);
        const s2 = document.createElement('span');
        s2.textContent = ' | 频率: ' + freqDesc + ' | 范围: ' + scopeDesc;
        line1.appendChild(s2);
        statusEl.appendChild(line1);

        const line2 = document.createElement('div');
        line2.textContent = '上次运行: ' + lastRun + ' | 结果: ' + lastStatus;
        statusEl.appendChild(line2);
    } catch (e) { console.error('loadScheduleStatus', e); }
}

/** 启用/禁用定时巡检 */
export async function toggleSchedule(enabled) {
    try {
        await post('/inspection/schedule/toggle?enabled=' + enabled);
        loadScheduleStatus();
    } catch (e) { alert('设置失败: ' + e.message); }
}

/** 预设频率切换 */
export function onSchedPresetChange(val) {
    const cronGroup = document.getElementById('schedCronGroup');
    if (val === 'custom') {
        cronGroup.style.display = '';
    } else {
        cronGroup.style.display = 'none';
    }
}

/** 获取当前 Cron 表达式 */
export function getCurrentCron() {
    const preset = document.getElementById('schedPreset').value;
    if (preset === 'custom') {
        return document.getElementById('schedCron').value.trim();
    }
    return preset;
}

/** 保存定时巡检配置 */
export async function saveScheduleConfig() {
    const body = {
        enabled: document.getElementById('schedEnabled').checked,
        scope: document.getElementById('schedScope').value,
        network: document.getElementById('schedNetwork').value.trim(),
        cronExpression: getCurrentCron()
    };
    try {
        await post('/inspection/schedule/config', body);
        const msg = document.getElementById('schedConfigMsg');
        msg.textContent = '配置保存成功';
        msg.style.display = 'block';
        setTimeout(() => { msg.style.display = 'none'; }, 2000);
        loadScheduleStatus();
    } catch (e) {
        alert('保存失败: ' + e.message);
    }
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

/** 加载采集参数 */
export async function loadCollectParams() {
    try {
        const d = await get('/inspection/collect-params');
        document.getElementById('paramConcurrency').value = d.concurrency || 10;
        document.getElementById('paramMaxRounds').value = d.maxRounds || 10;
    } catch (e) { console.error('loadCollectParams', e); }
}

/** 保存采集参数 */
export async function saveCollectParams() {
    const body = {
        concurrency: parseInt(document.getElementById('paramConcurrency').value) || 10,
        maxRounds: parseInt(document.getElementById('paramMaxRounds').value) || 10
    };
    try {
        await post('/inspection/collect-params', body);
        const msg = document.getElementById('collectParamsMsg');
        msg.textContent = '参数保存成功';
        msg.style.display = 'block';
        setTimeout(() => { msg.style.display = 'none'; }, 2000);
    } catch (e) {
        alert('保存失败: ' + e.message);
    }
}

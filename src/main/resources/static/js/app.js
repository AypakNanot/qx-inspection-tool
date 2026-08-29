/**
 * 应用入口模块
 * 初始化页面、注册全局事件、挂载onclick处理器
 */

import { loadDevices, loadGlobalConfig, saveGlobalConfig, syncDevices, clearDataType, clearConnProfiles, toggleAllClearCb, clearSelectedData, connectAll, disconnectAll, connectSingle, disconnectSingle, closeDeviceModal, saveDeviceConfig, deleteDeviceConfig, searchDevices, filterByNetwork, filterByStatus, sortBy, dismissFirstUseHint } from './device.js';
import { loadStatsOverview, loadStatsNetworks, loadStats, switchStatsChart, resizeChart } from './stats.js';
import { loadGlobalThreshold, saveGlobalThreshold, loadThresholds, openThresholdModal, closeThresholdModal, saveThreshold, onModuleSelectChange } from './threshold.js';
import { loadScheduleStatus, toggleSchedule, toggleSchedScope, toggleManualScope, loadTaskNetworks, loadTaskDevices, startManualInspection, onSchedPresetChange, saveScheduleConfig, loadCollectParams, saveCollectParams } from './task.js';
import { loadProgress, stopProgressPoll } from './progress.js';
import { loadQueryRounds, loadQueryFilters, loadQueryResults, exportExcel, searchQuery, sortQueryBy, expandAll, collapseAll } from './query.js';
import { loadClockTopology, refreshClockTopology, resizeClockChart } from './clock.js';
import { showToast } from './toast.js';
import { loadSyncStatus, syncEssential, syncAll, clearSyncData, loadMysqlConfig, saveMysqlConfig, testMysqlConnection } from './sync.js';
import { get } from './api.js';

/** 页面标题映射 */
const TITLES = {
    'page-device': ['设备管理', '设备管理'],
    'page-stats': ['类型统计', '统计 / 类型统计'],
    'page-threshold': ['门限配置', '光功率巡检 / 门限配置'],
    'page-task': ['任务配置', '光功率巡检 / 任务配置'],
    'page-progress': ['任务进度', '光功率巡检 / 任务进度'],
    'page-query': ['数据查询', '光功率巡检 / 数据查询'],
    'page-clock': ['时钟拓扑', '时钟管理 / 时钟拓扑'],
    'page-maintenance': ['数据维护', '维护 / 数据维护']
};

/** 切换页面（侧边栏导航） */
function switchPage(el) {
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
    el.classList.add('active');
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.getElementById(el.dataset.page).classList.add('active');
    const t = TITLES[el.dataset.page] || ['', ''];
    document.getElementById('pageTitle').textContent = t[0];
    document.getElementById('breadcrumb').textContent = t[1];

    const page = el.dataset.page;
    if (page === 'page-stats') {
        loadStatsOverview();
        loadStats();
        setTimeout(resizeChart, 100);
    }
    if (page === 'page-threshold') {
        loadGlobalThreshold();
        loadThresholds();
    }
    if (page === 'page-task') {
        loadScheduleStatus();
        loadTaskNetworks();
        loadTaskDevices();
        loadCollectParams();
    }
    if (page === 'page-progress') {
        loadProgress();
    } else {
        stopProgressPoll();
    }
    if (page === 'page-query') {
        loadQueryRounds();
        loadQueryFilters();
        loadQueryResults();
    }
    if (page === 'page-clock') {
        loadClockTopology();
        setTimeout(resizeClockChart, 100);
    }
    if (page === 'page-maintenance') {
        loadMysqlConfig();
        loadSyncStatus();
    }
}

/** 折叠面板展开/收起 */
function togglePanel(header) {
    header.classList.toggle('collapsed');
    header.nextElementSibling.classList.toggle('hidden');
}

// 将函数挂载到全局作用域，供HTML中的onclick调用
window.switchPage = switchPage;
window.togglePanel = togglePanel;
window.loadDevices = loadDevices;
window.saveGlobalConfig = saveGlobalConfig;
window.syncDevices = syncDevices;
window.clearDataType = (type, label) => clearDataType(type, label);
window.clearConnProfiles = clearConnProfiles;
window.toggleAllClearCb = toggleAllClearCb;
window.clearSelectedData = clearSelectedData;
window.connectAll = (e) => connectAll(e.target);
window.disconnectAll = disconnectAll;
window.connectSingle = connectSingle;
window.disconnectSingle = disconnectSingle;
window.closeDeviceModal = closeDeviceModal;
window.saveDeviceConfig = saveDeviceConfig;
window.deleteDeviceConfig = deleteDeviceConfig;
window.searchDevices = searchDevices;
window.filterByNetwork = (val) => filterByNetwork(val);
window.filterByStatus = (val) => filterByStatus(val);
window.sortBy = sortBy;
window.dismissFirstUseHint = dismissFirstUseHint;
window.loadStats = loadStats;
window.switchStatsChart = switchStatsChart;
window.saveGlobalThreshold = saveGlobalThreshold;
window.loadThresholds = loadThresholds;
window.openThresholdModal = (levelType) => openThresholdModal(levelType);
window.closeThresholdModal = closeThresholdModal;
window.saveThreshold = saveThreshold;
window.onModuleSelectChange = onModuleSelectChange;
window.toggleSchedule = (checked) => toggleSchedule(checked);
window.onSchedPresetChange = onSchedPresetChange;
window.saveScheduleConfig = saveScheduleConfig;
window.loadCollectParams = loadCollectParams;
window.saveCollectParams = saveCollectParams;
window.toggleSchedScope = toggleSchedScope;
window.toggleManualScope = toggleManualScope;
window.startManualInspection = () => startManualInspection(switchPage);
window.loadProgress = loadProgress;
window.loadQueryResults = loadQueryResults;
window.exportExcel = exportExcel;
window.searchQuery = searchQuery;
window.sortQueryBy = sortQueryBy;
window.expandAll = expandAll;
window.collapseAll = collapseAll;
window.refreshClockTopology = refreshClockTopology;
window.showToast = showToast;
window.loadSyncStatus = loadSyncStatus;
window.syncEssential = syncEssential;
window.syncAll = syncAll;
window.clearSyncData = clearSyncData;
window.loadMysqlConfig = loadMysqlConfig;
window.saveMysqlConfig = saveMysqlConfig;
window.testMysqlConnection = testMysqlConnection;

// 初始化加载
loadDevices();
loadGlobalConfig();
loadStatsOverview();
loadStatsNetworks();
checkWorkflow();

window.addEventListener('resize', () => { resizeChart(); resizeClockChart(); });

// ========== 快速开始流程引导 ==========

const WORKFLOW_STEPS = [
    { id: 'config',  label: '配置数据源', page: 'page-maintenance' },
    { id: 'sync',    label: '同步设备',   page: 'page-maintenance' },
    { id: 'connect', label: '连接设备',   page: 'page-device' },
    { id: 'inspect', label: '执行巡检',   page: 'page-task' },
    { id: 'query',   label: '查看结果',   page: 'page-query' }
];

async function checkWorkflow() {
    const [devices, rounds, results] = await Promise.all([
        (async () => { try { const d = await get('/connection/status'); return Array.isArray(d) ? d : []; } catch { return []; } })(),
        (async () => { try { const d = await get('/inspection/rounds'); return Array.isArray(d) ? d : []; } catch { return []; } })(),
        (async () => { try { const d = await get('/inspection/results'); return Array.isArray(d) ? d : []; } catch { return []; } })()
    ]);

    const hasDevices = devices.length > 0;
    const hasConnected = devices.some(c => c.connectionStatus === 1);
    const hasRounds = rounds.length > 0;
    const hasResults = results.length > 0;

    // 有设备说明已配置数据源并同步
    const configDone = hasDevices;
    const syncDone = hasDevices;
    const connectDone = hasConnected;
    const inspectDone = hasRounds;
    const queryDone = hasResults;

    renderWorkflow([configDone, syncDone, connectDone, inspectDone, queryDone]);
}

function renderWorkflow(statuses) {
    const container = document.getElementById('workflowSteps');
    const list = document.getElementById('workflowList');
    if (!container || !list) return;

    // 全部完成则隐藏
    if (statuses.every(s => s)) {
        container.style.display = 'none';
        return;
    }

    container.style.display = '';
    list.textContent = '';

    let foundCurrent = false;
    WORKFLOW_STEPS.forEach((step, i) => {
        const done = statuses[i];
        const current = !done && !foundCurrent;
        if (current) foundCurrent = true;

        const div = document.createElement('div');
        div.className = 'wf-step' + (done ? ' wf-done' : '') + (current ? ' wf-current' : '');
        div.onclick = () => {
            const nav = document.querySelector(`[data-page="${step.page}"]`);
            if (nav) switchPage(nav);
        };

        const num = document.createElement('span');
        num.className = 'wf-num';
        num.textContent = done ? '✓' : (i + 1);
        div.appendChild(num);

        const label = document.createElement('span');
        label.className = 'wf-label';
        label.textContent = step.label;
        div.appendChild(label);

        const check = document.createElement('span');
        check.className = 'wf-check';
        check.textContent = '✓';
        div.appendChild(check);

        list.appendChild(div);
    });
}

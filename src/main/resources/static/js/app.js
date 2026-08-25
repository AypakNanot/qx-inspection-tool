/**
 * 应用入口模块
 * 初始化页面、注册全局事件、挂载onclick处理器
 */

import { loadDevices, loadGlobalConfig, saveGlobalConfig, syncDevices, openClearDataModal, closeClearDataModal, toggleClearConnNetworks, toggleNetworkSelect, executeClearData, connectAll, disconnectAll, connectSingle, disconnectSingle, closeDeviceModal, saveDeviceConfig, deleteDeviceConfig, searchDevices, filterByNetwork, filterByStatus, sortBy } from './device.js';
import { loadStatsOverview, loadStatsNetworks, loadStats, switchStatsChart, resizeChart } from './stats.js';
import { loadGlobalThreshold, saveGlobalThreshold, loadThresholds, openThresholdModal, closeThresholdModal, saveThreshold } from './threshold.js';
import { loadScheduleStatus, toggleSchedule, toggleSchedScope, toggleManualScope, loadTaskNetworks, loadTaskDevices, startManualInspection, onSchedPresetChange, saveScheduleConfig, loadCollectParams, saveCollectParams } from './task.js';
import { loadProgress, stopProgressPoll } from './progress.js';
import { loadQueryRounds, loadQueryFilters, loadQueryResults, exportExcel, searchQuery, sortQueryBy } from './query.js';

/** 页面标题映射 */
const TITLES = {
    'page-device': ['设备管理', '设备管理'],
    'page-stats': ['类型统计', '统计 / 类型统计'],
    'page-threshold': ['门限配置', '光功率巡检 / 门限配置'],
    'page-task': ['任务配置', '光功率巡检 / 任务配置'],
    'page-progress': ['任务进度', '光功率巡检 / 任务进度'],
    'page-query': ['数据查询', '光功率巡检 / 数据查询']
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
window.clearAllData = openClearDataModal;
window.closeClearDataModal = closeClearDataModal;
window.toggleClearConnNetworks = toggleClearConnNetworks;
window.toggleNetworkSelect = toggleNetworkSelect;
window.executeClearData = executeClearData;
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
window.loadStats = loadStats;
window.switchStatsChart = switchStatsChart;
window.saveGlobalThreshold = saveGlobalThreshold;
window.openThresholdModal = (levelType) => openThresholdModal(levelType);
window.closeThresholdModal = closeThresholdModal;
window.saveThreshold = saveThreshold;
window.toggleSchedule = (checked) => toggleSchedule(checked);
window.onSchedPresetChange = onSchedPresetChange;
window.saveScheduleConfig = saveScheduleConfig;
window.loadCollectParams = loadCollectParams;
window.saveCollectParams = saveCollectParams;
window.toggleSchedScope = toggleSchedScope;
window.toggleManualScope = toggleManualScope;
window.startManualInspection = () => startManualInspection(switchPage);
window.loadQueryResults = loadQueryResults;
window.exportExcel = exportExcel;
window.searchQuery = searchQuery;
window.sortQueryBy = sortQueryBy;

// 初始化加载
loadDevices();
loadGlobalConfig();
loadStatsOverview();
loadStatsNetworks();

window.addEventListener('resize', resizeChart);

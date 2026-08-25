/**
 * 设备管理模块
 * 处理设备列表展示、连接/断开操作、设备配置弹窗
 * 支持分页浏览（200/500/1000条每页）
 */

import { get, post, put, del } from './api.js';

/** 当前弹窗操作的设备ID */
let currentModalNeOid = null;

/** 分页状态 */
let allDevices = [];
let filteredDevices = [];
let currentPage = 1;
let pageSize = 200;
let sortField = '';
let sortOrder = 'asc';
let filterNetwork = '';
let filterStatus = '';
let searchText = '';

/** 创建文本单元格 */
function createTextCell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
}

/** 创建操作按钮 */
function createBtn(text, className, onclick) {
    const btn = document.createElement('button');
    btn.className = className;
    btn.textContent = text;
    btn.onclick = onclick;
    return btn;
}

/** 更新设备统计卡片 */
function updateDeviceStats(devices) {
    const online = devices.filter(d => d.connectionStatus === 1).length;
    document.getElementById('devTotal').textContent = devices.length;
    document.getElementById('devOnline').textContent = online;
    document.getElementById('devOffline').textContent = devices.length - online;
}

/** 渲染设备表格 */
function renderDeviceTable(devices) {
    const tbody = document.getElementById('deviceTable');
    tbody.textContent = '';
    if (!devices || devices.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 7;
        td.className = 'empty';
        td.textContent = '暂无设备，请先点击"同步设备"';
        tr.appendChild(td);
        tbody.appendChild(tr);
        renderPagination(0);
        return;
    }

    // 计算分页
    const totalPages = Math.ceil(devices.length / pageSize);
    if (currentPage > totalPages) currentPage = totalPages;
    const start = (currentPage - 1) * pageSize;
    const end = Math.min(start + pageSize, devices.length);
    const pageData = devices.slice(start, end);

    pageData.forEach(d => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(d.neName || '-'));
        tr.appendChild(createTextCell(d.ipAddr || '-'));
        tr.appendChild(createTextCell(d.networkName || '-'));
        tr.appendChild(createTextCell(d.neTypeName || '-'));

        const statusTd = document.createElement('td');
        const online = d.connectionStatus === 1;
        const sdkOnline = d.online === true;
        const isOnline = online || sdkOnline;
        const dot = document.createElement('span');
        dot.className = 'status-dot ' + (isOnline ? 'online' : 'offline');
        statusTd.appendChild(dot);
        statusTd.appendChild(document.createTextNode(isOnline ? '在线' : '离线'));
        tr.appendChild(statusTd);

        tr.appendChild(createTextCell(d.connectionStatus === 1 ? (d.username || '-') : '-'));

        const opTd = document.createElement('td');
        if (isOnline) {
            opTd.appendChild(createBtn('断开', 'btn btn-danger btn-sm', () => disconnectSingle(d.neId)));
        } else {
            opTd.appendChild(createBtn('连接', 'btn btn-success btn-sm', () => connectSingle(d.neId)));
        }
        opTd.appendChild(document.createTextNode(' '));
        opTd.appendChild(createBtn('配置', 'btn btn-outline btn-sm', () => openDeviceModal(d)));
        tr.appendChild(opTd);

        tbody.appendChild(tr);
    });

    renderPagination(devices.length);
    updateFilterStats();
}

/** 更新筛选统计 */
function updateFilterStats() {
    const stats = document.getElementById('deviceFilterStats');
    if (stats) {
        stats.textContent = `筛选结果：${filteredDevices.length} / ${allDevices.length} 台`;
    }
}

/** 应用筛选和排序 */
function applyFilterAndSort() {
    filteredDevices = allDevices.filter(d => {
        // 搜索文本筛选
        if (searchText) {
            const text = searchText.toLowerCase();
            const match = (d.neName || '').toLowerCase().includes(text) ||
                         (d.ipAddr || '').toLowerCase().includes(text) ||
                         (d.neId || '').toLowerCase().includes(text);
            if (!match) return false;
        }
        // 网络筛选
        if (filterNetwork && d.networkName !== filterNetwork) return false;
        // 状态筛选
        if (filterStatus) {
            const isOnline = d.connectionStatus === 1 || d.online === true;
            if (filterStatus === 'online' && !isOnline) return false;
            if (filterStatus === 'offline' && isOnline) return false;
        }
        return true;
    });

    // 排序
    if (sortField) {
        filteredDevices.sort((a, b) => {
            let va = a[sortField] || '';
            let vb = b[sortField] || '';
            if (typeof va === 'string') va = va.toLowerCase();
            if (typeof vb === 'string') vb = vb.toLowerCase();
            if (va < vb) return sortOrder === 'asc' ? -1 : 1;
            if (va > vb) return sortOrder === 'asc' ? 1 : -1;
            return 0;
        });
    }

    currentPage = 1;
    renderDeviceTable(filteredDevices);
}

/** 切换排序字段 */
export function sortBy(field) {
    if (sortField === field) {
        sortOrder = sortOrder === 'asc' ? 'desc' : 'asc';
    } else {
        sortField = field;
        sortOrder = 'asc';
    }
    applyFilterAndSort();
}

/** 搜索设备 */
export function searchDevices(text) {
    searchText = text.trim();
    applyFilterAndSort();
}

/** 按网络筛选 */
export function filterByNetwork(network) {
    filterNetwork = network;
    applyFilterAndSort();
}

/** 按状态筛选 */
export function filterByStatus(status) {
    filterStatus = status;
    applyFilterAndSort();
}

/** 渲染分页控件 */
function renderPagination(total) {
    const containers = [
        document.getElementById('devicePaginationTop'),
        document.getElementById('devicePagination')
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
            renderDeviceTable(allDevices);
        };
        container.appendChild(sizeSelect);

        // 页码信息
        const info = document.createElement('span');
        info.style.cssText = 'font-size:12px;color:#6b7280;line-height:28px;margin-right:12px;';
        info.textContent = `第 ${currentPage}/${totalPages} 页，共 ${total} 条`;
        container.appendChild(info);

        // 上一页
        const prevBtn = document.createElement('button');
        prevBtn.className = 'btn btn-outline btn-sm';
        prevBtn.textContent = '上一页';
        prevBtn.disabled = currentPage <= 1;
        prevBtn.onclick = () => { currentPage--; renderDeviceTable(allDevices); };
        container.appendChild(prevBtn);

        // 下一页
        const nextBtn = document.createElement('button');
        nextBtn.className = 'btn btn-outline btn-sm';
        nextBtn.textContent = '下一页';
        nextBtn.disabled = currentPage >= totalPages;
        nextBtn.onclick = () => { currentPage++; renderDeviceTable(allDevices); };
        container.appendChild(nextBtn);
    });
}

/** 加载设备列表 */
export async function loadDevices() {
    try {
        allDevices = await get('/connection/status');
        filteredDevices = allDevices;
        renderDeviceTable(filteredDevices);
        updateDeviceStats(allDevices);
        loadNetworkFilter();
    } catch (e) {
        console.error('loadDevices', e);
    }
}

/** 加载网络筛选下拉框 */
function loadNetworkFilter() {
    const networks = [...new Set(allDevices.map(d => d.networkName).filter(Boolean))];
    const select = document.getElementById('filterNetwork');
    if (!select) return;
    const current = select.value;
    while (select.firstChild) select.removeChild(select.firstChild);
    const defaultOpt = document.createElement('option');
    defaultOpt.value = '';
    defaultOpt.textContent = '全部网络';
    select.appendChild(defaultOpt);
    networks.sort().forEach(n => {
        const opt = document.createElement('option');
        opt.value = n;
        opt.textContent = n;
        select.appendChild(opt);
    });
    select.value = current;
}

/** 加载全局连接配置 */
export async function loadGlobalConfig() {
    try {
        const cfg = await get('/connection/config/global');
        document.getElementById('globalUser').value = cfg.username || '';
        document.getElementById('globalPass').value = cfg.password || '';
        document.getElementById('globalPort').value = cfg.port || 9900;
    } catch (e) { console.error('loadGlobalConfig', e); }
}

/** 保存全局连接配置 */
export async function saveGlobalConfig() {
    const body = {
        username: document.getElementById('globalUser').value,
        password: document.getElementById('globalPass').value,
        port: parseInt(document.getElementById('globalPort').value) || 9900
    };
    try {
        await put('/connection/config/global', body);
        const msg = document.getElementById('globalConfigMsg');
        msg.textContent = '保存成功';
        msg.style.display = 'block';
        setTimeout(() => { msg.style.display = 'none'; }, 2000);
    } catch (e) { alert('保存失败: ' + e.message); }
}

/** 同步设备列表（从MySQL导入） */
export async function syncDevices() {
    try {
        await post('/database/sync-devices');
        alert('设备同步完成');
        loadDevices();
    } catch (e) { alert('同步失败: ' + e.message); }
}

/** 打开清除数据弹窗 */
export async function openClearDataModal() {
    document.getElementById('clearInspectionRecords').checked = false;
    document.getElementById('clearInspectionRounds').checked = false;
    document.getElementById('clearDeviceConfigs').checked = false;
    document.getElementById('clearConnProfiles').checked = false;
    document.getElementById('clearThresholdRules').checked = false;
    document.getElementById('clearConnNetworks').style.display = 'none';
    document.querySelector('input[name="connScope"][value="all"]').checked = true;
    document.getElementById('clearNetworkSelect').style.display = 'none';

    // 加载网络列表
    try {
        const networks = await get('/database/networks');
        const select = document.getElementById('clearNetworkList');
        select.textContent = '';
        networks.forEach(n => {
            const opt = document.createElement('option');
            opt.value = n;
            opt.textContent = n;
            select.appendChild(opt);
        });
    } catch (e) { console.error('load networks', e); }

    document.getElementById('clearDataModal').classList.remove('hidden');
}

/** 关闭清除数据弹窗 */
export function closeClearDataModal() {
    document.getElementById('clearDataModal').classList.add('hidden');
}

/** 切换连接配置网络选择区域显示 */
export function toggleClearConnNetworks() {
    const checked = document.getElementById('clearConnProfiles').checked;
    document.getElementById('clearConnNetworks').style.display = checked ? 'block' : 'none';
}

/** 切换指定网络下拉框显示 */
export function toggleNetworkSelect() {
    const isNetwork = document.querySelector('input[name="connScope"]:checked').value === 'network';
    document.getElementById('clearNetworkSelect').style.display = isNetwork ? 'block' : 'none';
}

/** 执行清除数据 */
export async function executeClearData() {
    const options = {};
    let hasSelection = false;

    if (document.getElementById('clearInspectionRecords').checked) {
        options.inspectionRecords = true; hasSelection = true;
    }
    if (document.getElementById('clearInspectionRounds').checked) {
        options.inspectionRounds = true; hasSelection = true;
    }
    if (document.getElementById('clearDeviceConfigs').checked) {
        options.deviceConfigs = true; hasSelection = true;
    }
    if (document.getElementById('clearThresholdRules').checked) {
        options.thresholdRules = true; hasSelection = true;
    }
    if (document.getElementById('clearConnProfiles').checked) {
        hasSelection = true;
        const scope = document.querySelector('input[name="connScope"]:checked').value;
        if (scope === 'all') {
            options.connectionProfiles = 'all';
        } else {
            const select = document.getElementById('clearNetworkList');
            const selected = Array.from(select.selectedOptions).map(o => o.value);
            if (selected.length === 0) {
                alert('请至少选择一个网络');
                return;
            }
            options.connectionProfiles = selected;
        }
    }

    if (!hasSelection) {
        alert('请至少选择一项数据');
        return;
    }

    const labels = [];
    if (options.inspectionRecords) labels.push('巡检记录');
    if (options.inspectionRounds) labels.push('巡检轮次');
    if (options.deviceConfigs) labels.push('设备配置');
    if (options.thresholdRules) labels.push('门限规则');
    if (options.connectionProfiles) {
        labels.push('连接配置' + (options.connectionProfiles === 'all' ? '(全部)' : '(' + options.connectionProfiles.join(',') + ')'));
    }

    if (!confirm('确认清除以下数据？\n\n' + labels.join('\n') + '\n\n此操作不可恢复！')) return;

    try {
        const d = await post('/database/clear-selected', options);
        closeClearDataModal();
        const lines = Object.entries(d.deletedCounts).map(([k, v]) => k + ': ' + v + '条');
        alert('清除完成：\n' + (lines.length > 0 ? lines.join('\n') : '无数据被删除'));
        loadDevices();
    } catch (e) { alert('清除失败: ' + e.message); }
}

/** 一键连接所有设备 */
export async function connectAll(btn) {
    btn.disabled = true;
    btn.textContent = '连接中...';
    try {
        const d = await post('/connection/connect-all');
        alert('连接完成: 成功 ' + d.success + ' 台, 失败 ' + d.fail + ' 台');
        loadDevices();
    } catch (e) { alert('连接失败: ' + e.message); }
    btn.disabled = false;
    btn.textContent = '一键连接';
}

/** 一键断开所有设备 */
export async function disconnectAll() {
    if (!confirm('确认断开所有设备连接？')) return;
    try {
        const d = await post('/connection/disconnect-all');
        alert('已断开 ' + d.disconnected + ' 台设备');
        loadDevices();
    } catch (e) { alert('断开失败: ' + e.message); }
}

/** 连接单台设备 */
export async function connectSingle(neOid) {
    try {
        await post('/connection/connect/' + encodeURIComponent(neOid));
        loadDevices();
    } catch (e) { console.error('connectSingle', e); }
}

/** 断开单台设备 */
export async function disconnectSingle(neOid) {
    try {
        await post('/connection/disconnect/' + encodeURIComponent(neOid));
        loadDevices();
    } catch (e) { console.error('disconnectSingle', e); }
}

/** 打开设备配置弹窗 */
function openDeviceModal(device) {
    currentModalNeOid = device.neId;
    document.getElementById('modalDeviceInfo').textContent = device.neName + ' (' + device.ipAddr + ')';
    document.getElementById('modalTitle').textContent = '设备连接配置';
    document.getElementById('modalUser').value = '';
    document.getElementById('modalPass').value = '';
    document.getElementById('modalPort').value = '9900';
    document.getElementById('modalDeleteBtn').style.display = '';
    document.getElementById('deviceModal').classList.remove('hidden');

    get('/connection/config/' + encodeURIComponent(currentModalNeOid))
        .then(cfg => {
            if (cfg) {
                document.getElementById('modalUser').value = cfg.username || '';
                document.getElementById('modalPass').value = cfg.password || '';
                document.getElementById('modalPort').value = cfg.port || 9900;
            }
        }).catch(() => {});
}

/** 关闭设备配置弹窗 */
export function closeDeviceModal() {
    document.getElementById('deviceModal').classList.add('hidden');
    currentModalNeOid = null;
}

/** 保存单设备连接配置 */
export async function saveDeviceConfig() {
    if (!currentModalNeOid) return;
    const body = {
        username: document.getElementById('modalUser').value,
        password: document.getElementById('modalPass').value,
        port: parseInt(document.getElementById('modalPort').value) || 9900
    };
    try {
        await put('/connection/config/' + encodeURIComponent(currentModalNeOid), body);
        closeDeviceModal();
        loadDevices();
    } catch (e) { alert('保存失败: ' + e.message); }
}

/** 删除单设备配置（恢复使用全局配置） */
export async function deleteDeviceConfig() {
    if (!currentModalNeOid) return;
    if (!confirm('确认清除该设备的独立配置，恢复使用全局配置？')) return;
    try {
        await del('/connection/config/' + encodeURIComponent(currentModalNeOid));
        closeDeviceModal();
        loadDevices();
    } catch (e) { alert('删除失败: ' + e.message); }
}

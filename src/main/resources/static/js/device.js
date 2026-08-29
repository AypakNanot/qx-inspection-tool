/**
 * 设备管理模块
 * 处理设备列表展示、连接/断开操作、设备配置弹窗
 * 支持分页浏览（200/500/1000条每页）
 */

import { get, post, put, del } from './api.js';
import { showToast, withLoading } from './toast.js';

/** 当前弹窗操作的设备ID */
let currentModalNeOid = null;

/** 设备状态自动刷新 */
let deviceRefreshTimer = null;

export function startDeviceRefresh() {
    if (deviceRefreshTimer) return;
    deviceRefreshTimer = setInterval(() => {
        loadDevices();
    }, 10000);
}

export function stopDeviceRefresh() {
    if (deviceRefreshTimer) {
        clearInterval(deviceRefreshTimer);
        deviceRefreshTimer = null;
    }
}

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

/** 创建操作按钮（onclick 接收 btn 元素作为第一个参数） */
function createBtn(text, className, onclick) {
    const btn = document.createElement('button');
    btn.className = className;
    btn.textContent = text;
    btn.onclick = () => onclick(btn);
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
        td.textContent = '暂无设备，请点击上方「同步设备」从数据库导入网元列表';
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
            opTd.appendChild(createBtn('断开', 'btn btn-danger btn-sm', (btn) => withLoading(btn, () => disconnectSingle(d.neId, d.neName))));
        } else {
            opTd.appendChild(createBtn('连接', 'btn btn-success btn-sm', (btn) => withLoading(btn, () => connectSingle(d.neId, d.neName))));
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
async function loadNetworkFilter() {
    // 优先从同步表获取网络列表（即使未同步设备也能选择）
    let networks = [];
    try {
        networks = await get('/database/networks');
    } catch (e) {
        // 接口不存在或失败，回退到从设备列表提取
        networks = [...new Set(allDevices.map(d => d.networkName).filter(Boolean))];
    }
    // 筛选下拉框
    const select = document.getElementById('filterNetwork');
    if (select) {
        const current = select.value;
        select.textContent = '';
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
    // 连接/断开/同步网络选择
    const connSel = document.getElementById('connNetwork');
    if (connSel) {
        const current = connSel.value;
        connSel.textContent = '';
        const defaultOpt = document.createElement('option');
        defaultOpt.value = '';
        defaultOpt.textContent = '全网';
        connSel.appendChild(defaultOpt);
        networks.sort().forEach(n => {
            const opt = document.createElement('option');
            opt.value = n;
            opt.textContent = n;
            connSel.appendChild(opt);
        });
        connSel.value = current;
    }
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
        showToast('全局配置保存成功', 'success');
        const msg = document.getElementById('globalConfigMsg');
        msg.textContent = '保存成功';
        msg.style.display = 'block';
        setTimeout(() => { msg.style.display = 'none'; }, 2000);
    } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
}

/** 同步设备列表（从MySQL导入） */
export async function syncDevices() {
    try {
        var network = document.getElementById('connNetwork').value || '';
        var body = network ? { network: network } : {};
        var result = await post('/database/sync-devices', body);
        if (result.status === 'SUCCESS') {
            var msg = network ? '设备同步完成（' + network + '）' : '设备同步完成';
            showToast(msg, 'success');
            loadDevices();
        } else {
            showToast(result.message || '同步失败', 'error');
        }
    } catch (e) { showToast('同步失败: ' + e.message, 'error'); }
}

/** 独立清除指定类型的数据 */
export async function clearDataType(type, label) {
    if (!confirm('确认清除所有' + label + '？\n\n此操作不可恢复！')) return;
    try {
        const options = {};
        options[type] = true;
        const d = await post('/database/clear-selected', options);
        const counts = d.deletedCounts || {};
        const lines = Object.entries(counts).map(([k, v]) => k + ': ' + v + '条');
        showToast(label + '清除完成：' + (lines.length > 0 ? lines.join(', ') : '无数据被删除'), 'success');
    } catch (e) { showToast('清除失败: ' + e.message, 'error'); }
}

/** 清除连接配置（支持网络范围选择） */
export async function clearConnProfiles() {
    let networks = [];
    try {
        networks = await get('/database/networks');
    } catch (e) { console.error('load networks', e); }

    const scope = confirm('清除全部连接配置？\n\n确定 = 清除全部\n取消 = 选择指定网络') ? 'all' : 'network';

    let options = {};
    if (scope === 'all') {
        options.connectionProfiles = 'all';
    } else {
        if (networks.length === 0) {
            showToast('没有可用的网络', 'info');
            return;
        }
        const msg = '可选网络：\n' + networks.map((n, i) => (i + 1) + '. ' + n).join('\n') + '\n\n输入网络编号（多个用逗号分隔）：';
        const input = prompt(msg);
        if (!input) return;
        const indices = input.split(',').map(s => parseInt(s.trim()) - 1).filter(i => i >= 0 && i < networks.length);
        if (indices.length === 0) {
            showToast('未选择有效网络', 'error');
            return;
        }
        options.connectionProfiles = indices.map(i => networks[i]);
    }

    const scopeLabel = scope === 'all' ? '全部' : options.connectionProfiles.join(',');
    if (!confirm('确认清除连接配置（' + scopeLabel + '）？\n\n此操作不可恢复！')) return;

    try {
        const d = await post('/database/clear-selected', options);
        const counts = d.deletedCounts || {};
        const lines = Object.entries(counts).map(([k, v]) => k + ': ' + v + '条');
        showToast('连接配置清除完成：' + (lines.length > 0 ? lines.join(', ') : '无数据被删除'), 'success');
    } catch (e) { showToast('清除失败: ' + e.message, 'error'); }
}

/** 全选/取消全选清除复选框 */
export function toggleAllClearCb() {
    const cbs = document.querySelectorAll('.clear-cb');
    const allChecked = Array.from(cbs).every(cb => cb.checked);
    cbs.forEach(cb => { cb.checked = !allChecked; });
}

/** 清除选中的数据 */
export async function clearSelectedData() {
    const checked = document.querySelectorAll('.clear-cb:checked');
    if (checked.length === 0) {
        showToast('请至少选择一项', 'error');
        return;
    }
    const types = Array.from(checked).map(cb => cb.value);
    const labels = { syncData: '同步数据', deviceConfigs: '设备配置', connectionProfiles: '连接配置', thresholdRules: '门限规则', inspectionRecords: '巡检记录', inspectionRounds: '巡检轮次' };
    const names = types.map(t => labels[t] || t).join('、');
    if (!confirm('确认清除所选数据（' + names + '）？\n\n此操作不可恢复！')) return;

    try {
        // 同步数据走单独接口
        if (types.includes('syncData')) {
            const syncResult = await post('/sync/clear');
            const syncCounts = syncResult.deletedCounts || {};
            const syncLines = Object.entries(syncCounts).map(([k, v]) => k + ': ' + v + '条');
            showToast('同步数据清除完成：' + (syncLines.length > 0 ? syncLines.join(', ') : '无数据被删除'), 'success');
        }
        // 其他数据走统一接口
        const otherTypes = types.filter(t => t !== 'syncData');
        if (otherTypes.length > 0) {
            const options = {};
            otherTypes.forEach(t => { options[t] = true; });
            const d = await post('/database/clear-selected', options);
            const counts = d.deletedCounts || {};
            const lines = Object.entries(counts).map(([k, v]) => k + ': ' + v + '条');
            showToast('清除完成：' + (lines.length > 0 ? lines.join(', ') : '无数据被删除'), 'success');
        }
        document.querySelectorAll('.clear-cb').forEach(cb => { cb.checked = false; });
    } catch (e) { showToast('清除失败: ' + e.message, 'error'); }
}

/** 一键连接所有设备 */
export function connectAll(btn) {
    withLoading(btn, async () => {
        const network = document.getElementById('connNetwork').value;
        const params = network ? '?network=' + encodeURIComponent(network) : '';
        const d = await post('/connection/connect-all' + params);
        const scope = network || '全网';
        let msg = '[' + scope + '] 连接完成: 共 ' + d.total + ' 台，成功 ' + d.success + ' 台';
        if (d.fail > 0) {
            msg += '，失败 ' + d.fail + ' 台';
        }
        showToast(msg, d.fail > 0 ? 'warning' : 'success');
        loadDevices();
    });
}

/** 一键断开所有设备 */
export async function disconnectAll() {
    const network = document.getElementById('connNetwork').value;
    const scope = network || '全网';
    if (!confirm('确认断开' + scope + '的所有设备连接？')) return;
    try {
        const params = network ? '?network=' + encodeURIComponent(network) : '';
        const d = await post('/connection/disconnect-all' + params);
        showToast('[' + scope + '] 已断开 ' + d.disconnected + ' 台设备', 'success');
        loadDevices();
    } catch (e) { showToast('断开失败: ' + e.message, 'error'); }
}

/** 连接单台设备 */
export async function connectSingle(neOid, neName) {
    try {
        const d = await post('/connection/connect/' + encodeURIComponent(neOid));
        if (d.success) {
            showToast((neName || neOid) + ' 连接成功', 'success');
        } else {
            showToast((neName || neOid) + ' ' + (d.message || '连接失败'), 'error');
        }
        loadDevices();
    } catch (e) { showToast('连接失败: ' + e.message, 'error'); }
}

/** 断开单台设备 */
export async function disconnectSingle(neOid, neName) {
    try {
        const d = await post('/connection/disconnect/' + encodeURIComponent(neOid));
        if (d.success) {
            showToast((neName || neOid) + ' 已断开', 'success');
        } else {
            showToast((neName || neOid) + ' ' + (d.message || '断开失败'), 'error');
        }
        loadDevices();
    } catch (e) { showToast('断开失败: ' + e.message, 'error'); }
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
    } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
}

/** 删除单设备配置（恢复使用全局配置） */
export async function deleteDeviceConfig() {
    if (!currentModalNeOid) return;
    if (!confirm('确认清除该设备的独立配置，恢复使用全局配置？')) return;
    try {
        await del('/connection/config/' + encodeURIComponent(currentModalNeOid));
        closeDeviceModal();
        loadDevices();
    } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
}

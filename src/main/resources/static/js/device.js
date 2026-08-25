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
let currentPage = 1;
let pageSize = 200;

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
}

/** 渲染分页控件 */
function renderPagination(total) {
    const container = document.getElementById('devicePagination');
    if (!container) return;
    container.textContent = '';

    const totalPages = Math.ceil(total / pageSize);
    if (totalPages <= 1) {
        container.style.display = 'none';
        return;
    }
    container.style.display = 'flex';

    // 每页条数选择
    const sizeSelect = document.createElement('select');
    sizeSelect.style.cssText = 'padding:4px 8px;border:1px solid #d1d5db;border-radius:4px;font-size:12px;margin-right:12px;';
    [200, 500, 1000].forEach(size => {
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
}

/** 加载设备列表 */
export async function loadDevices() {
    try {
        allDevices = await get('/connection/status');
        renderDeviceTable(allDevices);
        updateDeviceStats(allDevices);
    } catch (e) {
        console.error('loadDevices', e);
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

/**
 * MySQL → SQLite 按网络同步模块
 */

import { get, post, put, API } from './api.js';
import { showToast, withLoading } from './toast.js';

/** 加载 MySQL 配置到表单 */
export async function loadMysqlConfig() {
    try {
        const cfg = await get('/sync/mysql-config');
        document.getElementById('mysqlHost').value = cfg.host || '';
        document.getElementById('mysqlUsername').value = cfg.username || '';
        if (cfg.password) {
            document.getElementById('mysqlPassword').placeholder = '已配置（留空保持不变）';
        }
    } catch (e) {
        console.error('loadMysqlConfig', e);
    }
}

/** 保存 MySQL 配置 */
export function saveMysqlConfig() {
    var btn = event.target;
    withLoading(btn, async function() {
        var body = {
            host: document.getElementById('mysqlHost').value.trim(),
            username: document.getElementById('mysqlUsername').value.trim(),
            password: document.getElementById('mysqlPassword').value
        };
        var result = await put('/sync/mysql-config', body);
        if (result.status === 'SUCCESS') {
            showToast('MySQL 配置已保存', 'success');
            document.getElementById('mysqlPassword').value = '';
            document.getElementById('mysqlPassword').placeholder = '已配置（留空保持不变）';
        } else {
            showToast(result.message || '保存失败', 'error');
        }
    });
}

/** 测试 MySQL 连接 */
export function testMysqlConnection() {
    var btn = event.target;
    var resultEl = document.getElementById('mysqlTestResult');
    withLoading(btn, async function() {
        var result = await post('/sync/mysql-test');
        if (result.status === 'SUCCESS') {
            resultEl.textContent = result.message;
            resultEl.style.color = '#16a34a';
            showToast(result.message, 'success');
        } else {
            resultEl.textContent = result.message;
            resultEl.style.color = '#dc2626';
            showToast(result.message, 'error');
        }
    });
}

/** 加载可同步的网络列表 */
export async function loadNetworkList() {
    const container = document.getElementById('syncNetworkList');
    try {
        container.textContent = '加载中...';
        const networks = await get('/sync/networks');
        container.textContent = '';

        if (!networks || networks.length === 0) {
            container.textContent = '未发现网络数据，请先检查 MySQL 连接';
            return;
        }

        networks.forEach(function(net) {
            const label = document.createElement('label');
            label.style.cssText = 'display:flex;align-items:center;gap:6px;padding:3px 0;cursor:pointer;font-size:13px;';
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.className = 'sync-network-cb';
            cb.value = net.oid;
            cb.dataset.name = net.name || net.oid;
            label.appendChild(cb);
            label.appendChild(document.createTextNode(net.name || net.oid));
            container.appendChild(label);
        });
    } catch (e) {
        container.textContent = '加载失败: ' + e.message;
    }
}

/** 全选/取消网络 */
export function toggleAllNetworks() {
    const cbs = document.querySelectorAll('.sync-network-cb');
    const allChecked = Array.from(cbs).every(function(cb) { return cb.checked; });
    cbs.forEach(function(cb) { cb.checked = !allChecked; });
}

/** 执行按网络同步 */
export function executeSync() {
    const btn = event.target;
    const checked = document.querySelectorAll('.sync-network-cb:checked');
    const networkOids = Array.from(checked).map(function(cb) { return cb.value; });

    if (networkOids.length === 0) {
        showToast('请至少选择一个网络', 'error');
        return;
    }

    const networkNames = Array.from(checked).map(function(cb) { return cb.dataset.name; });

    withLoading(btn, async function() {
        const result = await post('/sync/execute', networkOids);
        const el = document.getElementById('syncResult');
        el.style.display = 'block';

        if (result.status === 'SUCCESS') {
            el.textContent = '同步完成: ' + result.networks + ' (网元: ' + result.neCount + '台, 链路: ' + result.linkCount + '条, 端口: ' + result.portCount + '个, 耗时: ' + result.elapsed + ')';
            el.style.color = '#16a34a';
            showToast('同步完成', 'success');
        } else {
            el.textContent = '同步失败: ' + (result.error || '未知错误');
            el.style.color = '#dc2626';
            showToast('同步失败', 'error');
        }
        loadSyncStatus();
    });
}

/** 加载同步状态 */
export async function loadSyncStatus() {
    try {
        const status = await get('/sync/status');
        const el = document.getElementById('syncStatus');
        el.textContent = '';

        if (status.syncStatus === 'SUCCESS') {
            addStatusLine(el, '已同步网络: ' + (status.networkNames || '-'), '#16a34a');
            addStatusLine(el, '网元: ' + (status.neCount || 0) + ' 台');
            addStatusLine(el, '链路: ' + (status.linkCount || 0) + ' 条');
            addStatusLine(el, '端口: ' + (status.portCount || 0) + ' 个');
            addStatusLine(el, '同步时间: ' + (status.syncTime || '-'));
        } else if (status.syncStatus === 'RUNNING') {
            addStatusLine(el, '同步进行中...', '#f59e0b');
        } else if (status.syncStatus === 'FAILED') {
            addStatusLine(el, '上次同步失败', '#dc2626');
        } else {
            addStatusLine(el, '尚未同步数据，请选择网络后执行同步', '#6b7280');
        }
    } catch (e) {
        document.getElementById('syncStatus').textContent = '加载失败: ' + e.message;
    }
}

function addStatusLine(el, text, color) {
    const div = document.createElement('div');
    div.textContent = text;
    if (color) div.style.color = color;
    el.appendChild(div);
}

/** 清除同步数据 */
export function clearSyncData() {
    const btn = event.target;
    withLoading(btn, async function() {
        if (!confirm('确认清除所有同步到 SQLite 的数据？\n\n此操作不可恢复！')) return;
        const result = await post('/sync/clear');
        showToast('同步数据已清除', 'success');
        loadSyncStatus();
    });
}

/** 加载操作审计日志 */
export async function loadAuditLogs() {
    try {
        const logs = await get('/inspection/audit/logs');
        const tbody = document.getElementById('auditLogTable');
        tbody.textContent = '';
        if (!logs || logs.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 5; td.className = 'empty'; td.textContent = '暂无日志';
            tr.appendChild(td); tbody.appendChild(tr);
            return;
        }
        logs.forEach(log => {
            const tr = document.createElement('tr');
            const timeTd = document.createElement('td');
            timeTd.textContent = log.opTime ? log.opTime.replace('T', ' ').replace(/\.\d+$/, '') : '-';
            tr.appendChild(timeTd);
            const typeTd = document.createElement('td');
            const badge = document.createElement('span');
            badge.className = 'badge';
            const typeMap = { CONNECT: 'badge-online', DISCONNECT: 'badge-offline', INSPECTION: 'badge-online', CONFIG: '', THRESHOLD: '', SYNC: 'badge-online' };
            badge.className = 'badge ' + (typeMap[log.opType] || '');
            badge.textContent = log.opType;
            typeTd.appendChild(badge);
            tr.appendChild(typeTd);
            const targetTd = document.createElement('td');
            targetTd.textContent = log.target || '-';
            tr.appendChild(targetTd);
            const resultTd = document.createElement('td');
            resultTd.textContent = log.result || '-';
            if (log.result === 'FAIL') resultTd.style.color = '#dc2626';
            tr.appendChild(resultTd);
            const remarkTd = document.createElement('td');
            remarkTd.textContent = log.remark || '-';
            tr.appendChild(remarkTd);
            tbody.appendChild(tr);
        });
    } catch (e) { console.error('loadAuditLogs', e); }
}

/** 备份数据库 */
export async function backupDatabase() {
    try {
        const res = await fetch(API + '/inspection/backup', {
            headers: { 'X-Admin-Token': 'qx-inspection-admin' }
        });
        if (!res.ok) {
            const text = await res.text();
            showToast('备份失败: ' + text, 'error');
            return;
        }
        const blob = await res.blob();
        const disposition = res.headers.get('Content-Disposition') || '';
        const match = disposition.match(/filename\*=UTF-8''(.+)/);
        const filename = match ? decodeURIComponent(match[1]) : 'backup.db';
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        showToast('数据库备份已下载', 'success');
    } catch (e) {
        showToast('备份请求失败: ' + e.message, 'error');
    }
}

/** 恢复数据库 */
export async function restoreDatabase() {
    const fileInput = document.getElementById('restoreFile');
    const file = fileInput.files[0];
    if (!file) return;
    if (!confirm('确认从备份文件恢复数据库？\n\n恢复后当前数据库将被覆盖，建议先执行备份。')) {
        fileInput.value = '';
        return;
    }
    const formData = new FormData();
    formData.append('file', file);
    try {
        const res = await fetch(API + '/inspection/restore', {
            method: 'POST',
            headers: { 'X-Admin-Token': 'qx-inspection-admin' },
            body: formData
        }).then(r => r.json());
        const el = document.getElementById('restoreResult');
        el.style.display = '';
        el.textContent = res.message || '操作完成';
        el.style.color = res.success ? '#16a34a' : '#dc2626';
        if (res.success) showToast('数据库恢复成功', 'success');
        else showToast('恢复失败: ' + res.message, 'error');
    } catch (e) {
        showToast('恢复请求失败: ' + e.message, 'error');
    }
    fileInput.value = '';
}
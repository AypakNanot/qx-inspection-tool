/**
 * MySQL → SQLite 动态同步模块
 */

import { get, post, put } from './api.js';
import { showToast, withLoading } from './toast.js';

/** 加载 MySQL 配置到表单 */
export async function loadMysqlConfig() {
    try {
        const cfg = await get('/sync/mysql-config');
        document.getElementById('mysqlHost').value = cfg.host || '';
        document.getElementById('mysqlUsername').value = cfg.username || '';
        // 密码不回显，只标记是否已配置
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

/** 测试 MySQL 连接（测试已保存的配置） */
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

/** 加载同步状态 */
export async function loadSyncStatus() {
    try {
        const status = await get('/sync/status');
        const el = document.getElementById('syncStatus');
        const total = status.totalTables || 0;
        const synced = status.syncedCount || 0;
        const notSynced = status.notSyncedCount || 0;
        const essential = status.essentialTables || [];

        el.textContent = '';
        if (status.error) {
            addStatusLine(el, status.error, '#dc2626');
        } else {
            addStatusLine(el, 'MySQL 总表数：' + total);
            addStatusLine(el, '已同步：' + synced + '  ', '#16a34a');
            addStatusLine(el, '未同步：' + notSynced + '  ', notSynced > 0 ? '#dc2626' : '#16a34a');
        }
        addStatusLine(el, '必要表：' + essential.length + ' 张 (' + essential.join(', ') + ')');
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

/** 同步必要表 */
export function syncEssential() {
    const btn = event.target;
    withLoading(btn, async function() {
        const result = await post('/sync/essential');
        showSyncResult(result);
        loadSyncStatus();
    });
}

/** 同步全部表 */
export function syncAll() {
    const btn = event.target;
    withLoading(btn, async function() {
        const result = await post('/sync/all');
        showSyncResult(result);
        loadSyncStatus();
    });
}

/** 同步选中表 */
export function syncSelectedTables() {
    const checked = document.querySelectorAll('.sync-table-cb:checked');
    const tables = Array.from(checked).map(function(cb) { return cb.value; });
    if (tables.length === 0) {
        showToast('请至少选择一张表', 'error');
        return;
    }
    withLoading(event.target, async function() {
        const result = await post('/sync/tables', tables);
        showSyncResult(result);
        loadSyncStatus();
    });
}

function showSyncResult(result) {
    const el = document.getElementById('syncResult');
    el.style.display = 'block';
    el.textContent = result._summary || JSON.stringify(result);
    showToast(result._summary || '同步完成', 'success');
}

/** 清除所有同步数据 */
export function clearSyncData() {
    const btn = event.target;
    withLoading(btn, async function() {
        if (!confirm('确认清除所有同步到 SQLite 的数据？\n\n此操作不可恢复！')) return;
        const result = await post('/sync/clear');
        const counts = result.deletedCounts || {};
        const lines = Object.entries(counts).map(function(entry) { return entry[0] + ': ' + entry[1] + '条'; });
        showToast('同步数据清除完成：' + (lines.length > 0 ? lines.join(', ') : '无数据被删除'), 'success');
        loadSyncStatus();
    });
}

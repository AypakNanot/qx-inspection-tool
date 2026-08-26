/**
 * MySQL → SQLite 动态同步模块
 */

import { get, post } from './api.js';
import { showToast, withLoading } from './toast.js';

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
        addStatusLine(el, 'MySQL 总表数：' + total);
        addStatusLine(el, '已同步：' + synced + '  ', '#16a34a');
        addStatusLine(el, '未同步：' + notSynced + '  ', notSynced > 0 ? '#dc2626' : '#16a34a');
        addStatusLine(el, '必要表：' + essential.length + ' 张 (' + essential.join(', ') + ')');

        // 渲染表选择列表
        renderTableList(status);
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

function renderTableList(status) {
    const container = document.getElementById('syncTableList');
    const selectArea = document.getElementById('syncTablesSelect');
    container.textContent = '';

    const allTables = [...(status.synced || []).map(function(s) { return s.split(' (')[0]; }),
                       ...(status.notSynced || [])];
    if (allTables.length === 0) {
        selectArea.style.display = 'none';
        return;
    }

    selectArea.style.display = 'block';
    allTables.forEach(function(table) {
        const label = document.createElement('label');
        label.style.cssText = 'display:flex;align-items:center;gap:6px;padding:3px 0;cursor:pointer;';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = table;
        cb.className = 'sync-table-cb';
        const txt = document.createElement('span');
        const isSynced = (status.synced || []).some(function(s) { return s.startsWith(table + ' ('); });
        txt.textContent = table;
        if (isSynced) txt.style.color = '#16a34a';
        label.appendChild(cb);
        label.appendChild(txt);
        container.appendChild(label);
    });
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

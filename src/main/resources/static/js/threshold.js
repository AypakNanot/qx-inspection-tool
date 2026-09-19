/**
 * 门限配置模块
 * 每个模块类型预置标准默认门限值，用户只能修改，不能添加或删除
 */

import { get, post } from './api.js';
import { showToast } from './toast.js';

/** 预置门限值缓存（matchKey → {txLow, txHigh, rxLow, rxHigh}） */
let presetMap = {};

/** 加载所有门限规则 */
export async function loadThresholds() {
    try {
        const rules = await get('/inspection/thresholds');
        renderThresholdTable(rules);
    } catch (e) { console.error('loadThresholds', e); }
}

/** 渲染门限规则表格 */
function renderThresholdTable(rules) {
    const tbody = document.getElementById('moduleThresholdTable');
    tbody.textContent = '';

    if (rules.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 7; td.className = 'empty'; td.textContent = '暂无门限规则';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }

    rules.forEach(r => {
        // 缓存预置值
        if (r.presetTxLow != null) {
            presetMap[r.matchKey] = {
                txLow: r.presetTxLow, txHigh: r.presetTxHigh,
                rxLow: r.presetRxLow, rxHigh: r.presetRxHigh
            };
        }
        tbody.appendChild(buildThresholdRow(r));
    });
}

/** 构建单条门限规则行 */
function buildThresholdRow(r) {
    const tr = document.createElement('tr');
    tr.appendChild(createTextCell(r.matchKey || '-'));
    tr.appendChild(createTextCell(r.description || '-'));
    tr.appendChild(createTextCell(r.txLow != null ? r.txLow : '-'));
    tr.appendChild(createTextCell(r.txHigh != null ? r.txHigh : '-'));
    tr.appendChild(createTextCell(r.rxLow != null ? r.rxLow : '-'));
    tr.appendChild(createTextCell(r.rxHigh != null ? r.rxHigh : '-'));

    const opTd = document.createElement('td');
    opTd.appendChild(createBtn('修改', 'btn btn-outline btn-sm', () => openThresholdModal(r)));
    tr.appendChild(opTd);
    return tr;
}

function createTextCell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
}

function createBtn(text, className, onclick) {
    const btn = document.createElement('button');
    btn.className = className;
    btn.textContent = text;
    btn.onclick = onclick;
    return btn;
}

/** 打开编辑门限弹窗 */
export function openThresholdModal(rule) {
    document.getElementById('thId').value = rule.id || '';
    document.getElementById('thMatchKey').value = rule.matchKey || '';
    document.getElementById('thMatchKeyDisplay').textContent = rule.matchKey || '';
    document.getElementById('thDesc').value = rule.description || '';
    document.getElementById('thTxLow').value = rule.txLow != null ? rule.txLow : '';
    document.getElementById('thTxHigh').value = rule.txHigh != null ? rule.txHigh : '';
    document.getElementById('thRxLow').value = rule.rxLow != null ? rule.rxLow : '';
    document.getElementById('thRxHigh').value = rule.rxHigh != null ? rule.rxHigh : '';

    // 显示预置标准值
    const preset = presetMap[rule.matchKey];
    if (preset) {
        document.getElementById('thPresetTxLow').value = preset.txLow;
        document.getElementById('thPresetTxHigh').value = preset.txHigh;
        document.getElementById('thPresetRxLow').value = preset.rxLow;
        document.getElementById('thPresetRxHigh').value = preset.rxHigh;
        document.getElementById('thPresetTxLowHint').textContent = '标准: ' + preset.txLow;
        document.getElementById('thPresetTxHighHint').textContent = '标准: ' + preset.txHigh;
        document.getElementById('thPresetRxLowHint').textContent = '标准: ' + preset.rxLow;
        document.getElementById('thPresetRxHighHint').textContent = '标准: ' + preset.rxHigh;
    } else {
        ['thPresetTxLowHint', 'thPresetTxHighHint', 'thPresetRxLowHint', 'thPresetRxHighHint'].forEach(id => {
            document.getElementById(id).textContent = '';
        });
    }

    document.getElementById('thresholdModal').classList.remove('hidden');
}

/** 关闭门限弹窗 */
export function closeThresholdModal() {
    document.getElementById('thresholdModal').classList.add('hidden');
}

/** 恢复默认值（前端操作，不请求后端） */
export function restoreThresholdDefaults() {
    const matchKey = document.getElementById('thMatchKey').value;
    const preset = presetMap[matchKey];
    if (!preset) {
        showToast('该模块类型无预置默认值', 'error');
        return;
    }
    document.getElementById('thTxLow').value = preset.txLow;
    document.getElementById('thTxHigh').value = preset.txHigh;
    document.getElementById('thRxLow').value = preset.rxLow;
    document.getElementById('thRxHigh').value = preset.rxHigh;
    showToast('已恢复为标准默认值，请确认后保存', 'success');
}

/** 保存门限规则（只允许修改） */
export async function saveThreshold() {
    const matchKey = document.getElementById('thMatchKey').value;
    const body = {
        matchKey: matchKey,
        rxLow: parseFloat(document.getElementById('thRxLow').value),
        rxHigh: parseFloat(document.getElementById('thRxHigh').value),
        txLow: parseFloat(document.getElementById('thTxLow').value),
        txHigh: parseFloat(document.getElementById('thTxHigh').value),
        description: document.getElementById('thDesc').value.trim()
    };
    try {
        await post('/inspection/thresholds', body);
        closeThresholdModal();
        loadThresholds();
        showToast('门限已更新', 'success');
    } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
}

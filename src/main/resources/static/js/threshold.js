/**
 * 门限配置模块
 * 管理全局/模块类型/型号三级光功率门限规则
 */

import { get, post, del } from './api.js';
import { showToast } from './toast.js';

/** 模块类型列表（与老网管 LaserInfoTableRowDecoder.getDistance() 一致） */
const MODULE_TYPES = [
    // 2.5G (STM-16)
    { key: 'I16.1',  speed: '2.5G',  wave: '1310nm', distance: 'I (中距)' },
    { key: 'S16.1',  speed: '2.5G',  wave: '1310nm', distance: 'S (短距)' },
    { key: 'L16.1',  speed: '2.5G',  wave: '1310nm', distance: 'L (长距)' },
    { key: 'V16.1',  speed: '2.5G',  wave: '1550nm', distance: 'V (超长距)' },
    // 622M (STM-4)
    { key: 'I4.1',   speed: '622M',  wave: '1310nm', distance: 'I (中距)' },
    { key: 'S4.1',   speed: '622M',  wave: '1310nm', distance: 'S (短距)' },
    { key: 'L4.1',   speed: '622M',  wave: '1310nm', distance: 'L (长距)' },
    { key: 'V4.1',   speed: '622M',  wave: '1550nm', distance: 'V (超长距)' },
    // 155M (STM-1)
    { key: 'I1.1',   speed: '155M',  wave: '1310nm', distance: 'I (中距)' },
    { key: 'S1.1',   speed: '155M',  wave: '1310nm', distance: 'S (短距)' },
    { key: 'L1.1',   speed: '155M',  wave: '1310nm', distance: 'L (长距)' },
    { key: 'V1.1',   speed: '155M',  wave: '1550nm', distance: 'V (超长距)' },
    // 10G (STM-64, 850nm)
    { key: 'S64.2b', speed: '10G',   wave: '850nm',  distance: 'S (短距)' },
    { key: 'L64.2',  speed: '10G',   wave: '850nm',  distance: 'L (长距)' },
    { key: 'V64.2',  speed: '10G',   wave: '850nm',  distance: 'V (超长距)' },
    // GE
    { key: '1000BASE-SX', speed: 'GE', wave: '850nm',  distance: 'SX (短距)' },
    { key: '1000BASE-LX', speed: 'GE', wave: '1310nm', distance: 'LX (长距)' },
];

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

/** 加载全局默认门限 */
export async function loadGlobalThreshold() {
    try {
        const rules = await get('/inspection/thresholds');
        const g = rules.find(r => r.levelType === 'GLOBAL');
        if (g) {
            document.getElementById('gRxLow').value = g.rxLow != null ? g.rxLow : -28;
            document.getElementById('gRxHigh').value = g.rxHigh != null ? g.rxHigh : -8;
            document.getElementById('gTxLow').value = g.txLow != null ? g.txLow : -6;
            document.getElementById('gTxHigh').value = g.txHigh != null ? g.txHigh : 0;
        }
    } catch (e) { console.error('loadGlobalThreshold', e); }
}

/** 保存全局默认门限 */
export async function saveGlobalThreshold() {
    const body = {
        levelType: 'GLOBAL', matchKey: 'GLOBAL',
        rxLow: parseFloat(document.getElementById('gRxLow').value),
        rxHigh: parseFloat(document.getElementById('gRxHigh').value),
        txLow: parseFloat(document.getElementById('gTxLow').value),
        txHigh: parseFloat(document.getElementById('gTxHigh').value),
        description: '默认全局门限'
    };
    try {
        await post('/inspection/thresholds', body);
        const msg = document.getElementById('globalThresholdMsg');
        msg.textContent = '保存成功'; msg.style.display = 'block';
        setTimeout(() => { msg.style.display = 'none'; }, 2000);
    } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
}

/** 加载所有门限规则（模块类型+型号） */
export async function loadThresholds() {
    try {
        const rules = await get('/inspection/thresholds');
        renderThresholdTables(rules);
    } catch (e) { console.error('loadThresholds', e); }
}

/** 渲染模块类型规则表格 */
function renderThresholdTables(rules) {
    const moduleTbody = document.getElementById('moduleThresholdTable');
    moduleTbody.textContent = '';

    const moduleRules = rules.filter(r => r.levelType === 'MODULE');

    if (moduleRules.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 7; td.className = 'empty'; td.textContent = '暂无模块类型规则，可点击「添加」为特定模块类型配置独立门限';
        tr.appendChild(td); moduleTbody.appendChild(tr);
    } else {
        moduleRules.forEach(r => moduleTbody.appendChild(buildThresholdRow(r)));
    }
}

/** 构建单条门限规则行 */
function buildThresholdRow(r) {
    const tr = document.createElement('tr');
    let displayKey = r.matchKey;
    if (r.levelType === 'MODULE') {
        const m = MODULE_TYPES.find(t => t.key === r.matchKey);
        if (m) displayKey = m.key + '  (' + m.speed + ', ' + m.wave + ', ' + m.distance + ')';
    }
    tr.appendChild(createTextCell(displayKey));
    tr.appendChild(createTextCell(r.rxLow != null ? r.rxLow : '-'));
    tr.appendChild(createTextCell(r.rxHigh != null ? r.rxHigh : '-'));
    tr.appendChild(createTextCell(r.txLow != null ? r.txLow : '-'));
    tr.appendChild(createTextCell(r.txHigh != null ? r.txHigh : '-'));
    tr.appendChild(createTextCell(r.description || '-'));
    const opTd = document.createElement('td');
    opTd.appendChild(createBtn('编辑', 'btn btn-outline btn-sm', () => editThreshold(r)));
    opTd.appendChild(document.createTextNode(' '));
    opTd.appendChild(createBtn('删除', 'btn btn-danger btn-sm', () => deleteThreshold(r.id)));
    tr.appendChild(opTd);
    return tr;
}

/** 打开门限规则弹窗（新增/编辑） */
export function openThresholdModal(levelType, rule) {
    document.getElementById('thLevelType').value = levelType;
    document.getElementById('thId').value = rule ? rule.id : '';
    document.getElementById('thRxLow').value = rule && rule.rxLow != null ? rule.rxLow : '';
    document.getElementById('thRxHigh').value = rule && rule.rxHigh != null ? rule.rxHigh : '';
    document.getElementById('thTxLow').value = rule && rule.txLow != null ? rule.txLow : '';
    document.getElementById('thTxHigh').value = rule && rule.txHigh != null ? rule.txHigh : '';
    document.getElementById('thDesc').value = rule ? (rule.description || '') : '';
    document.getElementById('thresholdModalTitle').textContent = rule ? '编辑门限规则' : '新增门限规则';

    // 填充下拉列表
    const sel = document.getElementById('thModuleSelect');
    sel.textContent = '';
    const defaultOpt = document.createElement('option');
    defaultOpt.value = '';
    defaultOpt.textContent = '-- 请选择模块类型 --';
    sel.appendChild(defaultOpt);
    MODULE_TYPES.forEach(m => {
        const opt = document.createElement('option');
        opt.value = m.key;
        opt.textContent = m.key + '  (' + m.speed + ', ' + m.wave + ', ' + m.distance + ')';
        sel.appendChild(opt);
    });
    // 编辑时回显选中值
    if (rule && rule.matchKey) {
        sel.value = rule.matchKey;
        onModuleSelectChange();
    } else {
        document.getElementById('thModuleInfo').textContent = '';
    }

    document.getElementById('thresholdModal').classList.remove('hidden');
}

/** 编辑门限规则 */
function editThreshold(rule) { openThresholdModal(rule.levelType, rule); }

/** 模块类型下拉选择变化 */
export function onModuleSelectChange() {
    const key = document.getElementById('thModuleSelect').value;
    const info = document.getElementById('thModuleInfo');
    if (!key) { info.textContent = ''; return; }
    const m = MODULE_TYPES.find(t => t.key === key);
    if (m) {
        info.textContent = '速率: ' + m.speed + ' | 波长: ' + m.wave + ' | 距离档: ' + m.distance;
    } else {
        info.textContent = '';
    }
}

/** 关闭门限规则弹窗 */
export function closeThresholdModal() { document.getElementById('thresholdModal').classList.add('hidden'); }

/** 保存门限规则（新增/编辑） */
export async function saveThreshold() {
    const id = document.getElementById('thId').value;
    const levelType = document.getElementById('thLevelType').value;
    const matchKey = document.getElementById('thModuleSelect').value;
    const body = {
        levelType: levelType,
        matchKey: matchKey,
        rxLow: parseFloat(document.getElementById('thRxLow').value),
        rxHigh: parseFloat(document.getElementById('thRxHigh').value),
        txLow: parseFloat(document.getElementById('thTxLow').value),
        txHigh: parseFloat(document.getElementById('thTxHigh').value),
        description: document.getElementById('thDesc').value.trim()
    };
    if (!body.matchKey) { showToast('请输入匹配键', 'error'); return; }
    try {
        await post('/inspection/thresholds', body);
        closeThresholdModal();
        loadThresholds();
    } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
}

/** 删除门限规则 */
async function deleteThreshold(id) {
    if (!confirm('确认删除该门限规则？')) return;
    try {
        await del('/inspection/thresholds/' + id);
        loadThresholds();
    } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
}

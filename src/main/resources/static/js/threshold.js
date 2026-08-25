/**
 * 门限配置模块
 * 管理全局/模块类型/型号三级光功率门限规则
 */

import { get, post, del } from './api.js';

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
    } catch (e) { alert('保存失败: ' + e.message); }
}

/** 加载所有门限规则（模块类型+型号） */
export async function loadThresholds() {
    try {
        const rules = await get('/inspection/thresholds');
        renderThresholdTables(rules);
    } catch (e) { console.error('loadThresholds', e); }
}

/** 渲染模块类型和型号规则表格 */
function renderThresholdTables(rules) {
    const moduleTbody = document.getElementById('moduleThresholdTable');
    const partTbody = document.getElementById('partThresholdTable');
    moduleTbody.textContent = '';
    partTbody.textContent = '';

    const moduleRules = rules.filter(r => r.levelType === 'MODULE');
    const partRules = rules.filter(r => r.levelType === 'PART');

    if (moduleRules.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 7; td.className = 'empty'; td.textContent = '暂无模块类型规则';
        tr.appendChild(td); moduleTbody.appendChild(tr);
    } else {
        moduleRules.forEach(r => moduleTbody.appendChild(buildThresholdRow(r)));
    }
    if (partRules.length === 0) {
        const tr2 = document.createElement('tr');
        const td2 = document.createElement('td');
        td2.colSpan = 7; td2.className = 'empty'; td2.textContent = '暂无型号规则';
        tr2.appendChild(td2); partTbody.appendChild(tr2);
    } else {
        partRules.forEach(r => partTbody.appendChild(buildThresholdRow(r)));
    }
}

/** 构建单条门限规则行 */
function buildThresholdRow(r) {
    const tr = document.createElement('tr');
    tr.appendChild(createTextCell(r.matchKey));
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
    document.getElementById('thMatchKey').value = rule ? rule.matchKey : '';
    document.getElementById('thRxLow').value = rule && rule.rxLow != null ? rule.rxLow : '';
    document.getElementById('thRxHigh').value = rule && rule.rxHigh != null ? rule.rxHigh : '';
    document.getElementById('thTxLow').value = rule && rule.txLow != null ? rule.txLow : '';
    document.getElementById('thTxHigh').value = rule && rule.txHigh != null ? rule.txHigh : '';
    document.getElementById('thDesc').value = rule ? (rule.description || '') : '';
    document.getElementById('thMatchKeyLabel').textContent = levelType === 'PART' ? '型号编码 (partNumber)' : '模块类型 (moduleTypeKey)';
    document.getElementById('thMatchKey').placeholder = levelType === 'PART' ? '如 ABCD-1234' : '如 2.5G-L、GE-LX';
    document.getElementById('thresholdModalTitle').textContent = rule ? '编辑门限规则' : '新增门限规则';
    document.getElementById('thresholdModal').classList.remove('hidden');
}

/** 编辑门限规则 */
function editThreshold(rule) { openThresholdModal(rule.levelType, rule); }

/** 关闭门限规则弹窗 */
export function closeThresholdModal() { document.getElementById('thresholdModal').classList.add('hidden'); }

/** 保存门限规则（新增/编辑） */
export async function saveThreshold() {
    const id = document.getElementById('thId').value;
    const body = {
        levelType: document.getElementById('thLevelType').value,
        matchKey: document.getElementById('thMatchKey').value.trim(),
        rxLow: parseFloat(document.getElementById('thRxLow').value),
        rxHigh: parseFloat(document.getElementById('thRxHigh').value),
        txLow: parseFloat(document.getElementById('thTxLow').value),
        txHigh: parseFloat(document.getElementById('thTxHigh').value),
        description: document.getElementById('thDesc').value.trim()
    };
    if (!body.matchKey) { alert('请输入匹配键'); return; }
    try {
        await post('/inspection/thresholds', body);
        closeThresholdModal();
        loadThresholds();
    } catch (e) { alert('保存失败: ' + e.message); }
}

/** 删除门限规则 */
async function deleteThreshold(id) {
    if (!confirm('确认删除该门限规则？')) return;
    try {
        await del('/inspection/thresholds/' + id);
        loadThresholds();
    } catch (e) { alert('删除失败: ' + e.message); }
}

/**
 * 类型统计模块
 * 展示设备类型分布，支持柱状图/饼图/折线图切换
 */

import { get } from './api.js';

/** ECharts 图表实例 */
let statsChart = null;
/** 图表数据缓存 */
let statsChartData = [];
/** 当前图表类型: bar/pie/line */
let statsChartType = 'bar';
/** 图表配色方案 */
const STATS_COLORS = ['#1a73e8','#34a853','#ea580c','#7c3aed','#dc2626','#0891b2','#ca8a04','#be185d','#4f46e5','#059669'];

/** 创建文本单元格 */
function createTextCell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
}

/** 加载统计概览（网元/盘/端口/网络数量） */
export async function loadStatsOverview() {
    try {
        const d = await get('/inventory/overview');
        document.getElementById('stNe').textContent = d.neCount || 0;
        document.getElementById('stSlot').textContent = d.slotCount || 0;
        document.getElementById('stPort').textContent = d.portCount || 0;
        document.getElementById('stNetwork').textContent = d.networkCount || 0;
    } catch (e) { console.error('loadStatsOverview', e); }
}

/** 加载网络列表（用于筛选下拉框） */
export async function loadStatsNetworks() {
    try {
        const data = await get('/inventory/networks');
        const dl = document.getElementById('statsNetworkList');
        dl.textContent = '';
        data.forEach(n => {
            const opt = document.createElement('option');
            opt.value = n;
            dl.appendChild(opt);
        });
    } catch (e) { console.error('loadStatsNetworks', e); }
}

/** 加载类型统计数据 */
export async function loadStats() {
    const network = document.getElementById('statsNetwork').value.trim();
    const scope = document.getElementById('statsScope').value;
    let url = scope === 'online' ? '/stats/type/online' : '/stats/type';
    if (network) url += '?network=' + encodeURIComponent(network);
    try {
        statsChartData = await get(url);
        renderStatsChart();
        renderStatsTable(statsChartData);
    } catch (e) { console.error('loadStats', e); }
}

/** 切换图表类型（柱状图/饼图/折线图） */
export function switchStatsChart(type, btn) {
    statsChartType = type;
    btn.parentElement.querySelectorAll('button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    renderStatsChart();
}

/** 渲染 ECharts 图表 */
function renderStatsChart() {
    const container = document.getElementById('statsChart');
    if (!container || container.offsetParent === null) return;

    if (!statsChart) {
        statsChart = echarts.init(container);
    } else {
        statsChart.resize();
    }
    if (!statsChartData || statsChartData.length === 0) {
        statsChart.setOption({ title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#999', fontSize: 14 } } });
        return;
    }
    const top20 = statsChartData.slice(0, 20);
    const names = top20.map(d => d.typeName);
    const values = top20.map(d => d.count);

    if (statsChartType === 'pie') {
        statsChart.setOption({
            tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
            legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { fontSize: 11 } },
            color: STATS_COLORS,
            series: [{ type: 'pie', radius: ['35%', '70%'], center: ['40%', '50%'],
                label: { show: false },
                emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
                data: top20.map(d => ({ name: d.typeName, value: d.count }))
            }]
        }, true);
    } else if (statsChartType === 'line') {
        statsChart.setOption({
            tooltip: { trigger: 'axis' },
            grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
            xAxis: { type: 'category', data: names, axisLabel: { rotate: 30, fontSize: 10 } },
            yAxis: { type: 'value' },
            color: STATS_COLORS,
            series: [{ type: 'line', data: values, smooth: true, symbol: 'circle', symbolSize: 6,
                lineStyle: { width: 2 }, areaStyle: { opacity: 0.15 },
                label: { show: true, position: 'top', fontSize: 11 }
            }]
        }, true);
    } else {
        statsChart.setOption({
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
            grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
            xAxis: { type: 'category', data: names, axisLabel: { rotate: 30, fontSize: 10 } },
            yAxis: { type: 'value' },
            color: STATS_COLORS,
            series: [{ type: 'bar', data: values,
                itemStyle: { borderRadius: [4, 4, 0, 0] }, barWidth: '60%',
                label: { show: true, position: 'top', fontSize: 11 }
            }]
        }, true);
    }
}

/** 渲染统计表格 */
function renderStatsTable(data) {
    const tbody = document.getElementById('statsTable');
    tbody.textContent = '';
    if (!data || data.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 3; td.className = 'empty'; td.textContent = '暂无数据';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }
    data.forEach(d => {
        const tr = document.createElement('tr');
        tr.appendChild(createTextCell(d.typeName));
        tr.appendChild(createTextCell(String(d.count)));
        tr.appendChild(createTextCell(d.percent + '%'));
        tbody.appendChild(tr);
    });
}

/** 窗口大小变化时调整图表 */
export function resizeChart() {
    if (statsChart) statsChart.resize();
}

/**
 * 时钟拓扑模块
 * 采集并可视化 MSTP 网元时钟同步链路
 */

import { get, post } from './api.js';
import { showToast, withLoading } from './toast.js';

/** ECharts 图表实例 */
let clockChart = null;
/** 缓存拓扑数据 */
let topologyData = [];

/** 时钟状态颜色 */
const CLOCK_COLORS = {
    1: '#16a34a',  // 锁定 - 绿色
    2: '#eab308',  // 保持 - 黄色
    3: '#dc2626',  // 自由振荡 - 红色
    0: '#9ca3af'   // 未知 - 灰色
};

/** SSM 质量等级 */
const SSM_NAMES = {
    0x02: 'PRC (G.811)',
    0x04: 'SSU-A (G.812)',
    0x08: 'SSU-B (G.812)',
    0x10: 'SEC (G.813)',
    0x20: 'DNU',
    0xFF: '未知'
};

/** 刷新时钟拓扑 */
export function refreshClockTopology() {
    const btn = document.getElementById('btnRefreshClock');
    if (!btn) return;
    withLoading(btn, async () => {
        topologyData = await post('/inspection/clock/refresh');
        renderClockTopology();
        updateClockStats();
        showToast('时钟拓扑刷新完成', 'success');
    });
}

/** 加载时钟拓扑（从缓存） */
export async function loadClockTopology() {
    try {
        topologyData = await get('/inspection/clock/topology');
        renderClockTopology();
        updateClockStats();
    } catch (e) {
        console.error('loadClockTopology', e);
    }
}

/** 更新统计卡片 */
function updateClockStats() {
    const total = topologyData.length;
    const locked = topologyData.filter(n => n.clockState === 1).length;
    const holdover = topologyData.filter(n => n.clockState === 2).length;
    const freeRun = topologyData.filter(n => n.clockState === 3).length;

    const el = (id) => document.getElementById(id);
    if (el('clockTotal')) el('clockTotal').textContent = total;
    if (el('clockLocked')) el('clockLocked').textContent = locked;
    if (el('clockHoldover')) el('clockHoldover').textContent = holdover;
    if (el('clockFreeRun')) el('clockFreeRun').textContent = freeRun;
}

/** 渲染时钟拓扑图 */
function renderClockTopology() {
    const container = document.getElementById('clockChart');
    if (!container) return;

    if (!clockChart) {
        clockChart = echarts.init(container);
    } else {
        clockChart.resize();
    }

    if (!topologyData || topologyData.length === 0) {
        clockChart.setOption({
            title: { text: '暂无数据，请点击"刷新"采集', left: 'center', top: 'center', textStyle: { color: '#999', fontSize: 14 } }
        });
        return;
    }

    // 构建节点和边
    const nodes = [];
    const links = [];
    const neIdMap = {}; // neId -> node index

    topologyData.forEach((node, idx) => {
        neIdMap[node.neId] = idx;
        nodes.push({
            id: node.neId,
            name: node.neName,
            symbolSize: 50,
            itemStyle: {
                color: CLOCK_COLORS[node.clockState] || CLOCK_COLORS[0],
                borderColor: '#fff',
                borderWidth: 2
            },
            label: {
                show: true,
                position: 'bottom',
                fontSize: 11,
                formatter: '{b}\n' + node.getClockStateText
            },
            data: node // 存储完整数据供点击使用
        });

        // 构建时钟源边
        if (node.sources) {
            node.sources.forEach(src => {
                if (src.current && src.clockSourceState === 1) {
                    // 当前使用的时钟源 - 实线
                    links.push({
                        source: node.neId,
                        target: node.neId, // 线路时钟来自同网络的其他网元
                        lineStyle: {
                            color: '#666',
                            width: 2,
                            type: 'solid'
                        },
                        label: {
                            show: true,
                            formatter: 'SSM:' + formatSSM(src.realSSM),
                            fontSize: 10
                        }
                    });
                }
            });
        }
    });

    // 如果没有边，添加说明
    if (links.length === 0) {
        links.push({
            source: topologyData[0]?.neId,
            target: topologyData[0]?.neId,
            lineStyle: { opacity: 0 }
        });
    }

    clockChart.setOption({
        tooltip: {
            formatter: function(params) {
                if (params.dataType === 'node') {
                    const node = params.data.data;
                    if (!node) return params.name;
                    return '<b>' + node.neName + '</b><br/>' +
                           '网元ID: ' + node.neId + '<br/>' +
                           'IP: ' + (node.ipAddr || '-') + '<br/>' +
                           '网络: ' + (node.networkName || '-') + '<br/>' +
                           '时钟状态: ' + node.getClockStateText() + '<br/>' +
                           '时钟源数: ' + (node.sources ? node.sources.length : 0);
                }
                return '';
            }
        },
        series: [{
            type: 'graph',
            layout: 'force',
            roam: true,
            draggable: true,
            force: {
                repulsion: 200,
                edgeLength: [80, 200],
                gravity: 0.1
            },
            data: nodes,
            links: links,
            emphasis: {
                focus: 'adjacency',
                lineStyle: { width: 4 }
            },
            label: {
                show: true,
                position: 'bottom',
                fontSize: 11
            },
            edgeLabel: {
                show: true,
                fontSize: 10,
                color: '#666'
            }
        }]
    }, true);

    // 点击节点显示详情
    clockChart.off('click');
    clockChart.on('click', function(params) {
        if (params.dataType === 'node' && params.data.data) {
            showClockDetail(params.data.data);
        }
    });
}

/** 显示网元时钟详情 */
function showClockDetail(node) {
    const panel = document.getElementById('clockDetail');
    if (!panel) return;

    let html = '<div style="padding:16px;">';
    html += '<h3 style="margin:0 0 12px 0;font-size:16px;">' + node.neName + '</h3>';
    html += '<div style="font-size:13px;color:#6b7280;line-height:2;">';
    html += '<div>网元ID: ' + node.neId + '</div>';
    html += '<div>IP: ' + (node.ipAddr || '-') + '</div>';
    html += '<div>网络: ' + (node.networkName || '-') + '</div>';
    html += '<div>时钟状态: <span style="color:' + (CLOCK_COLORS[node.clockState] || '#999') + ';font-weight:600;">' + node.getClockStateText() + '</span></div>';
    html += '</div>';

    if (node.sources && node.sources.length > 0) {
        html += '<div style="margin-top:12px;font-size:13px;font-weight:600;">时钟源列表</div>';
        html += '<table style="width:100%;border-collapse:collapse;font-size:12px;margin-top:8px;">';
        html += '<tr style="background:#f9fafb;"><th style="padding:6px;text-align:left;border:1px solid #e5e7eb;">类型</th><th style="padding:6px;text-align:left;border:1px solid #e5e7eb;">优先级</th><th style="padding:6px;text-align:left;border:1px solid #e5e7eb;">状态</th><th style="padding:6px;text-align:left;border:1px solid #e5e7eb;">SSM</th><th style="padding:6px;text-align:left;border:1px solid #e5e7eb;">选择原因</th></tr>';

        node.sources.forEach(src => {
            const typeText = src.systemClock ? '系统时钟' : '导出时钟';
            const stateText = src.clockSourceState === 1 ? '可用' : '不可用';
            const stateColor = src.current ? '#16a34a' : (src.clockSourceState === 1 ? '#6b7280' : '#dc2626');
            html += '<tr>';
            html += '<td style="padding:6px;border:1px solid #e5e7eb;">' + typeText + (src.current ? ' ★' : '') + '</td>';
            html += '<td style="padding:6px;border:1px solid #e5e7eb;">' + src.priority + '</td>';
            html += '<td style="padding:6px;border:1px solid #e5e7eb;color:' + stateColor + ';">' + stateText + '</td>';
            html += '<td style="padding:6px;border:1px solid #e5e7eb;">' + formatSSM(src.realSSM) + '</td>';
            html += '<td style="padding:6px;border:1px solid #e5e7eb;">' + src.selReason + '</td>';
            html += '</tr>';
        });
        html += '</table>';
    } else {
        html += '<div style="margin-top:12px;color:#9ca3af;font-size:13px;">无时钟源数据</div>';
    }

    html += '</div>';
    panel.innerHTML = html;
    panel.style.display = 'block';
}

/** 格式化 SSM 值 */
function formatSSM(ssm) {
    return SSM_NAMES[ssm] || ('0x' + ssm.toString(16).toUpperCase());
}

/** 窗口大小变化时重绘 */
export function resizeClockChart() {
    if (clockChart) {
        clockChart.resize();
    }
}

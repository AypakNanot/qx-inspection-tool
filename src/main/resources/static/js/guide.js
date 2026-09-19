/**
 * 操作指南模块
 * 绘制操作流程图、各页面子流程说明、快捷跳转
 */

/** 流程步骤定义 */
const STEPS = [
    {
        num: 1, label: '配置数据源', page: 'page-maintenance',
        desc: '在「数据维护」页面配置 MySQL 数据库连接参数',
        detail: [
            '切换到「数据维护」页面',
            '填写数据库主机地址、用户名和密码',
            '点击「测试连接」确认能正常连通',
            '点击「保存配置」持久化连接信息'
        ]
    },
    {
        num: 2, label: '同步设备', page: 'page-maintenance',
        desc: '从数据库同步网元和端口等核心数据',
        detail: [
            '在「数据同步」区域选择需要同步的网络',
            '点击「执行同步」开始同步数据',
            '等待同步完成，查看同步状态确认成功'
        ]
    },
    {
        num: 3, label: '导入设备', page: 'page-device',
        desc: '在「设备管理」页面查看同步的设备并建立连接',
        detail: [
            '点击「同步设备」从已同步的数据中导入网元列表',
            '在全局连接配置中填写设备的公共连接参数（端口、用户名、密码）',
            '点击「一键连接」建立与所有设备的通信连接',
            '确认设备在线状态变为绿色'
        ]
    },
    {
        num: 4, label: '配置门限', page: 'page-threshold',
        desc: '设置光功率告警门限（可选，有标准默认值）',
        detail: [
            '查看各模块类型的标准门限值（发送/接收功率上下限）',
            '如需修改，点击「修改」编辑门限值',
            '可点击「恢复默认」还原为标准值',
            '保存后对后续巡检生效（不影响历史数据）'
        ]
    },
    {
        num: 5, label: '执行巡检', page: 'page-task',
        desc: '配置并触发光功率巡检任务',
        detail: [
            '在「采集参数」中配置并发数、保留轮次数等',
            '选择巡检范围：全网 / 指定网络 / 指定网元',
            '可配置定时巡检（自动按计划执行）',
            '或点击「立即巡检」手动触发一次',
            '巡检过程中可切换到「任务进度」页面查看实时进度'
        ]
    },
    {
        num: 6, label: '查看结果', page: 'page-query',
        desc: '查询链路巡检结果、导出 Excel 报表',
        detail: [
            '选择要查看的巡检轮次（默认最新）',
            '可按网络、网元、光模块、异常状态等筛选',
            '每条链路一行，A 端与 Z 端并列展示光功率、误码与带宽',
            '收发光状态：正常（绿）、异常（红）',
            '点击「导出 Excel」下载报表（含门限说明 Sheet）'
        ]
    }
];

/** 渲染流程图 */
function renderFlowchart() {
    const container = document.getElementById('guideFlowchart');
    if (!container) return;
    container.textContent = '';

    const flow = document.createElement('div');
    flow.style.cssText = 'display:flex;align-items:flex-start;gap:0;overflow-x:auto;padding:8px 0;';

    STEPS.forEach((step, i) => {
        // 步骤卡片
        const card = document.createElement('div');
        card.style.cssText = 'flex:0 0 auto;display:flex;flex-direction:column;align-items:center;min-width:120px;cursor:pointer;';
        card.onclick = () => {
            const nav = document.querySelector(`[data-page="${step.page}"]`);
            if (nav) window.switchPage(nav);
        };

        const circle = document.createElement('div');
        circle.style.cssText = 'width:36px;height:36px;border-radius:50%;background:#1a73e8;color:#fff;display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:600;';
        circle.textContent = step.num;
        card.appendChild(circle);

        const label = document.createElement('div');
        label.style.cssText = 'margin-top:6px;font-size:12px;color:#374151;font-weight:500;text-align:center;';
        label.textContent = step.label;
        card.appendChild(label);

        const desc = document.createElement('div');
        desc.style.cssText = 'margin-top:2px;font-size:11px;color:#9ca3af;text-align:center;max-width:110px;line-height:1.4;';
        desc.textContent = step.desc;
        card.appendChild(desc);

        const link = document.createElement('div');
        link.style.cssText = 'margin-top:4px;font-size:11px;color:#1a73e8;';
        link.textContent = '前往 →';
        card.appendChild(link);

        flow.appendChild(card);

        // 箭头（非最后一个）
        if (i < STEPS.length - 1) {
            const arrow = document.createElement('div');
            arrow.style.cssText = 'flex:0 0 auto;display:flex;align-items:center;padding-top:6px;color:#d1d5db;font-size:20px;';
            arrow.textContent = '→';
            flow.appendChild(arrow);
        }
    });

    container.appendChild(flow);
}

/** 渲染各页面操作说明 */
function renderDetails() {
    const container = document.getElementById('guideDetails');
    if (!container) return;
    container.textContent = '';

    STEPS.forEach(step => {
        const section = document.createElement('div');
        section.style.cssText = 'margin-bottom:20px;';

        // 标题行
        const header = document.createElement('div');
        header.style.cssText = 'display:flex;align-items:center;gap:8px;margin-bottom:8px;cursor:pointer;';
        header.onclick = () => {
            const nav = document.querySelector(`[data-page="${step.page}"]`);
            if (nav) window.switchPage(nav);
        };

        const badge = document.createElement('span');
        badge.style.cssText = 'width:22px;height:22px;border-radius:50%;background:#1a73e8;color:#fff;display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:600;flex-shrink:0;';
        badge.textContent = step.num;
        header.appendChild(badge);

        const title = document.createElement('span');
        title.style.cssText = 'font-size:14px;font-weight:600;color:#1f2937;';
        title.textContent = step.label;
        header.appendChild(title);

        const pageLink = document.createElement('span');
        pageLink.style.cssText = 'font-size:12px;color:#1a73e8;margin-left:auto;';
        pageLink.textContent = '打开页面 →';
        header.appendChild(pageLink);

        section.appendChild(header);

        // 子流程列表
        const list = document.createElement('div');
        list.style.cssText = 'margin-left:30px;padding:10px 14px;background:#f8fafc;border-radius:8px;border:1px solid #e5e7eb;';
        step.detail.forEach((item, j) => {
            const row = document.createElement('div');
            row.style.cssText = 'display:flex;align-items:flex-start;gap:8px;padding:3px 0;font-size:13px;color:#4b5563;line-height:1.6;';

            const dot = document.createElement('span');
            dot.style.cssText = 'color:#1a73e8;font-weight:600;flex-shrink:0;';
            dot.textContent = (j + 1) + '.';
            row.appendChild(dot);

            const text = document.createElement('span');
            text.textContent = item;
            row.appendChild(text);

            list.appendChild(row);
        });
        section.appendChild(list);

        container.appendChild(section);
    });
}

/** 初始化操作指南页面 */
export function initGuide() {
    renderFlowchart();
    renderDetails();
}

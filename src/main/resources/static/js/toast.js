/**
 * Toast 通知 + 按钮 Loading 工具
 */

let container = null;

function ensureContainer() {
    if (!container) {
        container = document.createElement('div');
        container.style.cssText = 'position:fixed;top:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:8px;pointer-events:none;';
        document.body.appendChild(container);
    }
    return container;
}

const ICONS = { success: '✓', error: '✗', info: 'ℹ' };
const COLORS = { success: '#16a34a', error: '#dc2626', info: '#1a73e8' };
const BGS = { success: '#f0fdf4', error: '#fef2f2', info: '#eff6ff' };

/**
 * 显示 toast 通知
 * @param {string} msg 消息文本
 * @param {'success'|'error'|'info'} type 类型
 */
export function showToast(msg, type = 'info') {
    const c = ensureContainer();
    const color = COLORS[type] || COLORS.info;
    const bg = BGS[type] || BGS.info;

    const el = document.createElement('div');
    el.style.cssText = 'pointer-events:auto;display:flex;align-items:center;gap:8px;padding:10px 16px;border-radius:8px;box-shadow:0 4px 12px rgba(0,0,0,0.15);font-size:13px;color:#1f2937;max-width:360px;opacity:0;transform:translateX(20px);transition:all 0.3s;background:' + bg + ';border-left:4px solid ' + color + ';';

    const icon = document.createElement('span');
    icon.style.cssText = 'color:' + color + ';font-size:16px;font-weight:bold;';
    icon.textContent = ICONS[type];

    const text = document.createElement('span');
    text.textContent = msg;

    el.appendChild(icon);
    el.appendChild(text);
    c.appendChild(el);

    requestAnimationFrame(() => { el.style.opacity = '1'; el.style.transform = 'translateX(0)'; });

    const duration = type === 'error' ? 7000 : 5000;
    setTimeout(() => {
        el.style.opacity = '0';
        el.style.transform = 'translateX(20px)';
        setTimeout(() => {
            el.remove();
            if (container && container.children.length === 0) {
                container.remove();
                container = null;
            }
        }, 300);
    }, duration);
}

/**
 * 按钮 loading 包装
 * @param {HTMLButtonElement} btn 按钮元素
 * @param {() => Promise<void>} asyncFn 异步操作
 */
export async function withLoading(btn, asyncFn) {
    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '处理中...';
    try {
        await asyncFn();
    } catch (e) {
        showToast(e.message || '操作失败', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = originalText;
    }
}

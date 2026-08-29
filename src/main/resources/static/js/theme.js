/**
 * 深色模式模块
 * 管理主题切换和持久化
 */

const STORAGE_KEY = 'qx-theme';

/** 初始化主题（页面加载时调用） */
export function initTheme() {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'dark') {
        document.body.setAttribute('data-theme', 'dark');
        updateToggleBtn(true);
    }
}

/** 切换主题 */
export function toggleTheme() {
    const isDark = document.body.getAttribute('data-theme') === 'dark';
    if (isDark) {
        document.body.removeAttribute('data-theme');
        localStorage.setItem(STORAGE_KEY, 'light');
        updateToggleBtn(false);
    } else {
        document.body.setAttribute('data-theme', 'dark');
        localStorage.setItem(STORAGE_KEY, 'dark');
        updateToggleBtn(true);
    }
}

/** 更新按钮图标 */
function updateToggleBtn(isDark) {
    const btn = document.getElementById('themeToggle');
    if (btn) btn.textContent = isDark ? '☀️' : '🌙';
}

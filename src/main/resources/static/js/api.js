/**
 * API 请求模块
 * 封装所有后端接口调用，统一处理请求/响应
 */

export const API = '/qx-inspection/api';

/** GET 请求 */
export async function get(path) {
    const res = await fetch(API + path);
    if (!res.ok) throw new Error(res.statusText);
    return res.json();
}

/** POST 请求 */
export async function post(path, body) {
    const opts = { method: 'POST' };
    if (body !== undefined) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(body);
    }
    const res = await fetch(API + path, opts);
    if (!res.ok) throw new Error(res.statusText);
    return res.json();
}

/** PUT 请求 */
export async function put(path, body) {
    const res = await fetch(API + path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error(res.statusText);
    return res.json();
}

/** DELETE 请求 */
export async function del(path) {
    const res = await fetch(API + path, { method: 'DELETE' });
    if (!res.ok) throw new Error(res.statusText);
    return res;
}

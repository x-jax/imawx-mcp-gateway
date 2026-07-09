/**
 * 薄包装:让 {@code import { request } from '@/utils/request'} 的写法可以工作。
 *
 * <p>背景(2026-07-03):
 * <ul>
 *   <li>{@code src/api/sys/account.ts} 走 {@code request<T>({url, method, params})}
 *       的"通用方法+显式 method"风格</li>
 *   <li>{@code src/api/sys/{auth,mcp-proxy,...}.ts} 走
 *       {@code request.get<T>({url, params})} 的"方法绑定+省略 method"风格</li>
 *   <li>两种风格在底层都对应 {@link api.request}——本文件提供一个 named export
 *       {@code request} 让前一种 import 写法落地,避免改 14 处 call site。</li>
 * </ul>
 *
 * <p>后续:如果所有 api 文件统一成 {@code request.get/post/...} 风格,本文件可以删。
 *
 * @module utils/request
 */
import api from './http'

export function request<T = any>(config: Parameters<typeof api.request<T>>[0]): Promise<T> {
  return api.request<T>(config)
}

export default request

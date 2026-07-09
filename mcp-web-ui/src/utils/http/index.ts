/**
 * HTTP 请求封装模块
 * 基于 Axios 封装的 HTTP 请求工具，提供统一的请求/响应处理
 *
 * ## 主要功能
 *
 * - 请求/响应拦截器（自动添加 Token、统一错误处理）
 * - 401 未授权自动登出（带防抖机制）
 * - 请求失败自动重试（可配置）
 * - 统一的成功/错误消息提示
 * - 支持 GET/POST/PUT/DELETE 等常用方法
 *
 * @module utils/http
 * @author Art Design Pro Team
 */

import axios, { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { useUserStore } from '@/store/modules/user'
import { ApiStatus } from './status'
import { HttpError, handleError, showError, showSuccess } from './error'
import { $t } from '@/locales'
import { BaseResponse } from '@/types'

/** 请求配置常量 */
const REQUEST_TIMEOUT = 15000
const MAX_RETRIES = 0
const RETRY_DELAY = 1000
const UNAUTHORIZED_DEBOUNCE_TIME = 3000
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

/** 401防抖状态 */
let isUnauthorizedErrorShown = false
let unauthorizedTimer: NodeJS.Timeout | null = null

/** 扩展 AxiosRequestConfig */
interface ExtendedAxiosRequestConfig extends AxiosRequestConfig {
  showErrorMessage?: boolean
  showSuccessMessage?: boolean
}

const { VITE_API_URL, VITE_WITH_CREDENTIALS } = import.meta.env
const CSRF_ENDPOINT = '/api/sys/auth/csrf'
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfHeaderName = CSRF_HEADER_NAME
let csrfToken = ''
let csrfLoading: Promise<void> | null = null

/** Axios实例 */
const axiosInstance = axios.create({
  timeout: REQUEST_TIMEOUT,
  baseURL: VITE_API_URL,
  withCredentials: VITE_WITH_CREDENTIALS === 'true',
  xsrfCookieName: CSRF_COOKIE_NAME,
  xsrfHeaderName: CSRF_HEADER_NAME,
  withXSRFToken: true,
  validateStatus: (status) => status >= 200 && status < 300,
  transformResponse: [
    (data, headers) => {
      const contentType = headers['content-type']
      if (contentType?.includes('application/json')) {
        try {
          return JSON.parse(data)
        } catch {
          return data
        }
      }
      return data
    }
  ]
})

/** 请求拦截器 */
// imawx-mcp (2026-06-27): 后台 /api/sys/** 走 servlet HttpSession Cookie 鉴权，
// 不需要 Authorization Header（Authorization Bearer Token 是 MCP 端点用的，
// 不在 Web 前端 WebUI 场景）。
// 保留 accessToken 的目的仅用于 hooks/index.ts / userStore 等可能引用，
// 但 axios 请求不再自动加头。
axiosInstance.interceptors.request.use(
  async (request: InternalAxiosRequestConfig) => {
    // imawx-mcp: 已禁用 Authorization Header 注入，依赖 Cookie 自动转发
    // const { accessToken } = useUserStore()
    // if (accessToken) request.headers.set('Authorization', accessToken)

    if (needsCsrf(request)) {
      let token = readCookie(CSRF_COOKIE_NAME)
      if (!token) {
        await ensureCsrfToken()
        token = csrfToken || readCookie(CSRF_COOKIE_NAME)
      }
      if (token) {
        request.headers.set(csrfHeaderName, token)
      }
    }

    if (request.data && !(request.data instanceof FormData) && !request.headers['Content-Type']) {
      request.headers.set('Content-Type', 'application/json')
      request.data = JSON.stringify(request.data)
    }

    return request
  },
  (error) => {
    showError(createHttpError($t('httpMsg.requestConfigError'), ApiStatus.error))
    return Promise.reject(error)
  }
)

function needsCsrf(request: InternalAxiosRequestConfig): boolean {
  const method = request.method?.toUpperCase() || 'GET'
  if (!UNSAFE_METHODS.has(method)) return false
  return !request.url?.includes(CSRF_ENDPOINT)
}

async function ensureCsrfToken() {
  if (csrfToken) return
  if (!csrfLoading) {
    csrfLoading = axios
      .get(CSRF_ENDPOINT, {
        baseURL: VITE_API_URL,
        withCredentials: VITE_WITH_CREDENTIALS === 'true'
      })
      .then((res) => {
        const data = res.data?.data || res.data
        csrfHeaderName = data?.headerName || csrfHeaderName
        csrfToken = data?.token || readCookie(CSRF_COOKIE_NAME) || ''
      })
      .finally(() => {
        csrfLoading = null
      })
  }
  await csrfLoading
}

function readCookie(name: string): string {
  const encodedName = encodeURIComponent(name) + '='
  const parts = document.cookie ? document.cookie.split(';') : []
  for (const part of parts) {
    const item = part.trim()
    if (item.startsWith(encodedName)) {
      return decodeURIComponent(item.substring(encodedName.length))
    }
  }
  return ''
}

/** 响应拦截器 */
axiosInstance.interceptors.response.use(
  (response: AxiosResponse<BaseResponse>) => {
    const { code, message } = response.data
    if (code === ApiStatus.success) return response
    if (code === ApiStatus.unauthorized) handleUnauthorizedError(message)
    throw createHttpError(message || $t('httpMsg.requestFailed'), code)
  },
  (error) => {
    if (error.response?.status === ApiStatus.unauthorized) handleUnauthorizedError()
    return Promise.reject(handleError(error))
  }
)

/** 统一创建HttpError */
function createHttpError(message: string, code: number) {
  return new HttpError(message, code)
}

/** 处理401错误（带防抖） */
function handleUnauthorizedError(message?: string): never {
  const error = createHttpError(message || $t('httpMsg.unauthorized'), ApiStatus.unauthorized)

  if (!isUnauthorizedErrorShown) {
    isUnauthorizedErrorShown = true
    logOut()

    unauthorizedTimer = setTimeout(resetUnauthorizedError, UNAUTHORIZED_DEBOUNCE_TIME)

    showError(error, true)
    throw error
  }

  throw error
}

/** 重置401防抖状态 */
function resetUnauthorizedError() {
  isUnauthorizedErrorShown = false
  if (unauthorizedTimer) clearTimeout(unauthorizedTimer)
  unauthorizedTimer = null
}

/** 退出登录函数 */
function logOut() {
  useUserStore().logOut()
}

/** 是否需要重试 */
function shouldRetry(statusCode: number) {
  return [
    ApiStatus.requestTimeout,
    ApiStatus.internalServerError,
    ApiStatus.badGateway,
    ApiStatus.serviceUnavailable,
    ApiStatus.gatewayTimeout
  ].includes(statusCode)
}

/** 请求重试逻辑 */
async function retryRequest<T>(
  config: ExtendedAxiosRequestConfig,
  retries: number = MAX_RETRIES
): Promise<T> {
  try {
    return await request<T>(config)
  } catch (error) {
    if (retries > 0 && error instanceof HttpError && shouldRetry(error.code)) {
      await delay(RETRY_DELAY)
      return retryRequest<T>(config, retries - 1)
    }
    throw error
  }
}

/** 延迟函数 */
function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 请求函数 */
async function request<T = any>(config: ExtendedAxiosRequestConfig): Promise<T> {
  // POST | PUT 参数自动填充
  if (
    ['POST', 'PUT'].includes(config.method?.toUpperCase() || '') &&
    config.params &&
    !config.data
  ) {
    config.data = config.params
    config.params = undefined
  }

  try {
    const res = await axiosInstance.request<BaseResponse<T>>(config)

    // 显示成功消息
    if (config.showSuccessMessage && res.data.message) {
      showSuccess(res.data.message)
    }

    return res.data.data as T
  } catch (error) {
    if (error instanceof HttpError && error.code !== ApiStatus.unauthorized) {
      const showMsg = config.showErrorMessage !== false
      showError(error, showMsg)
    }
    return Promise.reject(error)
  }
}

/** API方法集合 */
const api = {
  get<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'GET' })
  },
  post<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'POST' })
  },
  put<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'PUT' })
  },
  del<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'DELETE' })
  },
  request<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>(config)
  }
}

export default api

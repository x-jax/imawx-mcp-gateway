/**
 * imawx-mcp 后台管理 API — 日志文件域
 *
 * 对应后端 {@code /api/sys/log-files/**}：
 * - GET    /api/sys/log-files/files             列出日志目录下所有文件（活跃 + 历史归档）
 * - GET    /api/sys/log-files/view              实时查看日志末尾 N 行（支持 level 过滤）
 * - GET    /api/sys/log-files/download          下载日志文件（防越权）
 * - WS     /ws/sys/log-files                    订阅日志文件增量
 *
 * 鉴权：servlet HttpSession + Cookie（不传 Authorization Header）。
 */
import axios from 'axios'
import request from '@/utils/http'

/**
 * 日志文件元数据（对应后端 LogFileVO）。
 */
export interface ImawxLogFile {
  /** 文件名（相对 {@code imawx.log.dir} 根目录，含 {@code archive/} 子目录前缀）。 */
  name: string
  /** 绝对路径。仅用于诊断，前端不要展示。 */
  absolutePath: string
  /** 文件大小（字节）。 */
  size: number
  /** 最后修改时间（epoch millis）。 */
  lastModified: number
  /** 是否 gzip 压缩（历史归档全是 true）。 */
  gzipped: boolean
  /** 目录类型：{@code active} 活跃日志 / {@code archive} 历史归档。 */
  category: 'active' | 'archive'
}

/**
 * 日志级别（与后端过滤参数对齐，精确匹配）。
 */
export type ImawxLogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

/**
 * 实时查看日志入参。
 */
export interface ImawxLogViewQuery {
  /** 日志文件名（相对 {@code imawx.log.dir}），{@code null} = 读活跃日志 {@code imawx-mcp-server.log}。 */
  file?: string
  /** 日志级别（精确匹配），{@code null} 不过滤。 */
  level?: ImawxLogLevel
  /** 尾部行数，默认 200，上限 10000。 */
  lines?: number
  /**
   * 字节偏移量（可空，>0 启用增量读模式）—— 前端"实时刷新"模式拿上次响应的
   * {@code fileSize} 当 {@code since},服务端从该位置读到末尾，避免每次全量读文件。
   * 2026-07-02 加。
   */
  since?: number
}

/**
 * 实时查看日志响应（对应后端 LogViewVO）。
 */
export interface ImawxLogView {
  /** 源文件名。 */
  file: string
  /** 日志条目（已按 level 过滤，已按文件倒序取末尾 lines 行）。 */
  lines: string[]
  /** 文件原始字节大小。 */
  fileSize: number
}

/**
 * 列出日志目录下所有文件（活跃 + 历史归档），按修改时间倒序。
 */
export function fetchLogFilesSys() {
  return request.get<ImawxLogFile[]>({
    url: '/api/sys/log-files/files'
  })
}

/**
 * 实时查看日志末尾 N 行（支持 level 过滤）。
 */
export function viewLogTailSys(params: ImawxLogViewQuery) {
  return request.get<ImawxLogView>({
    url: '/api/sys/log-files/view',
    params
  })
}

/**
 * 下载日志文件 —— 用原始 axios（不走统一响应包装），{@code responseType=blob} 让浏览器
 * 按 {@code Content-Disposition} 自动触发下载。
 */
export async function downloadLogFileSys(file: string): Promise<void> {
  const { VITE_API_URL, VITE_WITH_CREDENTIALS } = import.meta.env
  const res = await axios.get('/api/sys/log-files/download', {
    baseURL: VITE_API_URL,
    params: { file },
    responseType: 'blob',
    withCredentials: VITE_WITH_CREDENTIALS === 'true',
    // 不让 axios 把 4xx/5xx 当异常（我们下面看 blob 类型判断）
    validateStatus: () => true
  })
  const contentType = String(res.headers?.['content-type'] ?? '')
  // 后端 4xx 错误响应是 application/json（被错误处理切面统一包了），成功是 text/plain 或 application/octet-stream
  if (contentType.includes('application/json')) {
    const text = await (res.data as Blob).text()
    throw new Error(text || `下载失败（HTTP ${res.status}）`)
  }
  // 从 Content-Disposition 抓 filename（后端带 UTF-8 双份）
  const cd = String(res.headers?.['content-disposition'] ?? '')
  const m = /filename\*?=(?:UTF-8'')?["']?([^"';]+)/i.exec(cd)
  const filename = m ? decodeURIComponent(m[1]) : file.split('/').pop() || `log-${Date.now()}`
  triggerDownload(res.data as Blob, filename)
}

/**
 * 触发浏览器下载（临时 URL + a[download] click + revoke）。
 *
 * <p>异步释放 URL，避免 Safari 上 URL 还没走完就被 revoke。
 */
function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

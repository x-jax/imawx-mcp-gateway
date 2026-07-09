/**
 * imawx-mcp 后台管理 API — icon 域
 *
 * - GET /api/sys/icons/svgl  列出后端已下载的 svgl brand icon(启动时由 SvglIconDownloader 填充)
 *
 * 鉴权:跟其他 /api/sys/** 一样,走 servlet HttpSession + Cookie。
 */
import request from '@/utils/http'

/**
 * svgl 单个 brand icon 元数据(对应后端 SvglIconEntry record)。
 */
export interface SvglIconEntry {
  /** svgl 里的 brand title(英文),例 "GitHub" / "Stripe"。 */
  title: string
  /** 类别(可能多个),例 "AI" / ["AI", "Framework"]。 */
  category: string | string[]
  /**
   * 归一化后的相对路径(取 svgl 原始 route 的 light 端),例 "discord.svg"。
   * 前端用 `/svgl-icons/<route>` 取 SVG(WebMvcConfig 静态映射)。
   */
  route: string
  /** brand 官网 URL(可不展示)。 */
  url?: string
}

/**
 * 全量 svgl icon 列表。
 *
 * 启动下载未完成或失败时,后端返空数组,前端用空数组匹配 = 全 fallback transport icon。
 */
export function fetchSvglIconsSys() {
  return request.get<SvglIconEntry[]>({
    url: '/api/sys/icons/svgl'
  })
}

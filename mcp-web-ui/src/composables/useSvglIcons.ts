/**
 * svgl brand icon 单例加载 + serverName 匹配。
 *
 * <p>在卡片 / 详情页用 brand icon 替掉 transport icon(用户没匹配到的 fallback):
 * <ul>
 *   <li>首次调用时拉一次 {@code GET /api/sys/icons/svgl} 全量(~130KB,664 条),
 *       缓存到 module-scope ref(整个 SPA 生命周期内复用,不重拉)</li>
 *   <li>{@code matchByServerName(name)} 拆词 + includes 模糊匹配 svgl.title,
 *       返回命中的 entry(供 {@code <img src="/svgl-icons/<route>">} 用)</li>
 *   <li>拉取失败 / 启动未完成 → icons.value 是空数组,匹配全部返 null,
 *       调用方走 fallback transport icon</li>
 * </ul>
 *
 * <p>匹配规则:
 * <ol>
 *   <li>按非字母数字字符拆 serverName(空格 / 中文标点 / & / - 等都拆)</li>
 *   <li>过滤掉 ≤2 字符的短词(避免 "MCP" / "AI" 命中所有)</li>
 *   <li>每个 word 跟 svgl.title.toLowerCase 做 includes 匹配,
 *       第一个命中即返回(顺序按 svgl 原始 list 顺序,跟 GitHub stars 数大致相关)</li>
 *   <li>serverName 完全为空 / 全是短词 → 返 null</li>
 * </ol>
 *
 * <p>例:
 * <ul>
 *   <li>"GitHub MCP" → ["github", "mcp"] → 过滤 ["github"] → 命中 "GitHub"</li>
 *   <li>"我的 Stripe 支付 MCP" → ["我的", "stripe", "支付", "mcp"] → 过滤 ["我的", "stripe", "支付"] → 命中 "Stripe"</li>
 *   <li>"my custom service" → ["my", "custom", "service"] → 过滤 ["custom", "service"] → 通常不命中,fallback</li>
 * </ul>
 */
import { ref, type Ref } from 'vue'
import { fetchSvglIconsSys, type SvglIconEntry } from '@/api/sys/icons'

// module-scope 单例:整个 SPA 共享一个 list + 一个 promise,避免每个组件各自拉
const icons = ref<SvglIconEntry[]>([])
const loaded = ref(false)
let inflight: Promise<void> | null = null

/**
 * 拉全量 svgl icon(单例,多次调用复用同一 promise)。
 */
export function loadSvglIcons(force = false): Promise<void> {
  if (loaded.value && !force) return Promise.resolve()
  if (inflight && !force) return inflight
  inflight = (async () => {
    try {
      const list = await fetchSvglIconsSys()
      icons.value = list ?? []
      loaded.value = true
    } catch {
      // 拉失败保持空数组,匹配全部 fallback
      icons.value = []
      loaded.value = true
    } finally {
      inflight = null
    }
  })()
  return inflight
}

/**
 * 已加载的 svgl icon 列表(只读),给需要遍历的组件用。
 */
export function useSvglIcons(): Ref<SvglIconEntry[]> {
  // 首次访问触发加载(组件 mount 时),后续 mount 复用已加载 list
  if (!loaded.value && !inflight) {
    void loadSvglIcons()
  }
  return icons
}

/**
 * 按 serverName 匹配一个 svgl brand icon。
 *
 * <p>匹配规则(整词 + 短 title 优先 ranking,跟 svgl 顺序解耦):
 * <ol>
 *   <li>按非字母数字字符拆 serverName,过滤掉 ≤2 字符的短词(避免 "MCP" / "AI" 命中所有)</li>
 *   <li>对每个 svgl entry,把 title 也按非字母数字字符拆成单词集合</li>
 *   <li>任一 word ∈ entry.title 的单词集合 → 命中(整词匹配,避免 "service" 误中 "Services")</li>
 *   <li>多个 entry 命中时,按 score 取最高:
 *     <ul>
 *       <li>title 完全等于 word(整词且唯一词)→ +1000(核心 brand 优先)</li>
 *       <li>title 短优先 → +max(0, 100 - title.length)("GitHub" 6 字符 +94,"GitHub Copilot" 14 字符 +86)</li>
 *     </ul>
 *   </li>
 *   <li>综合:"GitHub MCP" 优先命中 "GitHub"(score 1094)而不是 "GitHub Copilot"(score 86)</li>
 * </ol>
 *
 * @returns 命中的 entry(用于 {@code <img src="/svgl-icons/<route>">});未命中返 null。
 */
export function matchByServerName(name: string | undefined | null): SvglIconEntry | null {
  if (!name) return null
  if (!loaded.value || icons.value.length === 0) return null

  const words = name
    .split(/[^a-zA-Z0-9]+/)
    .map((w) => w.toLowerCase())
    .filter((w) => w.length > 2)
  if (words.length === 0) return null

  let best: { entry: SvglIconEntry; score: number } | null = null
  for (const entry of icons.value) {
    const titleWords = entry.title
      .toLowerCase()
      .split(/[^a-zA-Z0-9]+/)
      .filter((w) => w.length > 0)
    const wordSet = new Set(titleWords)
    for (const w of words) {
      if (wordSet.has(w)) {
        // 整词命中:ranking 加分
        // 完全等于(整词且 title 只有一个词)→ +1000
        // 否则 → +0(仅靠 title 长度短加分)
        const exactMatch = titleWords.length === 1 && titleWords[0] === w
        const score = (exactMatch ? 1000 : 0) + Math.max(0, 100 - entry.title.length)
        if (!best || score > best.score) {
          best = { entry, score }
        }
        // 一个 word 命中就够了,跳到下一个 entry
        break
      }
    }
  }
  return best ? best.entry : null
}

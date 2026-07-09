/**
 * 根据容器宽 + item 数量 + 卡片最小宽,动态算每行多少列。
 *
 * <p>核心规则（取三者最小，再夹到 ≥ 1）:
 * <ol>
 *   <li>item 数量 <code>n</code>（列数不能比 item 多）</li>
 *   <li>每列宽 ≥ <code>minCardWidth</code>（<code>maxByMin = floor((w + gap) / (minCardWidth + gap))</code>）</li>
 *   <li>viewport 断点上限 <code>maxByBp</code>（按 <code>breakpoints</code> 匹配当前窗口宽）</li>
 * </ol>
 *
 * <p>断点匹配：取 <code>window.innerWidth >= b.minWidth</code> 中 <code>minWidth</code> 最大的一档。
 * 未传 <code>breakpoints</code> 或都不匹配 → <code>maxByBp = Infinity</code>（不封顶）。
 *
 * <p>用法:
 * ```ts
 * const gridRef = ref<HTMLElement | null>(null)
 * const cols = useCardColumns(gridRef, () => list.length, {
 *   minCardWidth: 280,
 *   gap: 12,
 *   padding: 32,
 *   // 大屏 5 / 中屏 4 / 小屏 3 / 移动 2
 *   breakpoints: [
 *     { minWidth: 1920, cols: 5 },
 *     { minWidth: 1280, cols: 4 },
 *     { minWidth: 768, cols: 3 },
 *     { minWidth: 0, cols: 2 }
 *   ]
 * })
 * // template:
 * <div ref="gridRef" :style="{ gridTemplateColumns: `repeat(${cols}, 1fr)` }">...</div>
 * ```
 */
import { ref, onMounted, onBeforeUnmount, watch, type Ref } from 'vue'

export interface CardColumnsBreakpoint {
  /** 触发该列数的最小 viewport 宽度（px）。从大到小排列，最后一档传 0 兜底。 */
  minWidth: number
  /** 该档允许的最大列数。 */
  cols: number
}

export interface CardColumnsOptions {
  /** 卡片最小宽度（px），低于此会触发换列。默认 280。 */
  minCardWidth?: number
  /** 卡片最大宽度（px），保留参数兼容；当前实现不直接使用（断点封顶已经限了列数）。 */
  maxCardWidth?: number
  /** 卡片间距（px）。默认 12。 */
  gap?: number
  /** 容器内边距（px，左右合计）。默认 32。 */
  padding?: number
  /** viewport 断点 → 列数映射。不传则按容器宽自由撑（无封顶）。 */
  breakpoints?: CardColumnsBreakpoint[]
}

export function useCardColumns(
  containerRef: Ref<HTMLElement | null>,
  itemCount: Ref<number> | (() => number),
  minCardWidthOrOptions: number | CardColumnsOptions = 280,
  legacyMaxCardWidth = 480,
  legacyGap = 12,
  legacyPadding = 32
): Ref<number> {
  // 兼容旧的 6 参位置参数签名
  const opts: CardColumnsOptions =
    typeof minCardWidthOrOptions === 'number'
      ? {
          minCardWidth: minCardWidthOrOptions,
          maxCardWidth: legacyMaxCardWidth,
          gap: legacyGap,
          padding: legacyPadding
        }
      : minCardWidthOrOptions

  const minCardWidth = opts.minCardWidth ?? 280
  const gap = opts.gap ?? 12
  const padding = opts.padding ?? 32
  const breakpoints = opts.breakpoints

  const cols = ref(1)
  let ro: ResizeObserver | null = null

  const getCount = (): number => {
    if (typeof itemCount === 'function') return itemCount()
    return itemCount.value
  }

  /**
   * 按当前 viewport 宽找断点匹配的列数上限。breakpoints 为空 → Infinity。
   */
  const maxByBreakpoint = (): number => {
    if (!breakpoints || breakpoints.length === 0) return Infinity
    const vw = window.innerWidth
    let best = -1
    for (const bp of breakpoints) {
      if (vw >= bp.minWidth && bp.minWidth > best) {
        best = bp.minWidth
      }
    }
    if (best < 0) return Infinity
    return breakpoints.find((b) => b.minWidth === best)!.cols
  }

  const calc = () => {
    const el = containerRef.value
    if (!el) return
    const w = el.clientWidth - padding
    if (w <= 0) {
      cols.value = 1
      return
    }
    const n = getCount()
    const maxByMin = Math.max(1, Math.floor((w + gap) / (minCardWidth + gap)))
    const maxByBp = maxByBreakpoint()
    const result = Math.min(n, maxByMin, maxByBp)
    cols.value = Math.max(1, result)
  }

  onMounted(() => {
    calc()
    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      // 容器尺寸变化（侧栏展开/折叠、卡片增删导致 grid 容器变宽变窄）
      ro = new ResizeObserver(() => calc())
      ro.observe(containerRef.value)
    } else {
      // fallback：旧浏览器没有 ResizeObserver，靠 window resize 间接触发
      window.addEventListener('resize', calc)
    }
    // 监听 viewport 变化：断点封顶依赖 window.innerWidth
    // ResizeObserver 只看容器不监听 viewport，所以这里必须挂一个 window resize
    // (同一 listener 重复 add 是幂等的，calc 跑 2 次不影响结果)
    window.addEventListener('resize', calc)
  })

  onBeforeUnmount(() => {
    if (ro) {
      ro.disconnect()
      ro = null
    }
    window.removeEventListener('resize', calc)
  })

  // 监听 item 数量变化(列表刷新时重算)
  if (typeof itemCount !== 'function') {
    watch(itemCount, () => calc())
  }

  return cols
}

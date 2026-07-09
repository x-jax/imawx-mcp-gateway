/**
 * 用户标签颜色映射 — 按 tag 字符串 hash 到 Element Plus 的 5 种 ElTag type。
 *
 * <p>设计要点：
 * <ul>
 *   <li>同名 tag 永远同色（djb2 hash 稳定）→ 用户记忆负担小，"生产" 一直是黄色</li>
 *   <li>5 色循环：primary(蓝) / success(绿) / warning(黄) / danger(红) / info(灰)
 *       —— 顺序按视觉强弱排，红色留给关键 tag(线上、危险、阻塞等)</li>
 *   <li>effect="light" —— 浅色背景 + 深色文字，密集卡片里不会太抢眼</li>
 *   <li>不需要响应式 — 纯函数。Vue 组件里 `:type="tagColor(t)"` 直接调</li>
 * </ul>
 *
 * <p>2026-07-01 加：替代之前的 type="info" + effect="plain"（灰边框白底，单调无区分度）。
 * 用户原话："标签给颜色啊，你这个太丑了"。
 *
 * @author Mavis
 * @since 2026-07-01
 */
const TAG_TYPES = ['primary', 'success', 'warning', 'danger', 'info'] as const

export type TagType = (typeof TAG_TYPES)[number]

/**
 * djb2 hash — 简单稳定,JavaScript 整数足够区分几千个 tag。
 * (Math.abs 是因为 JS 位运算会把大数转成 32-bit 有符号,h 可能溢出为负)
 */
function djb2(str: string): number {
  let h = 5381
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) + h + str.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

/**
 * tag 字符串 → ElTag type。同名 tag 永远返同一颜色。
 *
 * @param tag 用户标签字符串（已 trim）
 * @returns Element Plus ElTag type 之一：primary / success / warning / danger / info
 */
export function tagColor(tag: string): TagType {
  if (!tag) return 'info'
  return TAG_TYPES[djb2(tag) % TAG_TYPES.length]
}
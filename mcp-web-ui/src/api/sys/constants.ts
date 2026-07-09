/**
 * imawx-mcp 后台管理 API — 系统常量域
 *
 * 对应后端 SysConstantsController（GET /api/sys/constants）：
 * - 一次性返回所有"固定参数类型"枚举的下拉框选项
 * - key 是语义化常量名（loginMethods / userStatus / protocol / connectionStatus /
 *   invokeStatus / serverType）
 * - value 是 [{ value, label, key?, ext? }] 列表
 *
 * 鉴权：servlet HttpSession + Cookie（不传 Authorization Header）。
 *
 * 2026-07-01 重构:
 * - 字段从 {@code desc} 改成 {@code label}(更通用,跟 Element Plus ElOption label 对齐)
 * - value 类型从 string 改成 unknown(后端枚举的 value 可能是 Integer 也可能是 String)
 * - 新增 {@code key} 字段(业务分组 key,跨页面引用固定标识)
 * - 新增 {@code ext} 字段(扩展,例如 chip 颜色 / icon 名)
 * - 保留 {@code desc} 兼容老代码(读不到 label 时 fallback),逐步迁移后删除
 */
import request from '@/utils/http'

/**
 * 单个下拉框选项(对齐后端 DictOption record)。
 *
 * <ul>
 *   <li>{@code value}:业务值,JSON 原生类型(后端 Integer 或 String 都可能),
 *       前端按需 stringify 比较或 typeof 区分</li>
 *   <li>{@code label}:中文展示名,直接拿来做 ElOption label / chip 文本</li>
 *   <li>{@code key}:业务分组 key(可选),跨页面跨字段引用时用,
 *       比如 chip 显示要按 enum name 而不是 label 渲染时</li>
 *   <li>{@code ext}:扩展字段(可选),例如 {@code {color: '#ff0000'}} 给 chip 上色,
 *       或者 {@code {icon: 'ri:cloud-line'}} 给 chip 加 icon</li>
 *   <li>{@code desc}:兼容字段,老前端代码(2026-07-01 之前)还在读,
 *       读不到 label 才 fallback —— 后续迁移完成后删掉</li>
 * </ul>
 */
export interface ImawxOptionVo {
  value: unknown
  label: string
  desc?: string
  key?: string
  ext?: Record<string, unknown>
}

/**
 * 全部常量的 Map 结构(key 是常量名,value 是选项列表)。
 *
 * <p>目前 6 个 key(按需扩展):
 * <ul>
 *   <li>{@code loginMethods}:登录方式(password / sms / oauth / sso)</li>
 *   <li>{@code userStatus}:用户状态(1启用 / 0禁用)</li>
 *   <li>{@code protocol}:外部 MCP 传输协议(STDIO / SSE / HTTP)</li>
 *   <li>{@code connectionStatus}:外部 MCP 连接状态(DISCONNECTED / CONNECTED / FAILED)</li>
 *   <li>{@code invokeStatus}:调用日志状态(SUCCESS / FAILED / TIMEOUT)</li>
 *   <li>{@code serverType}:调用日志服务类型(EXTERNAL / DB / ALIYUN_DNS / ALIYUN_OSS / TENCENT_CLOUD / REDIS / KV_DATABASE)</li>
 * </ul>
 */
export type ImawxConstantsMap = Record<string, ImawxOptionVo[]>

/**
 * 拉所有系统常量下拉框。
 *
 * <p>应用启动时调一次,结果缓存到 {@code useConstants} 响应式 ref,
 * 后续所有 {@code <el-select :options="getOptions('xxx')" />} 直接读缓存。
 */
export function fetchConstantsSys() {
  return request.get<ImawxConstantsMap>({
    url: '/api/sys/constants'
  })
}

/**
 * 拉指定 key 的字典(单字典端点,2026-07-01 加)。
 *
 * <p>用得少 —— 主要场景是某个枚举变更后,前端增量刷新不用重启 SPA。
 * 99% 场景前端启动时拉一次 fetchConstantsSys() 就够。
 */
export function fetchConstantsByKeySys(key: string) {
  return request.get<ImawxOptionVo[]>({
    url: `/api/sys/constants/${key}`
  })
}

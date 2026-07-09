/**
 * imawx-mcp 后台管理 API — 系统配置域
 *
 * 对应 prd.md 第 6 章 6.1 后台管理 API：
 * - GET  /api/sys/system/config[?key=xxx]   获取配置（无参全量，带 key 单项）
 * - PUT  /api/sys/system/config            写入配置（覆盖式：不存在 insert，存在 update）
 *
 * 鉴权：servlet HttpSession + Cookie（不传 Authorization Header）。
 *
 * <p>当前用到的 key（详见 {@code McpSystemConfigService} 注释）：
 * <ul>
 *   <li>{@code mcp.global.enabled}：全局 MCP Server 开关（0=关闭 1=开启）</li>
 *   <li>{@code mcp.session.timeout-hours}：Session 超时小时数</li>
 *   <li>{@code mcp.tool.log.retention-days}：调用日志保留天数（默认 90）</li>
 *   <li>{@code mcp.security.stdio.allowed-commands}：STDIO 命令白名单</li>
 *   <li>{@code mcp.security.redact.keys}：审计日志脱敏字段名</li>
 *   <li>{@code mcp.security.redact.patterns}：审计日志脱敏正则</li>
 * </ul>
 */
import request from '@/utils/http'

/**
 * 系统配置项 VO（对应后端 McpSystemConfigVo）。
 */
export interface ImawxSystemConfig {
  configKey: string
  configValue: string
  description?: string
  /** ISO 8601，最近更新时间 */
  updatedAt?: string
}

/**
 * 系统配置项修改请求体（对应后端 McpSystemConfigUpdateDto）。
 */
export interface ImawxSystemConfigUpdate {
  configKey: string
  configValue: string
  description?: string
}

/**
 * 列出全部配置项（按 key 升序）。
 */
export function fetchSystemConfigListSys() {
  return request.get<ImawxSystemConfig[]>({
    url: '/api/sys/system/config'
  })
}

/**
 * 写配置(覆盖式)。
 */
export function putSystemConfigSys(payload: ImawxSystemConfigUpdate) {
  return request.put<void>({
    url: '/api/sys/system/config',
    data: payload,
    showSuccessMessage: true
  })
}

/**
 * 触发 AuthProperties 重新从 DB 读最新值(2026-07-02 加)——
 * admin 在 UI 改了 mcp.auth.totp-enabled 后点"应用变更"调这个端点,后端立即生效,不需要重启。
 */
export function postSystemConfigRefreshSys() {
  return request.post<void>({
    url: '/api/sys/system/config/refresh',
    showSuccessMessage: true
  })
}

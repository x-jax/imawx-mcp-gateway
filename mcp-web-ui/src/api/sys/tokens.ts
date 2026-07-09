/**
 * imawx-mcp 后台管理 API — API Token 域(2026-07-01 加;2026-07-03 加授权)。
 *
 * 对应后端 {@code SysTokenController} ({@code /api/sys/tokens/**}):
 * - POST   /api/sys/tokens                       创建 token(返回明文,仅此一次)
 * - GET    /api/sys/tokens                       列出当前用户的所有 token(不含 hash,含授权范围)
 * - POST   /api/sys/tokens/{id}/revoke           撤销 token(软删)
 * - DELETE /api/sys/tokens/{id}                 硬删 token
 * - PUT    /api/sys/tokens/{id}/authorizations   单独更新 token 授权(2026-07-03 加)
 *
 * 鉴权:session 必须登录(用户管理自己的 token)。
 * 生成的明文 token 用于调用其他 /api/** 接口,带 Authorization: Bearer imwx_xxx 头。
 */
import request from '@/utils/http'

/** Token 状态(对应后端 McpApiTokenVO.status,string 序列化)。 */
export type ImawxApiTokenStatus = 'active' | 'revoked'

/**
 * Token 授权模式(2026-07-03 加)。
 * <ul>
 *   <li>0=全开放 —— authorized 列表为空也能调任何 backend/tool</li>
 *   <li>1=严格 —— 只能调 authorized 列表里的 backend/tool,否则 403</li>
 * </ul>
 */
export type ImawxApiTokenRestrictMode = 0 | 1

/**
 * Token 授权 tool 引用(2026-07-03 加)——
 * (backendId, toolName) 二元组,token 被授权调用的具体 tool。
 */
export interface ImawxApiTokenToolRef {
  /** backend_id 字符串别名(bk_xxx)。 */
  backendId: string
  /** tool 原始名(不包含 method 前缀,如 stream_text / execute_sql)。 */
  toolName: string
}

/** Token 列表 VO(不含 hash 字段,2026-07-03 加授权范围)。 */
export interface ImawxApiToken {
  id: string
  name: string
  /** 明文前 16 位(用于列表展示识别,不暴露完整)。 */
  tokenPrefix: string
  scopes: string[]
  /** IP/CIDR 白名单,空数组表示不限制。 */
  ipWhitelist?: string[]
  expiresAt?: string
  lastUsedAt?: string
  lastUsedIp?: string
  status: ImawxApiTokenStatus
  createdAt: string
  revokedAt?: string
  /** 授权模式(2026-07-03 加)—— 0=全开放 / 1=严格 */
  restrictMode: ImawxApiTokenRestrictMode
  /** 授权可访问的 backend 列表(2026-07-03 加)—— backend_id 字符串别名集合。 */
  authorizedBackends: string[]
  /** 授权可调用的具体 tool 列表(2026-07-03 加)。 */
  authorizedTools: ImawxApiTokenToolRef[]
}

/**
 * 创建 token 响应 —— <b>唯一一次能看到明文 plaintext 的地方</b>。
 *
 * <p>前端需要做醒目的"复制"按钮 + "关闭后不再显示"提示。
 */
export interface ImawxApiTokenCreated {
  id: string
  name: string
  tokenPrefix: string
  /** 明文 token —— 立刻复制保存,关掉弹窗后不再出现。 */
  plaintext: string
  expiresAt?: string
  createdAt: string
}

/**
 * 创建 token 入参(2026-07-03 加授权字段)。
 */
export interface ImawxApiTokenCreatePayload {
  name: string
  scopes?: string[]
  /** ISO 字符串,可选(null = 永不过期)。 */
  expiresAt?: string | null
  /** IP/CIDR 白名单,空数组表示不限制。 */
  ipWhitelist?: string[]
  /** 授权模式。null = 默认 1(严格,新 token 默认走严格) */
  restrictMode?: ImawxApiTokenRestrictMode
  /** 授权可访问的 backend 列表(bk_xxx) */
  authorizedBackends?: string[]
  /** 授权可调用的具体 tool 列表 */
  authorizedTools?: ImawxApiTokenToolRef[]
}

/** 创建 token(返回明文)。 */
export function createMyTokenSys(payload: ImawxApiTokenCreatePayload) {
  return request.post<ImawxApiTokenCreated>({
    url: '/api/sys/tokens',
    data: payload
  })
}

/** 列出当前用户的所有 token(含授权范围)。 */
export function fetchMyTokensSys() {
  return request.get<ImawxApiToken[]>({
    url: '/api/sys/tokens'
  })
}

/** 撤销 token(软删)。 */
export function revokeMyTokenSys(id: string) {
  return request.post<void>({
    url: `/api/sys/tokens/${id}/revoke`
  })
}

/** 硬删 token。 */
export function hardDeleteMyTokenSys(id: string) {
  return request.del<void>({
    url: `/api/sys/tokens/${id}`
  })
}

/**
 * 单独更新 token 授权(2026-07-03 加)—— 不重建 token,纯改授权范围。
 *
 * <p>三段式 patch:每个字段 null = 不改,非 null = 覆盖。
 */
export interface ImawxApiTokenAuthorizationsUpdatePayload {
  restrictMode?: ImawxApiTokenRestrictMode
  ipWhitelist?: string[]
  authorizedBackends?: string[]
  authorizedTools?: ImawxApiTokenToolRef[]
}
export function updateMyTokenAuthorizationsSys(id: string, payload: ImawxApiTokenAuthorizationsUpdatePayload) {
  return request.put<void>({
    url: `/api/sys/tokens/${id}/authorizations`,
    data: payload
  })
}

/**
 * 当前用户可授权 backend + tool 列表(2026-07-03 加)——
 * 对应后端 GET /api/sys/tokens/available-backends。
 *
 * <p>授权编辑 UI 据此渲染「backend / tool 二级勾选」:
 * 整个 backend 授权,还是精确到 tool。
 */
export interface ImawxApiTokenAvailableBackend {
  backendId: string
  serverName: string
  transportType: string
  enabled: number
  tools: ImawxApiTokenAvailableTool[]
}
export interface ImawxApiTokenAvailableTool {
  name: string
  description?: string
  disabled?: boolean
}
export function fetchAvailableBackendsSys() {
  return request.get<ImawxApiTokenAvailableBackend[]>({
    url: '/api/sys/tokens/available-backends'
  })
}

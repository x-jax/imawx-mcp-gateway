/**
 * imawx-mcp 后台管理 API — 外部 MCP Server 管理域
 *
 * 对应 prd.md 第 6 章 6.1 后台管理 API（外部 MCP 部分）：
 * - GET    /api/sys/mcp-proxy                  分页查询
 * - GET    /api/sys/mcp-proxy/{id}             详情
 * - POST   /api/sys/mcp-proxy                  新增
 * - PUT    /api/sys/mcp-proxy/{id}             编辑
 * - DELETE /api/sys/mcp-proxy/{id}             删除
 * - POST   /api/sys/mcp-proxy/{id}/test        测试连接 + 列出 Tool
 * - POST   /api/sys/mcp-proxy/{id}/enable      启用（注册 Tool 到本地 MCP Server）
 * - POST   /api/sys/mcp-proxy/{id}/disable     禁用
 * - POST   /api/sys/mcp-proxy/{id}/sync        同步远端 Tool 列表到本地快照
 * - GET    /api/sys/mcp-proxy/{id}/tools       读已同步的快照 Tool 列表
 * - PUT    /api/sys/mcp-proxy/{id}/tools/{toolName}/disabled  切单个 tool 启用/禁用
 * - POST   /api/sys/mcp-proxy/{id}/tools/{toolName}/test      测试单个 tool（空参数 callTool）
 *
 * 鉴权：servlet HttpSession + Cookie。
 */
import request from '@/utils/http'

/**
 * MCP 传输类型 —— 2026-07-06 重构：AUTO 已删除。
 *
 * <ul>
 *   <li>外部 MCP 接入:HTTP / SSE / STDIO 三选一（user 必须明确 transportType）</li>
 *   <li>网关内置 provider:DB / MYSQL / POSTGRESQL / ORACLE / SQLSERVER / REDIS /
 *       MONGODB / ELASTICSEARCH / ALIYUN_DNS / ALIYUN_OSS / OPENAPI / SSH</li>
 *   <li>STREAMABLE_HTTP 枚举保留供旧数据/API 调用,前端不再 radio 暴露</li>
 * </ul>
 */
export type ImawxMcpTransport =
  | 'HTTP' | 'SSE' | 'STDIO' | 'STREAMABLE_HTTP'
  | 'DB' | 'MYSQL' | 'POSTGRESQL' | 'ORACLE' | 'SQLSERVER'
  | 'KV_DATABASE' | 'REDIS' | 'MONGODB' | 'ELASTICSEARCH'
  | 'ALIYUN_DNS' | 'ALIYUN_OSS' | 'OPENAPI' | 'SSH' | 'DRONE'

/** 外部 MCP 连接状态（对应后端 {@code ConnectionStatusEnum} JSON 序列化的字符串，"双形态"枚举）。 */
export type ImawxConnectionStatus = 'DISCONNECTED' | 'CONNECTED' | 'FAILED'

/**
 * 外部 MCP Server VO（对应后端 McpExternalServerVo）。
 *
 * <p>{@code authToken} 字段后端用 {@code @JsonIgnore}，前端不会收到（即使有也是后端 bug）。
 *
 * <p>{@code id} 是 string：后端用 {@code ToStringSerializer} 强制把雪花算法生成的 19 位 Long 序列化成字符串，
 * 避免 JavaScript Number.MAX_SAFE_INTEGER(2^53-1) 精度丢失（实测 2070768298896953346 → 2070768298896953300，
 * 导致前端拿错 id 调 /test /sync → 404"找不到外部 MCP Server"）。
 * 传给后端时直接拼 URL 即可（{@code @PathVariable Long} Spring 自动从 string 转 Long）。
 */
export interface ImawxMcpProxy {
  id: string
  /** 业务 backendId，例如 db_3 / bk_xxx。内部数据库行需要用它反解数据库连接 id。 */
  backendId?: string
  serverName: string
  /** 后端 {@code McpTransportEnum} 序列化的 value。 */
  transportType: ImawxMcpTransport
  /** URL（HTTP/SSE）或命令（STDIO） */
  endpoint: string
  /** STDIO 的 args / env（JSON 字符串）；HTTP/SSE 通常为空 */
  extraConfig?: string
  /** 用户备注(可空,最长 512 字符) */
  remark?: string
  /** 后端 {@code ConnectionStatusEnum} 序列化的字符串（DB 存的是整数 code,JSON 是字符串）。 */
  status: ImawxConnectionStatus
  lastCheckAt?: string
  /** 最近一次测试连接失败的错误信息(可空) */
  lastError?: string
  /**
   * 最近一次 Tool 快照同步失败的错误信息(可空)。
   *
   * <p>跟 {@code lastError} 区分:测试失败 → lastError,同步失败 → lastSyncError。
   * 卡片 desc 优先级:有 lastSyncError 时显示「同步失败:XXX」,否则看 lastError 显示「测试失败:XXX」。
   */
  lastSyncError?: string
  /** 1=启用 0=禁用 */
  enabled: number
  /** 最近一次 Tool 快照同步成功时间（没同步过就是 undefined） */
  lastSyncAt?: string
  /** Tool 数量。仅用于监控聚合等统计展示，列表卡片不再展示，避免和快照语义混淆。 */
  toolCount?: number
  /**
   * 用户标签(2026-07-01 加)。后端 mcp_backend.tags 是 JSON 列,
   * VO 序列化为字符串数组。未设置时是 undefined,卡片不渲染标签区。
   * 每个 tag 1-32 字符,数量 0-20 个(后端 DTO @Size 校验)。
   */
  tags?: string[]
  createdAt: string
  updatedAt: string
  /**
   * 创建人 userId(2026-07-03 加)—— 后端 mcp_backend 拆 userId 隔离后,
   * 不再用 userId 强绑所有权,这里只记是谁创建的。UserCenter 当前 user 拿
   * displayName 渲染列表卡片。
   */
  createdBy?: number
  /** 最近修改人 userId(2026-07-03 加)—— CRUD update 时回填。 */
  updatedBy?: number
  /** 测试连接时拿到的 Tool 列表（详情 / test 接口才填） */
  tools?: ImawxMcpProxyToolPreview[]
}

/**
 * 同步结果（POST /api/sys/mcp-proxy/{id}/sync 返回）。
 *
 * <p>对齐后端 {@code McpToolSyncService.SyncOutcome}：
 * <ul>
 *   <li>{@code success=true}: 同步成功。</li>
 *   <li>{@code success=false}: 同步失败(远端挂了 / endpoint 错 / 协议不匹配),errorCode + errorMessage 描述原因。
 *       HTTP 仍返 200(success 走 data 透出),UI 自己按 success 字段弹 message,不靠全局 4xx/5xx。</li>
 * </ul>
 *
 * <p>卡片底部"同步"按钮拿到结果后:
 * <ul>
 *   <li>成功 → ElMessage.success('同步成功') + reload 列表(desc 重新显示同步时间)</li>
 *   <li>失败 → ElMessage.error('同步失败: ' + errorMessage) + reload 列表(desc 显示红色「同步失败:XXX」)</li>
 * </ul>
 */
export interface ImawxMcpProxySyncResult {
  success: boolean
  toolCount: number
  errorCode?: string
  errorMessage?: string
}

/**
 * 2026-07-06 重构：自动探测结果已删除。
 *
 * <p>AUTO transport 选项已从表单移除，user 必须明确选 HTTP / SSE / STDIO。
 * 后端 {@code /api/sys/mcp-proxy/probe} 端点和 {@code McpClientFactory.ProbeResult}
 * 同步删除 —— 接口不再返回探测结果，前端也不需要这个 type。
 */

export interface ImawxMcpProxyToolPreview {
  name: string
  originalName?: string
  description?: string
  /** JSON Schema 字符串（原始字符串，前端按需 JSON.parse） */
  inputSchema?: string
  /**
   * 是否禁用。默认 false（启用）。
   *
   * <p>禁用后该 tool 不参与 token 分配、不暴露给大模型 listTools。
   * 老 snapshot 没有此字段时反序列化为 false（兼容）。
   */
  disabled?: boolean
}

export interface ImawxMcpToolOverride {
  id?: string
  backendId?: string
  toolName: string
  displayName?: string
  description?: string
  inputSchema?: string
}

/**
 * 调单个 Tool 的返回结果(对应后端 {@code McpSchema.CallToolResult} record)。
 *
 * <p>字段含义跟 MCP SDK 一致 ——
 * <ul>
 *   <li>{@code content}: 多段内容列表,实际类型由 {@code type} 字段决定
 *     (text / image / audio / resource)。前端按 type 分发渲染。</li>
 *   <li>{@code isError}: true 表示工具返回了错误(协议层成功,但业务失败)。</li>
 *   <li>{@code structuredContent}: MCP 2.0 标准的结构化输出字段(任意 JSON),有值时优先展示。</li>
 * </ul>
 *
 * <p>2026-06-28 加:抽屉里每个 tool 卡片右侧的「测试」按钮调此 API,
 * 把外部 MCP Server 的原始响应透出来给 admin 看。
 */
export interface ImawxMcpCallToolResult {
  content?: ImawxMcpCallToolContent[]
  isError?: boolean
  /** MCP 2.0 标准结构化输出 —— 有值时优先展示,代表 server 自己已经按 schema 输出 typed JSON。 */
  structuredContent?: unknown
}

/**
 * MCP 协议 content block 的统一类型(对应后端透传的 {@code List<McpSchema.Content>})。
 *
 * <p>MCP 协议定义 4 种 content block:
 * <ul>
 *   <li>{@code text}: 含 {@code text: string}</li>
 *   <li>{@code image}: 含 {@code data: base64, mimeType: string}</li>
 *   <li>{@code audio}: 含 {@code data: base64, mimeType: string}</li>
 *   <li>{@code resource}: 含 {@code resource: { uri, mimeType, text?|blob? }}</li>
 * </ul>
 *
 * <p>前端不强类型(全 optional),按 {@code type} 字段分发。{@code annotations} 是可选的
 * audience/priority 元数据,前端暂不展示。
 */
export interface ImawxMcpCallToolContent {
  type?: 'text' | 'image' | 'audio' | 'resource' | string
  text?: string
  data?: string
  mimeType?: string
  uri?: string
  /** resource 类型专属:内嵌的 ResourceContents */
  resource?: {
    uri?: string
    mimeType?: string
    /** 文本资源(配置 / 文档等) */
    text?: string
    /** 二进制资源的 base64 */
    blob?: string
  }
  annotations?: {
    audience?: ('user' | 'assistant')[]
    priority?: number
  }
  _meta?: Record<string, unknown>
}

/**
 * 外部 MCP Server 分页响应。
 */
export interface ImawxMcpProxyPage {
  records: ImawxMcpProxy[]
  total: number
  pageNum: number
  pageSize: number
}

/**
 * 查询条件。
 */
export interface ImawxMcpProxyQuery {
  serverName?: string
  transportType?: ImawxMcpTransport
  enabled?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 新增 / 编辑请求体。
 *
 * <p>新增时 {@code remark} 可空;编辑时 {@code remark} 永远同步当前 UI 的值
 * (空字符串 = 清空备注),由后端 service.update 端统一 set,不区分"不改"。
 *
 * <p>{@code id} 是 string：见 {@link ImawxMcpProxy.id} 的说明（雪花 ID 必须走 string）。
 */
export interface ImawxMcpProxyPayload {
  id?: string
  serverName: string
  transportType: ImawxMcpTransport
  endpoint: string
  authToken?: string
  extraConfig?: string
  /**
   * 用户标签数组(2026-07-01 加)。undefined = 不传,后端不动;
   * 空数组 = 清空;非空数组 = 覆盖。
   * 后端 DTO @Size(max=20),每个元素 @Size(max=32)。
   */
  tags?: string[]
  remark?: string
}

export interface ImawxMcpProxyTemplate {
  key: string
  name: string
  description: string
  transportType: ImawxMcpTransport
  endpoint: string
  providerType?: 'DB' | 'KV_DATABASE' | 'HTTP_MCP' | 'ALIYUN_DNS' | 'ALIYUN_OSS' | 'REDIS' | 'MONGODB' | 'ELASTICSEARCH' | 'OPENAPI' | 'SSH' | 'DRONE'
  extraConfig?: string
  tags?: string[]
  securityNote?: string
}

export function fetchMcpProxyTemplatesSys() {
  return request.get<ImawxMcpProxyTemplate[]>({
    url: '/api/sys/mcp-proxy/templates',
    showErrorMessage: false
  })
}

/**
 * 分页查询。
 */
export function fetchMcpProxyPageSys(params: ImawxMcpProxyQuery) {
  return request.get<ImawxMcpProxyPage>({
    url: '/api/sys/mcp-proxy',
    params
  })
}

/**
 * 详情。
 */
export function fetchMcpProxyDetailSys(id: string) {
  return request.get<ImawxMcpProxy>({
    url: `/api/sys/mcp-proxy/${id}`
  })
}

/**
 * 新增响应：新建 id + 自动同步结果。
 *
 * <p>2026-07-01 改：之前只返 {@code {id}}，现在后端 create 后会自动调一次同步（拉远端 tool 列表），
 * 把 sync 结果也带回。前端按 sync.success 弹不同 message：
 * <ul>
 *   <li>success=true → 「保存成功，N 个 Tool」</li>
 *   <li>success=false → 「保存成功但同步失败:XXX」（黄色，records 已落库可手动重试）</li>
 * </ul>
 */
export interface ImawxMcpProxyCreateResult {
  id: string
  sync: ImawxMcpProxySyncResult
}

/**
 * 新增。
 */
export function createMcpProxySys(payload: ImawxMcpProxyPayload) {
  return request.post<ImawxMcpProxyCreateResult>({
    url: '/api/sys/mcp-proxy',
    data: payload,
    showSuccessMessage: false
  })
}

/**
 * 编辑。
 */
export function updateMcpProxySys(id: string, payload: ImawxMcpProxyPayload) {
  return request.put<void>({
    url: `/api/sys/mcp-proxy/${id}`,
    data: payload,
    showSuccessMessage: true
  })
}

/**
 * 删除。
 */
export function deleteMcpProxySys(id: string) {
  return request.del<void>({
    url: `/api/sys/mcp-proxy/${id}`,
    showSuccessMessage: true
  })
}

/**
 * 测试连接 + 列 Tool。
 *
 * 返回 Tool 预览列表（包含 name / description / inputSchema）。
 */
export function testMcpProxySys(id: string) {
  return request.post<ImawxMcpProxyToolPreview[]>({
    url: `/api/sys/mcp-proxy/${id}/test`,
    showSuccessMessage: true
  })
}

/**
 * 一次性探活（不落库、不写 cache）—— 给新增/编辑表单的「测试」按钮用。
 *
 * <p>2026-06-28 加：表单要求测试通过才能保存。新建记录没 ID 时不能用 {@link testMcpProxySys}，
 * 编辑表单也用这个 —— 表单字段变化时不能命中 cache 里的旧 client。
 *
 * <p>请求体 schema 跟新增一致（{@link ImawxMcpProxyPayload}）；响应是 tool 预览列表。
 * 失败由全局异常处理返 4xx,前端按 showSuccessMessage: false 走,自己弹 message。
 */
export function validateMcpProxySys(payload: ImawxMcpProxyPayload) {
  return request.post<ImawxMcpProxyToolPreview[]>({
    url: '/api/sys/mcp-proxy/validate',
    data: payload,
    showSuccessMessage: false
  })
}

/**
 * 2026-07-06 重构：自动探测端点已删除。
 *
 * <p>AUTO transport 选项移除后，user 必须明确选 HTTP / SSE / STDIO。前端表单不再
 * 需要"先探测再选 transport"的过渡 UX，直接让 user 在 radio 里挑。
 */

/**
 * 启用（注册 Tool 到本地 MCP Server）。
 */
export function enableMcpProxySys(id: string) {
  return request.post<void>({
    url: `/api/sys/mcp-proxy/${id}/enable`,
    showSuccessMessage: true
  })
}

/**
 * 禁用。
 */
export function disableMcpProxySys(id: string) {
  return request.post<void>({
    url: `/api/sys/mcp-proxy/${id}/disable`,
    showSuccessMessage: true
  })
}

/**
 * 同步 Tool 列表。
 *
 * <p>外部 MCP 会实时连远端 listTools 并写入本地 tool 表；内置 provider
 * 只刷新连接状态/同步时间，Tool 列表由 provider 运行时实时返回，不依赖快照。
 *
 * <p>返回 {@link ImawxMcpProxySyncResult} 让前端拿 success 自己判断:
 * <ul>
 *   <li>success=true → 弹「同步成功」</li>
 *   <li>success=false → 弹「同步失败: errorMessage」(红色,带原因)</li>
 * </ul>
 * showSuccessMessage:false —— 后端 SyncOutcome 已带 success 字段,request util 默认
 * "操作成功" 不准确且会双弹,前端按业务结果自己控 message。
 */
export function syncMcpProxySys(id: string) {
  return request.post<ImawxMcpProxySyncResult>({
    url: `/api/sys/mcp-proxy/${id}/sync`,
    showSuccessMessage: false
  })
}

/**
 * 读取 Tool 列表。
 *
 * <p>外部 MCP 返回已同步到本地 tool 表的列表；内置 provider
 * 直接返回当前代码定义的 Tool 列表。数据库类 Tool 执行时会实时查目标库元数据。
 */
export function fetchMcpProxyToolsSys(id: string) {
  return request.get<ImawxMcpProxyToolPreview[]>({
    url: `/api/sys/mcp-proxy/${id}/tools`
  })
}

export function fetchMcpToolOverridesSys(id: string) {
  return request.get<ImawxMcpToolOverride[]>({
    url: `/api/sys/mcp-proxy/${id}/tools/overrides`
  })
}

export function saveMcpToolOverrideSys(id: string, payload: ImawxMcpToolOverride) {
  const toolName = encodeURIComponent(payload.toolName)
  return request.put<void>({
    url: `/api/sys/mcp-proxy/${id}/tool-overrides/${toolName}`,
    data: payload,
    showSuccessMessage: true
  })
}

/**
 * 切换某个 tool 的 disabled 状态（落进 tools_snapshot JSON 的 disabled 字段）。
 *
 * 2026-07-01 移除:抽屉 UI 不再展示每个 tool 的启用开关,前端不再调用此接口。
 * 后端 `/tools/{name}/disabled` 端点本身保留(给运营 API 用),仅前端调用方删除。
 */
// export function toggleMcpProxyToolDisabledSys(
//   id: string,
//   toolName: string,
//   disabled: boolean
// ) {
//   return request.put<void>({
//     url: `/api/sys/mcp-proxy/${id}/tools/${encodeURIComponent(toolName)}/disabled`,
//     params: { disabled },
//     showSuccessMessage: false
//   })
// }

/**
 * 测试单个 tool（admin 调试用）：按 args 调一次 callTool,把外部 MCP Server 的原始响应透出。
 *
 * <p>2026-06-28 加：抽屉里每个 tool 卡片右侧的「测试」按钮走这个端点。
 * 前端解析 tool 的 {@code inputSchema} 生成表单,admin 填完点执行 → 传 args 给后端 → 转发给远端。
 * 不传 args 也行(后端走空 Map,远端自己报缺失)。
 *
 * <p>注意:这次 success message 由前端自己弹(在 dialog 里展示),
 * 所以这里 {@code showSuccessMessage: false} 避免重复。
 */
export function testMcpProxyToolSys(id: string, toolName: string, args?: Record<string, unknown>) {
  return request.post<ImawxMcpCallToolResult>({
    url: `/api/sys/mcp-proxy/${id}/tools/${encodeURIComponent(toolName)}/test`,
    data: { args: args ?? null },
    showSuccessMessage: false
  })
}

// ===== 流式调用（admin 调试看实时 logging / progress + 最终 result） =====

/**
 * 流式事件载荷。
 *
 * <p>2026-07-02 加:后端 {@code POST /test-stream} 端点通过 SSE 实时推这些 event:
 * <ul>
 *   <li>{@code logging} —— MCP 2.0 notifications/message;
 *     payload:{level, logger, data}</li>
 *   <li>{@code progress} —— MCP 2.0 notifications/progress;
 *     payload:{progress, total, message}</li>
 *   <li>{@code result} —— callTool 返的 final content,跟普通 /test 端点的 body 同</li>
 *   <li>{@code error} —— callTool 异常; payload:{message}</li>
 * </ul>
 */
export type ImawxMcpToolStreamEvent =
  | {
      type: 'logging'
      level: string
      logger?: string
      data: unknown
    }
  | {
      type: 'progress'
      progress: number
      total?: number
      message?: string
    }
  | {
      type: 'result'
      content?: ImawxMcpCallToolContent[]
      isError?: boolean
      structuredContent?: unknown
    }
  | {
      type: 'error'
      message: string
    }

/**
 * 流式调用 handlers —— 按事件类型分别回调,前端订阅关心的就行。
 */
export interface ImawxMcpToolStreamHandlers {
  onLogging?: (e: Extract<ImawxMcpToolStreamEvent, { type: 'logging' }>) => void
  onProgress?: (e: Extract<ImawxMcpToolStreamEvent, { type: 'progress' }>) => void
  onResult?: (e: Extract<ImawxMcpToolStreamEvent, { type: 'result' }>) => void
  onError?: (e: Extract<ImawxMcpToolStreamEvent, { type: 'error' }>) => void
  /** 网络层错误（fetch reject / reader 异常）—— 跟 server 推的 error 事件区分。 */
  onNetworkError?: (err: Error) => void
}

/**
 * 流式调用句柄 —— 调用方保留用于取消（dialog 关闭 / 用户停止时）。
 */
export interface ImawxMcpToolStreamHandle {
  cancel: () => void
}

/**
 * 流式测试单个 tool —— 后端通过 SSE 实时推 logging/progress notification + final result。
 *
 * <p>2026-06-28 加,2026-07-02 重写:之前流式 radio 是死代码(后端没实现 test-stream 端点);
 * 现在后端真实现了,边流边推 event,前端用 fetch + ReadableStream 边读边追加 UI。
 *
 * <p>跟 {@link testMcpProxyToolSys} 区别:不"等所有 chunk 完一次性返"。
 * 100 个 chunk × 5 秒 = 8 分钟,普通端点会超时;流式端点持续推 SSE event,前端立刻看到。
 *
 * <p>鉴权:复用项目 cookie / token 鉴权 —— fetch 默认 same-origin 带 cookie;跨域由 baseURL 控制。
 */
export function testMcpProxyToolStreamSys(
  id: string,
  toolName: string,
  args: Record<string, unknown> | undefined,
  handlers: ImawxMcpToolStreamHandlers
): ImawxMcpToolStreamHandle {
  const controller = new AbortController()

  // 拼 URL —— 与 axios 同源,去掉 baseURL 末尾的 "/" 防 protocol-relative URL
  const rawBaseURL = (import.meta.env.VITE_API_URL as string | undefined) ?? ''
  const baseURL = rawBaseURL.replace(/\/+$/, '')
  const url = `${baseURL}/api/sys/mcp-proxy/${id}/tools/${encodeURIComponent(toolName)}/test-stream`

  void (async () => {
    let response: Response
    try {
      response = await fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream'
        },
        body: JSON.stringify({ args: args ?? null }),
        signal: controller.signal
      })
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      handlers.onNetworkError?.(err as Error)
      return
    }

    if (!response.ok || !response.body) {
      let msg = `HTTP ${response.status}`
      try {
        const text = await response.text()
        const json = JSON.parse(text) as { message?: string }
        if (json.message) msg = json.message
      } catch {
        // 忽略 —— 兜底用 HTTP 状态码
      }
      handlers.onNetworkError?.(new Error(msg))
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE 协议以空行分隔 event —— 切分后逐条解析
        // 一条完整 event 形如: "event: logging\ndata: {...}\n\n"
        let boundary: number
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const rawEvent = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const parsed = parseSseEvent(rawEvent)
          if (!parsed) continue
          dispatchStreamEvent(parsed, handlers)
        }
      }
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      handlers.onNetworkError?.(err as Error)
    }
  })()

  return {
    cancel: () => controller.abort()
  }
}

/**
 * 解析单条 SSE event (形如 {@code "event: logging\ndata: {...}"} 或 {@code "data: ..."})。
 */
function parseSseEvent(raw: string): { event: string; data: string } | null {
  let event = 'message'
  let data = ''
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const idx = line.indexOf(':')
    if (idx === -1) continue
    const field = line.slice(0, idx).trim()
    let value = line.slice(idx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data += value
  }
  if (!data && event === 'message') return null
  return { event, data }
}

/**
 * 把 SSE event 分发到对应 handler —— TS 自动按 type 收窄。
 */
function dispatchStreamEvent(
  parsed: { event: string; data: string },
  handlers: ImawxMcpToolStreamHandlers
): void {
  const { event, data } = parsed
  let payload: unknown
  try {
    payload = data ? JSON.parse(data) : {}
  } catch {
    return // 损坏的 SSE 数据忽略 —— 不让一个坏 event 把流搞挂
  }
  switch (event) {
    case 'logging':
      handlers.onLogging?.(payload as Extract<ImawxMcpToolStreamEvent, { type: 'logging' }>)
      break
    case 'progress':
      handlers.onProgress?.(payload as Extract<ImawxMcpToolStreamEvent, { type: 'progress' }>)
      break
    case 'result':
      handlers.onResult?.(payload as Extract<ImawxMcpToolStreamEvent, { type: 'result' }>)
      break
    case 'error':
      handlers.onError?.({
        type: 'error',
        message:
          (payload as { message?: string }).message ?? '未知错误'
      })
      break
  }
}
// 普通模式（testMcpProxyToolSys）+ Result 区已能正确展示 text/image/audio/resource/embeddedResource。

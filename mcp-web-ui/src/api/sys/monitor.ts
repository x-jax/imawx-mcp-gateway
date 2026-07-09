/**
 * imawx-mcp 后台管理 API — 调用监控域(2026-07-01 重写)。
 *
 * <p>对应后端 {@code SysCallLogController} ({@code /api/sys/call-logs}):
 * <ul>
 *   <li>{@code GET /api/sys/call-logs} —— 分页查询,字段透传 {@code McpCallLogQueryDTO}</li>
 *   <li>{@code GET /api/sys/call-logs/stats} —— 聚合统计(今日 / 自定义时间段)</li>
 *   <li>{@code GET /api/sys/call-logs/{id}} —— 单条详情</li>
 * </ul>
 *
 * <p>字段对齐后端 {@code McpCallLogVO}(2026-07-01 重构):
 * <ul>
 *   <li>{@code id} 是后端 DB BIGINT 自增,通过 StringSerializer 输出(避免 JS 精度丢失)</li>
 *   <li>{@code traceId} 链路追踪 ID —— 跨服务排障用</li>
 *   <li>{@code backendId} 对应 {@code mcp_call_log.backend_id}</li>
 *   <li>{@code serverName} 对应 MCP 显示名，后端从 {@code mcp_backend.server_name} 回填</li>
 *   <li>{@code status} SUCCESS / FAILED / TIMEOUT,由后端按 success+costMs 推导</li>
 * </ul>
 *
 * <p>鉴权:servlet HttpSession + Cookie(不传 Authorization Header)。
 */
import request from '@/utils/http'

/** 调用日志状态(对应后端 {@code InvokeStatusEnum} 序列化值)。 */
export type ImawxInvokeStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT'

/**
 * 调用日志 VO(对应后端 {@code McpCallLogVO},2026-07-01 重写,2026-07-02 增列)。
 *
 * <p>字段语义对齐后端:
 * <ul>
 *   <li>{@code id / userId / backendId} 是后端 DB BIGINT,通过 StringSerializer 输出 string</li>
 *   <li>{@code status} 是 SUCCESS/FAILED/TIMEOUT 之一,后端 service 推导</li>
 *   <li>{@code success} 是 1/0 数字,跟 status 冗余(前端一般只用 status)</li>
 *   <li>{@code argumentsJson / resultJson / streamLogsJson} 直接透传 DB 字符串 —— 前端按需 JSON.parse</li>
 *   <li>{@code invokedAt} 是 ISO 字符串(后端 Jackson JSR-310 输出)</li>
 *   <li>2026-07-02 增:{@code transportType / inboundSessionId / outboundSessionId / streamLogsJson}
 *     用于排查"调用方 session ↔ 被调方 session"链路</li>
 * </ul>
 */
export interface ImawxInvokeLog {
  id: string
  /** 链路追踪 ID,跨服务排障用。 */
  traceId?: string
  userId: string
  userEmail?: string
  tokenPrefix?: string
  /** 外部 MCP server ID(对应 mcp_backend.id,非后端的 backend_id 字符串别名)。 */
  backendId: number
  /** JOIN mcp_backend 拿到的 server_name,backend 被删时为 undefined。 */
  serverName?: string
  toolName: string
  /** 调用时 tool 描述快照,用于 MCP/tool 删除后审计可读。 */
  toolDescription?: string
  /** 真实 transport(HTTP / SSE / STDIO)—— DB 落库的值,跟 mcp_backend.transport_type 对齐。 */
  transportType?: string
  /** 调用方 session id —— imawx-mcp-gateway 自己发的 HTTP session id。 */
  inboundSessionId?: string
  /** 被调方 session id —— MCP server 给的 session id(后端反射从 transport 拿)。 */
  outboundSessionId?: string
  /**
   * 请求参数 JSON(原始字符串)。
   *
   * <p>字段名 2026-07-02 改:跟后端 {@code McpCallLogVO.argumentsJson} 对齐(原来是 requestJson)。
   * sync 阶段通常为空(没有 tool call 入参)。
   */
  argumentsJson?: string
  /**
   * 响应结果 JSON(原始字符串)。
   *
   * <p>字段名 2026-07-02 改:跟后端 {@code McpCallLogVO.resultJson} 对齐(原来是 responseJson)。
   * sync 阶段通常是 tools_snapshot(工具列表 JSON)。
   */
  resultJson?: string
  /**
   * SSE 流式 logging/progress 事件合并 JSON。
   *
   * <p>格式:[{type:"logging"|"progress", ...}, ...] —— 一个 callTool 多 events 合一条 log。
   * 非流式调用为 undefined。
   */
  streamLogsJson?: string
  /** SUCCESS / FAILED / TIMEOUT。 */
  status: ImawxInvokeStatus
  /** 1成功 / 0失败 —— 跟 status 冗余,前端通常用 status。 */
  success: number
  errorCode?: string
  errorMessage?: string
  /**
   * 耗时(毫秒)—— 后端 VO 字段名 {@code costMs}(2026-07-02 改:对齐后端
   * {@code McpCallLogVO.costMs};之前前端叫 {@code durationMs},字段名错位
   * 导致表格/详情里耗时列读不到值,formatter 返 {@code "—"})。
   */
  costMs: number
  /** 调用时间 ISO 8601 字符串(后端 create_time 字段)。 */
  invokedAt: string
  /**
   * 调用方 IP(2026-07-02 加)—— 后端从 {@code X-Forwarded-For}/{@code X-Real-IP}/
   * {@code request.getRemoteAddr()} 优先级拿,溯源/审计用。
   *
   * <p>老日志(2026-07-02 之前 insert 的)该字段为 undefined,前端表格显 {@code "—"}。
   */
  clientIp?: string
  /**
   * 调用方 User-Agent(2026-07-02 加)—— MCP 客户端标识(MCP Inspector /
   * Claude Desktop / 自研 agent / curl / SDK 等)。DB 列 VARCHAR(512),前端表格截断
   * 显示 + tooltip 完整内容。
   */
  userAgent?: string
}

/**
 * 调用日志查询参数(GET 请求用 query string 传,2026-07-01 重写)。
 *
 * <p>对齐后端 {@code McpCallLogQueryDTO} —— 字段名一一对应。
 */
export interface ImawxInvokeLogQuery {
  /** 开始时间 ISO 格式(后端 Jackson 解析 LocalDateTime)。 */
  startTime?: string
  /** 结束时间 ISO 格式。 */
  endTime?: string
  /** Tool 名(模糊匹配)。 */
  toolName?: string
  /**
   * 外部 MCP backend ID(精确匹配,对应 mcp_call_log.backend_id / mcp_proxy.id)。
   *
   * <p>2026-07-02 改:字段名跟后端 {@code McpCallLogQueryDTO.backendId} 对齐,
   * 之前叫 serverId 时后端 query 拿不到这个参数,过滤完全失效 —— drawer 显示所有
   * backend 的 log(用户原话"日志没记录完整",根因是过滤错位,不是字段没写)。
   */
  backendId?: string
  /**
   * 服务名(2026-07-06 加)—— JOIN mcp_backend.server_name 模糊匹配。
   *
   * <p>跟后端 {@code McpCallLogQueryDTO.serverName} 字段对齐;之前没这个过滤,
   * 调用日志页没法按"哪个后端"筛选。
   */
  serverName?: string
  /** 用户邮箱(模糊匹配)。 */
  userEmail?: string
  /** API Token 前缀(模糊匹配)。 */
  tokenPrefix?: string
  /** 调用状态过滤。 */
  status?: ImawxInvokeStatus
  /** 分页 —— 前端必须传 pageNum + pageSize。 */
  pageNum?: number
  pageSize?: number
}

/**
 * 调用日志分页响应(后端返 { records, total, pageNum, pageSize },2026-07-01 重写)。
 *
 * <p>跟老接口(直接返数组)不兼容 —— 这次顺便加上 total 字段,前端不用
 * "records.length < pageSize 即末页"的 hack 判断。
 */
export interface ImawxInvokeLogPage {
  records: ImawxInvokeLog[]
  total: number
  pageNum: number
  pageSize: number
}

/**
 * 调用日志聚合统计 VO(对应后端 {@code McpCallLogStatsVO},2026-07-01 加)。
 *
 * <p>前端 dashboard / 列表头部 stats 卡片用 —— 总数 / 成功 / 失败 / 超时 / 平均耗时 / 成功率。
 *
 * <p>{@code avgCostMs} 是 BigDecimal 类型,前端用 {@code Number(avgCostMs).toFixed(2)} 渲染。
 * {@code successRate} 是百分比 0-100(double),前端直接渲染 "98.5%"。
 */
export interface ImawxInvokeLogStats {
  totalCount: number
  successCount: number
  failedCount: number
  timeoutCount: number
  /** 平均耗时毫秒(可能 null —— 没有日志时)。 */
  avgCostMs?: number | null
  maxCostMs?: number | null
  minCostMs?: number | null
  /** 成功率 0-100,例如 98.5 表示 98.5%。可能 null(没日志时)。 */
  successRate?: number | null
}

/**
 * 调用日志分页查询(GET /api/sys/call-logs)。
 *
 * <p>所有过滤参数可选 —— 只传 pageNum / pageSize 即"全集"。
 */
export function fetchInvokeLogsSys(params: ImawxInvokeLogQuery) {
  return request.get<ImawxInvokeLogPage>({
    url: '/api/sys/call-logs',
    params
  })
}

/**
 * 调用日志聚合统计(GET /api/sys/call-logs/stats)。
 *
 * <p>时间默认:不传 startTime → endTime - 24h;不传 endTime → now。
 */
export function fetchInvokeLogStatsSys(params: { startTime?: string; endTime?: string } = {}) {
  return request.get<ImawxInvokeLogStats>({
    url: '/api/sys/call-logs/stats',
    params
  })
}

/**
 * 单条日志详情(GET /api/sys/call-logs/{id})。
 *
 * <p>{@code id} 是后端 DB BIGINT 序列化的字符串,后端 {@code @PathVariable Long}
 * Spring 自动从 string 转 Long。
 */
export function fetchInvokeLogDetailSys(id: string) {
  return request.get<ImawxInvokeLog>({
    url: `/api/sys/call-logs/${id}`
  })
}

/* ============================================================
 *  Dashboard 大屏监控 API(2026-07-02 重写)。
 *
 *  <p>对应后端 {@code SysMonitorDashboardController} ({@code /api/sys/monitor/dashboard}):
 *  <ul>
 *    <li>{@code GET /status-distribution?hours=24} —— 状态分布环形图</li>
 *    <li>{@code GET /hourly-trend?hours=24} —— 24h 分时双柱状(调用+错误)</li>
 *    <li>{@code GET /slow-requests?hours=24&limit=10} —— 慢请求 Top N</li>
 *    <li>{@code GET /backend-stats?hours=24} —— backend 聚合</li>
 *  </ul>
 *
 *  <p>老端点 /api/sys/monitor/summary 和 /trend 在新设计里**已废弃**,dashboard 直接
 *  接 4 个新端点 + /api/sys/call-logs/stats(4 个数字卡片)。
 * ============================================================ */

/**
 * 状态分布点(环形图用)—— {@code status} 是后端 {@code InvokeStatusEnum} 序列化值。
 */
export interface ImawxStatusDistributionPoint {
  status: ImawxInvokeStatus
  /** 该状态下的调用条数。 */
  cnt: number
}

/**
 * 24h 分时数据点 —— {@code hour} 是 0-23 整数,前端展示成 "HH:00"。
 */
export interface ImawxHourlyTrendPoint {
  hour: number
  invokeCount: number
  errorCount: number
}

/**
 * 慢请求行(慢请求表用)—— 排序按 {@code costMs DESC}。
 */
export interface ImawxSlowRequestRow {
  id: string
  toolName: string
  backendId: number
  backendName?: string
  transportType?: string
  costMs: number
  success: number
  invokedAt: string
}

/**
 * Backend 聚合行(状态网格用)—— 一次 SQL LEFT JOIN mcp_backend_tool 算 toolCount,
 * 避免读 mcp_backend.tools_snapshot(stale)。
 */
export interface ImawxBackendStatsRow {
  /** DB BIGINT —— mcp_backend.id,前端 string 接避免精度丢失。 */
  backendId: string
  /** 公开 backend_id 字符串别名(bk_xxx)。 */
  backendPublicId: string
  backendName: string
  /** HTTP / SSE / STDIO。 */
  transportType: string
  /** 1=healthy / 0=unhealthy。 */
  connectionStatus: number
  /** 1=enabled / 0=disabled。 */
  enabled: number
  lastSyncAt?: string
  /** 从 mcp_backend_tool 表 GROUP BY backend_id 算出来的真实 tool 数。 */
  toolCount: number
  invokeCount: number
  errorCount: number
  avgCostMs: number
  p99CostMs: number
}

/**
 * Dashboard 状态分布环形图(GET /api/sys/monitor/dashboard/status-distribution)。
 *
 * <p>{@code hours} 不传默认 24(后端 controller 默认)。
 */
export function fetchDashboardStatusDistribution(params: { hours?: number } = {}) {
  return request.get<ImawxStatusDistributionPoint[]>({
    url: '/api/sys/monitor/dashboard/status-distribution',
    params
  })
}

/**
 * Dashboard 24h 分时双柱状图(GET /api/sys/monitor/dashboard/hourly-trend)。
 *
 * <p>后端只返"有数据的小时",前端补 0 渲染完整 24h 时间轴。
 */
export function fetchDashboardHourlyTrend(params: { hours?: number } = {}) {
  return request.get<ImawxHourlyTrendPoint[]>({
    url: '/api/sys/monitor/dashboard/hourly-trend',
    params
  })
}

/**
 * Dashboard 慢请求 Top N(GET /api/sys/monitor/dashboard/slow-requests)。
 *
 * <p>{@code limit} 不传默认 10(后端 controller 默认)。
 */
export function fetchDashboardSlowRequests(params: { hours?: number; limit?: number } = {}) {
  return request.get<ImawxSlowRequestRow[]>({
    url: '/api/sys/monitor/dashboard/slow-requests',
    params
  })
}

/**
 * Dashboard backend 状态网格(GET /api/sys/monitor/dashboard/backend-stats)。
 *
 * <p>前端按 invokeCount DESC 排序展示;每张卡片显 backendName / transportType /
 * invokeCount / errorCount / avgCostMs / toolCount。
 */
export function fetchDashboardBackendStats(params: { hours?: number } = {}) {
  return request.get<ImawxBackendStatsRow[]>({
    url: '/api/sys/monitor/dashboard/backend-stats',
    params
  })
}

/** Transport 平均耗时趋势单点。 */
export interface ImawxTransportCostPoint {
  /** HTTP / SSE / STDIO / MYSQL / REDIS 等。 */
  transportType: string
  /** 从查询开始时间算起的滚动小时槽位，范围 0-23。 */
  slotIndex: number
  /** 槽位展示标签，例如 15:00。 */
  slotLabel?: string
  /** 该小时窗口的平均耗时毫秒。 */
  avgCostMs: number
}

/**
 * Dashboard 按 transport 分组的 24h 平均耗时趋势(GET /api/sys/monitor/dashboard/transport-cost-trend)。
 */
export function fetchDashboardTransportCostTrend(params: { hours?: number } = {}) {
  return request.get<ImawxTransportCostPoint[]>({
    url: '/api/sys/monitor/dashboard/transport-cost-trend',
    params
  })
}

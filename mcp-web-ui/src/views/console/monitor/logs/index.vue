<!--
  imawx-mcp 调用日志页（基座 art-design-pro 风格）

  对应 prd.md 第 7 章 7.2.7 规格：
  - 顶部 ArtSearchBar 搜索(时间范围 / Mcp / Tool 名 / 状态)
  - ElCard header 显示 "调用日志" + "N 条数据" 数量 tag
  - ArtTableHeader 工具栏: 导出 CSV 按钮 + 内置 refresh / fullscreen / columns / settings
  - ArtTable 表格: 时间 / Mcp / Tool / 状态 / 耗时 / 客户端 / IP / 操作
  - 详情用 ElDialog 640px 居中弹窗(替换 el-drawer,更紧凑)

  后端接口(prd 6.1):
  - GET    /api/sys/monitor/logs           分页查询
  - GET    /api/sys/monitor/logs/{id}      详情
  - GET    /api/sys/monitor/logs/export    CSV 导出(响应是 text/csv,绕过统一响应包装)

  视觉规范:完全使用基座 art-design-pro 的 ArtSearchBar / ArtTableHeader / ArtTable /
  ArtButtonTable / ElTag 等组件,字体 / 颜色 / 间距全部走基座的 Tailwind token
  (text-g-700 / bg-g-300/55 / text-theme / bg-success/10 等),不写自定义 CSS 变量。
-->
<template>
  <div class="imawx-monitor-logs flex flex-col gap-3 art-full-height">
    <!-- 1) 搜索区(基座 ArtSearchBar:时间范围 + 服务名 + Tool 名 + 状态)
         2026-07-06 加:跟 McpProxyLogsDialog 一致,默认折叠 (showSearchBar=false),
         ArtTableHeader 搜索图标可展开/隐藏。节省顶部空间,跟刚删的 stats 卡片风格一致。 -->
    <ArtSearchBar
      v-if="showSearchBar"
      v-model="searchForm"
      :items="searchItems"
      :span="6"
      :default-expanded="true"
      :show-expand="true"
      @search="handleSearch"
      @reset="handleReset"
    />

    <!-- 2) 表格区(基座 ElCard + ArtTableHeader + ArtTable)
         2026-07-03 删 ElCard header("调用日志"标题 + "N 条数据" tag)——
         "调用日志"已由路由/layout 标题承载;"N 条数据"已由 ArtTable 分页栏
         的 total 槽位承载,单独再放一个 ElTag 视觉冗余。 -->
    <ElCard class="art-table-card flex-1 min-h-0" shadow="never">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:show-search-bar="showSearchBar"
        :loading="loading"
        full-class="imawx-monitor-logs"
        @refresh="reload"
      >
        <template #left>
          <ElSpace wrap>
            <!--
              错误提示(2026-07-03 迁过来)—— 原 ElCard header 里 ElTag 展示 loadError,
              header 删了之后落到这里。失败原因直接显示在表格上方,排查路径最短。
            -->
            <ElTag v-if="loadError" type="danger" effect="light">
              {{ loadError }}
            </ElTag>
            <!-- 2026-07-01 暂移除导出 CSV —— 后端 export 端点还没做;留个按钮会误导。
                 等阶段 3 真实 tool call 接入后再补回来,功能优先。 -->
          </ElSpace>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :pagination="pagination"
        :data="records"
        :columns="columns"
        empty-text="暂无调用记录"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <!-- 传输协议列(2026-07-02 加):HTTP / SSE / STDIO → 软色 chip -->
        <template #transportType="{ row }">
          <span :class="['imawx-monitor-logs__chip', transportChipClass(row.transportType)]">
            <ArtSvgIcon :icon="transportIcon(row.transportType)" />
            {{ transportLabel(row.transportType) }}
          </span>
        </template>

        <!-- 状态列:SUCCESS/FAILED/TIMEOUT → 软色 chip -->
        <template #status="{ row }">
          <span :class="['imawx-monitor-logs__tag', statusTagClass(row.status)]">
            {{ row.status || '—' }}
          </span>
        </template>

        <!-- 详情按钮(基座 ArtButtonTable) -->
        <template #operation="{ row }">
          <ArtButtonTable type="view" :row="row" @click="openDetail(row)" />
        </template>

        <!--
          请求参数查看器触发按钮(2026-07-02 加)—— 点开 ImawxJsonViewer 弹窗显示
          argumentsJson 完整内容。argumentsJson 为空时按钮禁用。
        -->
        <template #request="{ row }">
          <ElButton
            link
            type="primary"
            size="small"
            :disabled="!row.argumentsJson"
            @click="openJsonViewer(row.argumentsJson, `请求参数 #${row.id}`)"
          >
            查看
          </ElButton>
        </template>

        <!-- 响应结果查看器触发按钮(2026-07-02 加)—— 同 request -->
        <template #response="{ row }">
          <ElButton
            link
            type="primary"
            size="small"
            :disabled="!row.resultJson"
            @click="openJsonViewer(row.resultJson, `响应结果 #${row.id}`)"
          >
            查看
          </ElButton>
        </template>
      </ArtTable>

      <!--
        共享 JSON 查看器弹窗(2026-07-02 加)—— 表格 N 行共用一个实例,点击哪行
        就把哪行的 json 设置到 jsonViewer.payload,弹窗显示对应内容。
        不用每行一个 viewer 实例,避免 10 个 dialog 全挂载的性能浪费。
      -->
      <ImawxJsonViewer
        v-model="jsonViewer.visible"
        :json="jsonViewer.payload"
        :title="jsonViewer.title"
      />
    </ElCard>

    <!-- 3) 详情弹窗(640px 居中,基座 ElDialog) -->
    <ElDialog
      v-model="detailVisible"
      :title="`调用详情 #${detail?.id ?? ''}`"
      width="640px"
      top="8vh"
      class="imawx-dialog-fixed imawx-dialog-fixed--compact"
      :show-close="true"
      destroy-on-close
    >
      <div v-if="detail" class="imawx-monitor-logs__detail">
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">状态</span>
          <span :class="['imawx-monitor-logs__tag', statusTagClass(detail.status)]">
            {{ detail.status }}
          </span>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">调用时间</span>
          <span class="text-g-700">{{ formatTime(detail.invokedAt) }}</span>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Mcp</span>
          <span class="text-g-700">{{ detail.serverName || '—' }}</span>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Tool</span>
          <code class="text-g-700">{{ detail.toolName }}</code>
        </div>
        <div v-if="detail.toolDescription" class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Tool 描述</span>
          <span class="text-g-700">{{ detail.toolDescription }}</span>
        </div>
        <!-- 2026-07-02 加:Transport chip(跟表格列 chip 一致) -->
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Transport</span>
          <span :class="['imawx-monitor-logs__chip', transportChipClass(detail.transportType)]">
            <ArtSvgIcon :icon="transportIcon(detail.transportType)" />
            {{ transportLabel(detail.transportType) }}
          </span>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">耗时</span>
          <span class="text-g-700">{{ formatDuration(detail.costMs) }}</span>
        </div>
        <div v-if="detail.traceId" class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Trace ID</span>
          <code class="text-g-700">{{ detail.traceId }}</code>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">用户邮箱</span>
          <span class="text-g-700">{{ detail.userEmail || '—' }}</span>
        </div>
        <div class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Token 前缀</span>
          <code class="text-g-700">{{ detail.tokenPrefix || '—' }}</code>
        </div>
        <!-- 2026-07-02 加:双向 session id,排查跨服务链路 -->
        <div v-if="detail.inboundSessionId" class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Inbound Session</span>
          <code class="text-g-700">{{ detail.inboundSessionId }}</code>
        </div>
        <div v-if="detail.outboundSessionId" class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">Outbound Session</span>
          <code class="text-g-700">{{ detail.outboundSessionId }}</code>
        </div>
        <div v-if="detail.errorCode" class="imawx-monitor-logs__detail-row">
          <span class="imawx-monitor-logs__detail-key">错误码</span>
          <code class="text-g-700">{{ detail.errorCode }}</code>
        </div>

        <div class="imawx-monitor-logs__detail-block">
          <div class="imawx-monitor-logs__detail-key">请求参数</div>
          <pre class="imawx-monitor-logs__pre">{{ formatJson(detail.argumentsJson) }}</pre>
        </div>
        <div class="imawx-monitor-logs__detail-block">
          <div class="imawx-monitor-logs__detail-key">响应结果</div>
          <pre class="imawx-monitor-logs__pre">{{ formatJson(detail.resultJson) }}</pre>
        </div>
        <!-- 2026-07-02 加:SSE 流式 logging/progress 事件合并 -->
        <div v-if="detail.streamLogsJson" class="imawx-monitor-logs__detail-block">
          <div class="imawx-monitor-logs__detail-key">流式事件 ({{ streamLogsCount }} 条)</div>
          <pre class="imawx-monitor-logs__pre">{{ formatJson(detail.streamLogsJson) }}</pre>
        </div>
        <div v-if="detail.errorMessage" class="imawx-monitor-logs__detail-block">
          <div class="imawx-monitor-logs__detail-key">错误信息</div>
          <ElAlert type="error" :closable="false" class="!items-start">
            <pre class="imawx-monitor-logs__pre imawx-monitor-logs__pre--error">{{
              detail.errorMessage
            }}</pre>
          </ElAlert>
        </div>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchInvokeLogDetailSys,
    fetchInvokeLogsSys,
    type ImawxInvokeLog,
    type ImawxInvokeLogQuery,
    type ImawxInvokeStatus
  } from '@/api/sys/monitor'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import { useConstants } from '@/composables/useConstants'
  import ImawxJsonViewer from '@/components/imawx/ImawxJsonViewer.vue'

  defineOptions({ name: 'ImawxMonitorLogs' })

  // 状态下拉框(App.vue 启动时已拉,这里读缓存)
  // 用 computed 包装 getOptions:顶层 const 求值时如果常量还没回来,options 数组
  // 会被固化成空;computed 跟随 useConstants 模块级 ref 自动重算,加载完后立刻有选项。
  const { getOptions } = useConstants()

  // ===== 搜索表单 =====
  /**
   * 搜索表单状态(基座 ArtSearchBar 通过 v-model 双向绑定,2026-07-01 简化)
   * - daterange: [startISO, endISO] 来自 ArtSearchBar 的 datetimerange 组件
   * - serverName: Mcp 模糊搜索(后端 JOIN mcp_backend.server_name LIKE 过滤)
   * - toolName: 模糊搜索
   * - status: SUCCESS / FAILED / TIMEOUT
   */
  const searchForm = ref<{
    daterange?: string[]
    serverName?: string
    toolName?: string
    userEmail?: string
    tokenPrefix?: string
    status?: ImawxInvokeStatus
  }>({
    daterange: undefined,
    serverName: undefined,
    toolName: undefined,
    userEmail: undefined,
    tokenPrefix: undefined,
    status: undefined
  })

  /**
   * ArtSearchBar items 配置(基座约定的 schema)
   * - type: 'datetimerange' / 'input' / 'select'
   * - props 透传给底层 Element Plus 组件,**包括 options** —— ArtSearchBar
   *   的 getProps(item) 在 item.props 存在时只返回 item.props 本身,
   *   item 顶层的 options 会被丢弃,ElOption 永远不渲染。所以 select 的
   *   options 必须放 props 里(对齐基座 role-search.vue 的写法)。
   *
   * <p>searchItems 整体用 computed 包装,跟随 useConstants 模块级 ref 自动重算,
   * 常量回来后立刻刷出真实选项。
   */
  const searchItems = computed(() => [
    {
      key: 'daterange',
      label: '时间范围',
      type: 'datetimerange',
      props: {
        type: 'datetimerange',
        startPlaceholder: '开始时间',
        endPlaceholder: '结束时间',
        format: 'YYYY-MM-DD HH:mm:ss',
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
        style: 'width: 100%'
      }
    },
    {
      key: 'serverName',
      label: 'Mcp',
      type: 'input',
      props: { placeholder: '模糊搜索 Mcp', clearable: true }
    },
    {
      key: 'toolName',
      label: 'Tool 名',
      type: 'input',
      props: { placeholder: '模糊搜索', clearable: true }
    },
    {
      key: 'userEmail',
      label: '邮箱',
      type: 'input',
      props: { placeholder: '调用账号邮箱', clearable: true }
    },
    {
      key: 'tokenPrefix',
      label: 'Token 前缀',
      type: 'input',
      props: { placeholder: '如 imwx_ab', clearable: true }
    },
    {
      key: 'status',
      label: '状态',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '全部',
        options: getOptions('invokeStatus')
          .filter((o) => o.value !== undefined && o.value !== null && String(o.value).trim() !== '')
          .map((o) => ({ label: o.label || o.desc || String(o.value), value: o.value }))
      }
    }
  ])

  // ===== 表格列定义(基座 ArtTable 约定) =====
  /**
   * 搜索栏显隐(2026-07-06 改)—— 默认折叠 (false),ArtTableHeader 搜索图标可展开/隐藏。
   *
   * <p>跟 McpProxyLogsDialog 行为一致:节省顶部空间,搜索是低频操作,
   * 想搜时点工具栏图标展开。{@code <ArtSearchBar v-if="showSearchBar">} 让这个 ref 真的
   * 控制搜索栏 DOM 显隐 —— 之前没 v-if,改了 ref 也无效,ArtTableHeader 搜索图标"假死"。
   */
  const showSearchBar = ref(false)
  const records = ref<ImawxInvokeLog[]>([])
  const total = ref(0)
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const exporting = ref(false) // 2026-07-01 保留占位,export 端点补回来后重新启用
  void exporting

  const pagination = reactive({ current: 1, size: 20, total: 0 })

  /**
   * ArtTable 列定义(用 formatter 注入自定义 chip / icon,跟用户列表页一致)
   * 操作列固定在右侧,用 ArtButtonTable 渲染"详情"按钮(view 类型 = 蓝色眼 icon)
   *
   * <p>用基座 {@code useTableColumns} 而不是手写 columns + columnChecks 两份数组:
   * 之前是 columnChecks / columns 断联,ArtTableHeader 列设置改了 columnChecks.checked
   * 但 ArtTable 消费的 columns 收不到通知,列隐藏永远不生效。{@code useTableColumns}
   * 内部把 columns 实现为 columnChecks.filter(visible),自动双向同步。
   */
  const { columns, columnChecks } = useTableColumns<ImawxInvokeLog>(() => [
    {
      prop: 'invokedAt',
      label: '调用时间',
      width: 170,
      formatter: (row) => formatTime(row.invokedAt)
    },
    { prop: 'serverName', label: 'Mcp', minWidth: 260, showOverflowTooltip: true },
    {
      prop: 'transportType',
      label: 'Transport',
      width: 130,
      align: 'center',
      useSlot: true,
      slotName: 'transportType'
    },
    {
      prop: 'toolName',
      label: 'Tool',
      minWidth: 250,
      showOverflowTooltip: true,
      formatter: (row) => row.toolName || '—'
    },
    {
      prop: 'toolDescription',
      label: 'Tool 描述',
      minWidth: 220,
      showOverflowTooltip: true,
      formatter: (row) => row.toolDescription || '—'
    },
    {
      prop: 'status',
      label: '状态',
      width: 110,
      align: 'center',
      useSlot: true,
      slotName: 'status'
    },
    {
      prop: 'costMs',
      label: '耗时',
      width: 130,
      align: 'right',
      formatter: (row) => formatDuration(row.costMs)
    },
    {
      prop: 'traceId',
      label: 'Trace ID',
      minWidth: 140,
      showOverflowTooltip: true,
      formatter: (row) => row.traceId || '—'
    },
    {
      prop: 'clientIp',
      label: 'IP',
      width: 140,
      showOverflowTooltip: true,
      formatter: (row) => row.clientIp || '—'
    },
    {
      prop: 'userAgent',
      label: 'User-Agent',
      minWidth: 200,
      showOverflowTooltip: true,
      formatter: (row) => row.userAgent || '—'
    },
    {
      prop: 'userEmail',
      label: '用户邮箱',
      minWidth: 180,
      showOverflowTooltip: true,
      formatter: (row) => row.userEmail || '—'
    },
    {
      prop: 'tokenPrefix',
      label: 'Token 前缀',
      width: 130,
      showOverflowTooltip: true,
      formatter: (row) => row.tokenPrefix || '—'
    },
    {
      prop: 'argumentsJson',
      label: '请求',
      width: 90,
      align: 'center',
      useSlot: true,
      slotName: 'request'
    },
    {
      prop: 'resultJson',
      label: '响应',
      width: 90,
      align: 'center',
      useSlot: true,
      slotName: 'response'
    },
    {
      prop: 'operation',
      label: '操作',
      width: 90,
      fixed: 'right',
      align: 'center',
      useSlot: true,
      slotName: 'operation'
    }
  ])

  // ===== 详情 =====
  const detailVisible = ref(false)
  const detail = ref<ImawxInvokeLog | null>(null)

  // ===== JSON 查看器共享状态(2026-07-02 加) =====
  /**
   * N 行表格共用一个 viewer 实例 —— payload/title/visible 都集中在这里,
   * 避免每行挂一个 ImawxJsonViewer 实例的性能浪费(10 个 dialog 全挂 DOM)。
   */
  const jsonViewer = ref<{ visible: boolean; payload: string | null; title: string }>({
    visible: false,
    payload: null,
    title: 'JSON 详情'
  })
  function openJsonViewer(payload: string | null, title: string) {
    jsonViewer.value.payload = payload
    jsonViewer.value.title = title
    jsonViewer.value.visible = true
  }

  // ===== 加载 / 搜索 / 重置 =====
  /**
   * 把 ArtSearchBar 的 daterange 拆成后端要的 startTime / endTime,过滤空值后传给 API。
   */
  function buildQueryParams() {
    const sf = searchForm.value
    const params: ImawxInvokeLogQuery = {
      pageNum: pagination.current,
      pageSize: pagination.size
    }
    if (Array.isArray(sf.daterange) && sf.daterange.length === 2) {
      params.startTime = sf.daterange[0]
      params.endTime = sf.daterange[1]
    }
    if (sf.serverName) params.serverName = sf.serverName
    if (sf.toolName) params.toolName = sf.toolName
    if (sf.userEmail) params.userEmail = sf.userEmail
    if (sf.tokenPrefix) params.tokenPrefix = sf.tokenPrefix
    if (sf.status) params.status = sf.status
    return params
  }

  async function reload() {
    loading.value = true
    loadError.value = null
    try {
      // 2026-07-01 改:后端响应改成 { records, total, pageNum, pageSize },真实 total 可用
      const page = (await fetchInvokeLogsSys(buildQueryParams()))
      records.value = page?.records ?? []
      total.value = page?.total ?? 0
      pagination.total = total.value
    } catch (e) {
      records.value = []
      total.value = 0
      pagination.total = 0
      loadError.value = (e as Error).message || '加载失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 聚合统计(2026-07-01 加)—— 顶部 4 个 stat 卡片的数据源。
   *
   * <p>2026-07-06 删:用户要求"卡片不需要",顶部 4 个 stat 块整段移除,
   * stats / statsLoading / reloadStats 都不要了;{@code fetchInvokeLogStatsSys}
   * 接口保留,后续如果需要 dashboard 复用还能用。
   */

  function handleSearch() {
    pagination.current = 1
    reload()
  }

  function handleReset() {
    pagination.current = 1
    // 显式重置 searchForm,基座 ArtSearchBar @reset 只发事件,不一定把 v-model 置空
    searchForm.value = {
      daterange: undefined,
      serverName: undefined,
      toolName: undefined,
      userEmail: undefined,
      tokenPrefix: undefined,
      status: undefined
    }
    reload()
  }

  function handleSizeChange(size: number) {
    pagination.size = size
    pagination.current = 1
    reload()
  }

  function handleCurrentChange(current: number) {
    pagination.current = current
    reload()
  }

  // ===== 详情 =====
  async function openDetail(row: ImawxInvokeLog) {
    try {
      detail.value = await fetchInvokeLogDetailSys(row.id)
      detailVisible.value = true
    } catch {
      // 错误已被 axios 拦截器弹了 ElMessage
    }
  }

  // ===== 导出 CSV 暂移除(2026-07-01) —— 后端 export 端点没做,留个按钮误导。
  // 阶段 3 真实 tool call 接入后再补回,功能优先。

  // ===== 格式化 =====
  function formatTime(iso?: string): string {
    if (!iso) return '—'
    // 后端 LocalDateTime ISO 形如 2026-06-27T05:00:00,直接展示
    return iso.replace('T', ' ')
  }

  /**
   * 把毫秒数格式化成 {@code XmYYsZZZms} 自适应形式(2026-07-01 加)。
   *
   * <ul>
   *   <li>&lt; 1s  → {@code 456ms}</li>
   *   <li>&lt; 1m  → {@code 23s456ms}</li>
   *   <li>≥ 1m  → {@code 1m23s456ms}</li>
   * </ul>
   * 跟 IT 监控/日志通用习惯对齐 —— 监控老报错"5000ms 是 5s 还是要带 ms"的人不存在了。
   */
  function formatDuration(ms?: number | null): string {
    if (ms == null) return '—'
    const total = Math.max(0, Math.floor(ms))
    if (total < 1000) return `${total}ms`
    const minutes = Math.floor(total / 60_000)
    const seconds = Math.floor((total % 60_000) / 1000)
    const millis = total % 1000
    if (minutes === 0) return `${seconds}s${String(millis).padStart(3, '0')}ms`
    return `${minutes}m${String(seconds).padStart(2, '0')}s${String(millis).padStart(3, '0')}ms`
  }

  function formatJson(s?: string): string {
    if (!s) return '（空）'
    try {
      return JSON.stringify(JSON.parse(s), null, 2)
    } catch {
      return s
    }
  }

  // ===== 视觉映射(状态 / Transport → chip 类名) =====
  // 用基座的软色 token:is-ok / is-err / is-timeout
  function statusTagClass(status?: string): string {
    switch (status) {
      case 'SUCCESS':
        return 'is-ok'
      case 'TIMEOUT':
        return 'is-timeout'
      case 'FAILED':
        return 'is-err'
      default:
        return 'is-unknown'
    }
  }

  /**
   * Transport 视觉映射(2026-07-02 加)—— HTTP / SSE / STDIO → chip + icon。
   *
   * <p>HTTP / STREAMABLE_HTTP 都归一化显示成"HTTP"(落库真实 transport 是 HTTP)。
   * 跟 McpProxyLogsDialog 卡片弹窗逻辑保持一致 —— DB 里 stdio 显示"STDIO"、
   * sse 显示"SSE"、http/streamable_http 显示"HTTP"。
   *
   * <p>2026-07-06 改:6 个 DB 系 transport(MYSQL/POSTGRESQL/SQLSERVER/MONGODB/
   * REDIS/ELASTICSEARCH)跟 SSH 都补 icon —— 之前全部 fallback 到 `ri:question-line`,
   * 表格里 6 个灰底问号,分不清谁是谁。
   */
  function transportLabel(t?: string): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'HTTP'
    if (t === 'SSE') return 'SSE'
    if (t === 'STDIO') return 'STDIO'
    return t || '—'
  }

  function transportIcon(t?: string): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'ri:link'
    if (t === 'SSE') return 'ri:signal-tower-line'
    if (t === 'STDIO') return 'ri:terminal-box-line'
    // DB 系按类型给不同 icon,统一视觉风格但区分一眼能看出来
    if (t === 'MYSQL' || t === 'POSTGRESQL' || t === 'SQLSERVER') return 'ri:database-2-line'
    if (t === 'MONGODB') return 'ri:leaf-line'
    if (t === 'REDIS') return 'ri:key-2-line'
    if (t === 'ELASTICSEARCH') return 'ri:search-line'
    if (t === 'SSH') return 'ri:terminal-box-line'
    return 'ri:question-line'
  }

  function transportChipClass(t?: string): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'is-http'
    if (t === 'SSE') return 'is-sse'
    if (t === 'STDIO') return 'is-stdio'
    return 'is-unknown'
  }

  /** 流式事件合并数组长度 —— 详情弹窗里"流式事件 (N 条)"的 N。 */
  const streamLogsCount = computed(() => {
    const s = detail.value?.streamLogsJson
    if (!s) return 0
    try {
      const arr = JSON.parse(s)
      return Array.isArray(arr) ? arr.length : 0
    } catch {
      return 0
    }
  })

  // ===== 初始化 =====
  onMounted(() => {
    reload()
  })
</script>

<style scoped lang="scss">
  /**
   * 全部走基座 Tailwind token + Element Plus 主题变量,本文件只放真正需要的少量样式:
   *   - 状态/Transport chip 的软色背景(基座没有现成组件,只能本地实现一次)
   *   - 详情弹窗的 key/value 行 + JSON pre 样式
   *
   * 字体 / 颜色 / 间距 一律继承基座,不在本文件覆盖。
   */

  /* 固定高度用全局 styles/imawx.scss 的 .imawx-dialog-fixed--compact(600px) */

  /* 状态 chip:SUCCESS / FAILED / TIMEOUT / 未知 */
  .imawx-monitor-logs__tag {
    display: inline-flex;
    align-items: center;
    padding: 1px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 600;
    line-height: 1.6;
    border: 1px solid transparent;
  }
  .imawx-monitor-logs__tag.is-ok {
    background: var(--el-color-success-light-9);
    color: var(--el-color-success);
    border-color: var(--el-color-success-light-7);
  }
  .imawx-monitor-logs__tag.is-err {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
    border-color: var(--el-color-danger-light-7);
  }
  .imawx-monitor-logs__tag.is-timeout {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
    border-color: var(--el-color-warning-light-7);
  }
  .imawx-monitor-logs__tag.is-unknown {
    background: var(--el-fill-color-light);
    color: var(--el-text-color-placeholder);
    border-color: var(--el-border-color-lighter);
  }

  /* Transport chip */
  .imawx-monitor-logs__chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 1px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 500;
    line-height: 1.6;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-regular);
  }
  /* EXTERNAL 是协议族归一,transportType 不会有这个值,需要单独一个类 */
  .imawx-monitor-logs__chip.is-external {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }
  .imawx-monitor-logs__chip.is-unknown {
    background: var(--el-fill-color-light);
    color: var(--el-text-color-placeholder);
  }
  /* Transport chip:HTTP / SSE / STDIO(2026-07-02 加) */
  .imawx-monitor-logs__chip.is-http {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }
  .imawx-monitor-logs__chip.is-sse {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
  }
  .imawx-monitor-logs__chip.is-stdio {
    background: var(--el-color-info-light-9);
    color: var(--el-color-info);
  }

  /* 详情弹窗 */
  .imawx-monitor-logs__detail {
    font-size: 13px;
  }
  .imawx-monitor-logs__detail-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 0;
    border-bottom: 1px dashed var(--el-border-color-lighter);
  }
  .imawx-monitor-logs__detail-row:last-of-type {
    border-bottom: 0;
  }
  .imawx-monitor-logs__detail-key {
    width: 80px;
    flex-shrink: 0;
    color: var(--el-text-color-regular);
    font-size: 12px;
  }
  .imawx-monitor-logs__detail-block {
    margin-top: 12px;
  }
  .imawx-monitor-logs__pre {
    margin: 6px 0 0;
    padding: 10px 12px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-primary);
    max-height: 240px;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .imawx-monitor-logs__pre--error {
    border-left: 3px solid var(--el-color-danger);
    color: var(--el-color-danger);
    background: transparent;
    margin: 0;
  }
</style>

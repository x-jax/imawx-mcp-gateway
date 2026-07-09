<template>
  <div class="imawx-dashboard">
    <div class="imawx-dashboard__realtime">
      <div class="imawx-dashboard__realtime-main">
        <div>
          <h3>实时监控</h3>
          <p>自动刷新 · 最近更新 {{ lastRefreshLabel }}</p>
        </div>
        <ElTag :type="isRefreshing ? 'warning' : 'success'" effect="light" size="small">
          {{ isRefreshing ? '刷新中' : '运行中' }}
        </ElTag>
      </div>
      <div class="imawx-dashboard__realtime-actions">
        <ElSwitch v-model="autoRefresh" size="small" active-text="自动刷新" inactive-text="暂停" />
        <ElButton size="small" :loading="isRefreshing" @click="reloadAll">
          <template #icon><ArtSvgIcon icon="ri:refresh-line" /></template>
          刷新
        </ElButton>
      </div>
    </div>

    <!-- 第 1 行:4 个统计卡片 -->
    <ElRow :gutter="16" class="imawx-dashboard__cards" v-loading="cardsLoading">
      <ElCol v-for="card in cards" :key="card.title" :xs="24" :sm="12" :md="12" :lg="6">
        <ArtStatsCard
          :icon="card.icon"
          :iconStyle="card.iconStyle"
          :title="card.title"
          :count="card.count"
          :description="card.description"
        />
      </ElCol>
    </ElRow>

    <!-- 第 2 行:类型分组滚动 24h 平均耗时折线图 -->
    <div class="art-card imawx-dashboard__transport">
      <div class="art-card-header">
        <div class="title">
          <h4>平均耗时趋势</h4>
          <p>当前时间往前 24 小时 · 按类型和滚动小时窗口聚合</p>
        </div>
        <div class="imawx-dashboard__transport-tags">
          <ElTag
            v-for="t in transportTagList"
            :key="t.transportType"
            :type="transportTagType(t.transportType)"
            effect="light"
            size="small"
          >
            {{ t.transportType }} · {{ formatMs(t.avgCostMs) }}
          </ElTag>
        </div>
      </div>

      <div class="imawx-dashboard__transport-body">
        <ArtLineChart
          v-if="transportLineSeries.length > 0"
          height="100%"
          :data="transportLineSeries"
          :xAxisData="transportHourLabels"
          :colors="transportLineColors"
          :showAreaColor="false"
          :showAxisLine="false"
          :showLegend="true"
          legendPosition="top"
          :showSplitLine="true"
          :smooth="true"
        />
        <div v-else class="imawx-dashboard__empty">
          <ArtSvgIcon icon="ri:line-chart-line" class="imawx-dashboard__empty-icon" />
          <span>过去 24 小时各类型均无调用</span>
        </div>
      </div>
    </div>

    <!-- 第 3 行:慢请求 Top 10 + 最近调用 Top 10 -->
    <ElRow :gutter="16" class="imawx-dashboard__live">
      <ElCol :xs="24" :lg="12">
        <div class="art-card imawx-dashboard__panel imawx-dashboard__panel--tall" v-loading="slowLoading">
          <div class="art-card-header">
            <div class="title">
              <h4>慢请求 Top 10</h4>
              <p>按耗时倒序 · 过去 24 小时</p>
            </div>
            <ElTag type="warning" effect="light" size="small">
              最慢 {{ slowestCostMs }} ms
            </ElTag>
          </div>

          <div v-if="slowRequests.length > 0" class="imawx-dashboard__recent-list">
            <div v-for="call in slowRequests" :key="call.id" class="imawx-dashboard__recent-row">
              <div>
                <span class="imawx-dashboard__recent-tool">{{ call.toolName || '同步/探测' }}</span>
                <small>
                  {{ callTransportName(call.transportType, call.backendName, call.backendId) }}
                </small>
              </div>
              <div class="imawx-dashboard__recent-meta">
                <ElTag
                  :type="callStatusTagType(call)"
                  effect="light"
                  size="small"
                >
                  {{ callStatus(call) }}
                </ElTag>
                <span class="imawx-dashboard__recent-cost">{{ formatMs(call.costMs) }}</span>
                <time class="imawx-dashboard__recent-time">{{ formatTime(call.invokedAt) }}</time>
              </div>
            </div>
          </div>
          <div v-else class="imawx-dashboard__empty is-compact">
            <ArtSvgIcon icon="ri:speed-up-line" class="imawx-dashboard__empty-icon" />
            <span>过去 24 小时暂无慢请求</span>
          </div>
        </div>
      </ElCol>

      <ElCol :xs="24" :lg="12">
        <div class="art-card imawx-dashboard__panel imawx-dashboard__panel--tall">
          <div class="art-card-header">
            <div class="title">
              <h4>最近调用</h4>
              <p>最新 10 条审计日志</p>
            </div>
            <ElTag type="info" effect="light" size="small">{{ recentCalls.length }} 条</ElTag>
          </div>

          <div v-if="recentCalls.length > 0" class="imawx-dashboard__recent-list">
            <div v-for="call in recentCalls" :key="call.id" class="imawx-dashboard__recent-row">
              <div>
                <span class="imawx-dashboard__recent-tool">{{ call.toolName || '同步/探测' }}</span>
                <small>
                  {{ callTransportName(call.transportType, call.serverName, call.backendId) }}
                </small>
              </div>
              <div class="imawx-dashboard__recent-meta">
                <ElTag
                  :type="callStatusTagType(call)"
                  effect="light"
                  size="small"
                >
                  {{ callStatus(call) }}
                </ElTag>
                <span class="imawx-dashboard__recent-cost">{{ formatMs(call.costMs) }}</span>
                <time class="imawx-dashboard__recent-time">{{ formatTime(call.invokedAt) }}</time>
              </div>
            </div>
          </div>
          <div v-else class="imawx-dashboard__empty is-compact">
            <ArtSvgIcon icon="ri:file-list-3-line" class="imawx-dashboard__empty-icon" />
            <span>暂无最近调用</span>
          </div>
        </div>
      </ElCol>
    </ElRow>

    <!-- 第 4 行:状态分布环形 + 24h 分时柱状 -->
    <ElRow :gutter="16" class="imawx-dashboard__row">
      <ElCol :xs="24" :lg="12">
        <div class="art-card imawx-dashboard__chart">
          <div class="art-card-header">
            <div class="title">
              <h4>调用状态分布</h4>
              <p>过去 24 小时 · SUCCESS / FAILED / TIMEOUT</p>
            </div>
            <ElTag :type="totalInvoke > 0 ? 'primary' : 'info'" effect="light" size="small">
              总计 {{ totalInvoke }} 次
            </ElTag>
          </div>
          <div class="imawx-dashboard__chart-body">
            <ArtRingChart
              v-if="statusRingData.length > 0"
              height="100%"
              :data="statusRingData"
              :colors="statusRingColors"
              :centerText="String(totalInvoke)"
              :showLegend="true"
              legendPosition="right"
              :radius="['52%', '78%']"
            />
            <div v-else class="imawx-dashboard__empty">
              <ArtSvgIcon icon="ri:pie-chart-line" class="imawx-dashboard__empty-icon" />
              <span>过去 24 小时暂无调用</span>
            </div>
          </div>
        </div>
      </ElCol>

      <ElCol :xs="24" :lg="12">
        <div class="art-card imawx-dashboard__chart">
          <div class="art-card-header">
            <div class="title">
              <h4>24h 调用分时</h4>
              <p>每整点聚合 · 调用总数 + 错误数</p>
            </div>
            <ElTag :type="totalError > 0 ? 'danger' : 'success'" effect="light" size="small">
              {{ totalError }} 个错误
            </ElTag>
          </div>
          <div class="imawx-dashboard__chart-body">
            <ArtBarChart
              v-if="hasHourlyData"
              height="100%"
              :data="hourlyBarSeries"
              :xAxisData="hourlyHourLabels"
              :colors="hourlyBarColors"
              :showLegend="true"
              legendPosition="top"
              barWidth="50%"
            />
            <div v-else class="imawx-dashboard__empty">
              <ArtSvgIcon icon="ri:bar-chart-box-line" class="imawx-dashboard__empty-icon" />
              <span>过去 24 小时暂无分时数据</span>
            </div>
          </div>
        </div>
      </ElCol>
    </ElRow>

  </div>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue'
  import { getCssVar } from '@/utils/ui'
  import {
    fetchInvokeLogStatsSys,
    fetchInvokeLogsSys,
    fetchDashboardStatusDistribution,
    fetchDashboardHourlyTrend,
    fetchDashboardSlowRequests,
    fetchDashboardTransportCostTrend,
    type ImawxInvokeLogStats,
    type ImawxInvokeLog,
    type ImawxStatusDistributionPoint,
    type ImawxHourlyTrendPoint,
    type ImawxSlowRequestRow,
    type ImawxTransportCostPoint
  } from '@/api/sys/monitor'
  import type { LineDataItem } from '@/types/component/chart'

  defineOptions({ name: 'ImawxDashboard' })

  // ====================================================================
  // 顶部 4 卡片数据
  // ====================================================================

  const stats = ref<ImawxInvokeLogStats | null>(null)
  const cardsLoading = ref(false)
  const isRefreshing = ref(false)
  const autoRefresh = ref(true)
  const lastRefreshAt = ref<Date | null>(null)

  const lastRefreshLabel = computed(() => {
    if (!lastRefreshAt.value) return '—'
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${pad(lastRefreshAt.value.getHours())}:${pad(lastRefreshAt.value.getMinutes())}:${pad(lastRefreshAt.value.getSeconds())}`
  })

  const cards = computed(() => [
    {
      title: '总调用次数',
      icon: 'ri:flashlight-line',
      iconStyle: 'bg-primary/10 !text-primary',
      count: stats.value?.totalCount ?? 0,
      description: '含成功 + 失败 + 超时'
    },
    {
      title: '成功率(%)',
      icon: 'ri:checkbox-circle-line',
      iconStyle: 'bg-success/10 !text-success',
      count: Math.round((stats.value?.successRate ?? 0) * 10) / 10,
      description: `${stats.value?.successCount ?? 0} 成功 / ${stats.value?.failedCount ?? 0} 失败`
    },
    {
      title: '平均耗时(ms)',
      icon: 'ri:timer-line',
      iconStyle: 'bg-warning/10 !text-warning',
      count: Math.round(stats.value?.avgCostMs ?? 0),
      description: `最大 ${formatMs(stats.value?.maxCostMs ?? 0)} / 最小 ${formatMs(stats.value?.minCostMs ?? 0)}`
    },
    {
      title: '慢请求数',
      icon: 'ri:error-warning-line',
      iconStyle: 'bg-danger/10 !text-danger',
      count: stats.value?.timeoutCount ?? 0,
      description: '达到网关超时阈值'
    }
  ])

  const recentCalls = ref<ImawxInvokeLog[]>([])

  // ====================================================================
  // 状态分布环形图
  // ====================================================================

  const statusDistribution = ref<ImawxStatusDistributionPoint[]>([])

  const totalInvoke = computed(() =>
    statusDistribution.value.reduce((sum, p) => sum + safeNumber(p.cnt), 0)
  )

  const totalError = computed(() =>
    statusDistribution.value
      .filter((p) => p.status !== 'SUCCESS')
      .reduce((sum, p) => sum + safeNumber(p.cnt), 0)
  )

  const STATUS_LABEL: Record<string, string> = {
    SUCCESS: '成功',
    FAILED: '失败',
    TIMEOUT: '超时'
  }

  const STATUS_COLOR_VAR: Record<string, string> = {
    SUCCESS: '--el-color-success',
    FAILED: '--el-color-danger',
    TIMEOUT: '--el-color-warning'
  }

  const statusRingData = computed(() =>
    statusDistribution.value.filter((p) => safeNumber(p.cnt) > 0).map((p) => ({
      name: STATUS_LABEL[p.status] ?? p.status,
      value: safeNumber(p.cnt)
    }))
  )

  /**
   * 状态环形颜色 —— CanvasGradient 不接受 CSS var 字符串,必须解析成真实 hex/rgb。
   *
   * <p>getCssVar() 在浏览器端读 :root 上 --el-color-success 等真实值。
   */
  const statusRingColors = computed(() =>
    statusDistribution.value.filter((p) => safeNumber(p.cnt) > 0).map(
      (p) => getCssVar(STATUS_COLOR_VAR[p.status] ?? '--el-color-primary') || '#409EFF'
    )
  )

  // ====================================================================
  // 24h 分时柱状图
  // ====================================================================

  const hourlyTrend = ref<ImawxHourlyTrendPoint[]>([])

  // 后端只返"有数据的小时",前端补全 24 个 slot(没有的小时填 0)让柱状图连续
  const full24h = computed<ImawxHourlyTrendPoint[]>(() => {
    const map = new Map(hourlyTrend.value.map((p) => [safeNumber(p.hour), p]))
    return Array.from({ length: 24 }, (_, hour) =>
      map.get(hour) ?? { hour, invokeCount: 0, errorCount: 0 }
    )
  })

  const hourlyHourLabels = computed(() => full24h.value.map((p) => `${String(p.hour).padStart(2, '0')}:00`))

  const hasHourlyData = computed(() =>
    full24h.value.some((p) => safeNumber(p.invokeCount) > 0 || safeNumber(p.errorCount) > 0)
  )

  const hourlyBarSeries = computed(() => [
    {
      name: '调用总数',
      data: full24h.value.map((p) => p.invokeCount)
    },
    {
      name: '错误数',
      data: full24h.value.map((p) => p.errorCount)
    }
  ])

  /** 24h 分时柱状颜色 —— CanvasGradient 不能解析 CSS var,解析成真实 hex。 */
  const hourlyBarColors = computed(() => [
    getCssVar('--el-color-primary') || '#409EFF',
    getCssVar('--el-color-danger') || '#F56C6C'
  ])

  const TRANSPORT_ORDER = [
    'HTTP', 'SSE', 'STDIO',
    'MYSQL', 'POSTGRESQL', 'SQLSERVER', 'ORACLE',
    'REDIS', 'MONGODB', 'ELASTICSEARCH',
    'ALIYUN_OSS', 'ALIYUN_DNS',
    'SSH', 'DRONE', 'OPENAPI'
  ] as const

  const TRANSPORT_COLORS: Record<string, string> = {
    HTTP: '#2563EB',
    SSE: '#16A34A',
    STDIO: '#D97706',
    MYSQL: '#0EA5E9',
    POSTGRESQL: '#6366F1',
    SQLSERVER: '#0891B2',
    ORACLE: '#DC2626',
    REDIS: '#EF4444',
    MONGODB: '#22C55E',
    ELASTICSEARCH: '#F59E0B',
    ALIYUN_OSS: '#F97316',
    ALIYUN_DNS: '#A855F7',
    SSH: '#64748B',
    DRONE: '#E11D48',
    OPENAPI: '#14B8A6',
    UNKNOWN: '#94A3B8'
  }

  const transportCostTrend = ref<ImawxTransportCostPoint[]>([])

  const transportByType = computed(() => {
    const map = new Map<string, Map<number, number>>()
    for (const p of transportCostTrend.value) {
      const transportType = (p.transportType || 'UNKNOWN').toUpperCase()
      const slotIndex = safeNumber(p.slotIndex)
      if (slotIndex < 0 || slotIndex > 23) continue
      let hourMap = map.get(transportType)
      if (!hourMap) {
        hourMap = new Map()
        map.set(transportType, hourMap)
      }
      hourMap.set(slotIndex, safeNumber(p.avgCostMs))
    }
    return map
  })

  const orderedTransportTypes = computed(() => {
    const types = Array.from(transportByType.value.keys())
    const known = TRANSPORT_ORDER.filter((type) => types.includes(type))
    const other = types.filter((type) => !TRANSPORT_ORDER.includes(type as (typeof TRANSPORT_ORDER)[number])).sort()
    return [...known, ...other]
  })

  const transportLineSeries = computed<LineDataItem[]>(() => {
    const series: LineDataItem[] = []
    for (const transportType of orderedTransportTypes.value) {
      const hourMap = transportByType.value.get(transportType)
      if (!hourMap || hourMap.size === 0) continue
      const data = Array.from({ length: 24 }, (_, hour) => hourMap.get(hour) ?? 0)
      series.push({
        name: transportType,
        data,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineWidth: 2
      })
    }
    return series
  })

  const transportLineColors = computed(() =>
    orderedTransportTypes.value.map((type) => TRANSPORT_COLORS[type] || TRANSPORT_COLORS.UNKNOWN)
  )

  const transportHourLabels = computed(() => {
    const labels = new Array<string>(24).fill('')
    for (const point of transportCostTrend.value) {
      const slotIndex = safeNumber(point.slotIndex)
      if (slotIndex >= 0 && slotIndex < 24 && point.slotLabel) labels[slotIndex] = point.slotLabel
    }
    if (labels.every(Boolean)) return labels
    const start = new Date(Date.now() - 24 * 60 * 60 * 1000)
    return labels.map((label, index) => {
      if (label) return label
      const slot = new Date(start.getTime() + index * 60 * 60 * 1000)
      return `${String(slot.getHours()).padStart(2, '0')}:00`
    })
  })

  const transportTagList = computed(() => {
    const series = transportLineSeries.value
    return series.map((s) => {
      const data = s.data
      const valid = data.filter((v) => v > 0)
      const avg = valid.length > 0 ? valid.reduce((sum, v) => sum + v, 0) / valid.length : 0
      return { transportType: s.name, avgCostMs: avg }
    })
  })

  /** transport → tag type 映射(HTTP=primary / SSE=success / STDIO=warning) */
  function transportTagType(transportType: string): 'primary' | 'success' | 'warning' {
    if (transportType === 'HTTP') return 'primary'
    if (transportType === 'SSE') return 'success'
    if (transportType === 'STDIO') return 'warning'
    return 'primary'
  }

  // ====================================================================
  // 慢请求 Top 10
  // ====================================================================

  const slowRequests = ref<ImawxSlowRequestRow[]>([])
  const slowLoading = ref(false)

  const slowestCostMs = computed(() => {
    if (slowRequests.value.length === 0) return 0
    return Math.max(...slowRequests.value.map((r) => safeNumber(r.costMs)))
  })

  // ====================================================================
  // 数据加载
  // ====================================================================

  async function reloadCards() {
    cardsLoading.value = true
    try {
      stats.value = await fetchInvokeLogStatsSys()
    } catch {
      stats.value = null
    } finally {
      cardsLoading.value = false
    }
  }

  async function reloadStatusDistribution() {
    try {
      statusDistribution.value = await fetchDashboardStatusDistribution({ hours: 24 })
    } catch {
      statusDistribution.value = []
    }
  }

  async function reloadHourlyTrend() {
    try {
      hourlyTrend.value = await fetchDashboardHourlyTrend({ hours: 24 })
    } catch {
      hourlyTrend.value = []
    }
  }

  async function reloadTransportCostTrend() {
    try {
      transportCostTrend.value = await fetchDashboardTransportCostTrend({ hours: 24 })
    } catch {
      transportCostTrend.value = []
    }
  }

  async function reloadRecentCalls() {
    try {
      const page = await fetchInvokeLogsSys({ pageNum: 1, pageSize: 10 })
      recentCalls.value = page.records ?? []
    } catch {
      recentCalls.value = []
    }
  }

  async function reloadSlowRequests() {
    slowLoading.value = true
    try {
      slowRequests.value = await fetchDashboardSlowRequests({ hours: 24, limit: 10 })
    } catch {
      slowRequests.value = []
    } finally {
      slowLoading.value = false
    }
  }

  async function reloadAll() {
    isRefreshing.value = true
    try {
      await Promise.all([
        reloadCards(),
        reloadStatusDistribution(),
        reloadHourlyTrend(),
        reloadRecentCalls(),
        reloadTransportCostTrend(),
        reloadSlowRequests()
      ])
      lastRefreshAt.value = new Date()
    } finally {
      isRefreshing.value = false
    }
  }

  // 30s 轮询;window blur 停轮询避免浪费,focus 立刻拉一次
  let pollTimer: number | undefined
  function startPolling() {
    stopPolling()
    if (!autoRefresh.value) return
    pollTimer = window.setInterval(reloadAll, 30000)
  }
  function stopPolling() {
    if (pollTimer !== undefined) {
      window.clearInterval(pollTimer)
      pollTimer = undefined
    }
  }
  function onVisibilityChange() {
    if (document.hidden) {
      stopPolling()
    } else {
      reloadAll()
      startPolling()
    }
  }

  // ====================================================================
  // 格式化辅助
  // ====================================================================

  function formatMs(ms: number | null | undefined): string {
    const value = safeNumber(ms)
    if (value <= 0) return '0 ms'
    if (value < 1000) return `${Math.round(value)} ms`
    return `${(value / 1000).toFixed(2)} s`
  }

  function safeNumber(value: unknown): number {
    const n = Number(value ?? 0)
    return Number.isFinite(n) ? n : 0
  }

  function formatTime(iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  }

  function callTransportName(
    transportType: string | null | undefined,
    name: string | null | undefined,
    id: number | string | null | undefined
  ): string {
    return `${transportType || '—'}：${name || `#${id ?? '—'}`}`
  }

  function callStatus(call: Pick<ImawxInvokeLog, 'status'> | Pick<ImawxSlowRequestRow, 'success'>): string {
    if ('status' in call && call.status) return call.status
    if ('success' in call) return call.success === 1 ? 'SUCCESS' : 'FAILED'
    return 'FAILED'
  }

  function callStatusTagType(call: Pick<ImawxInvokeLog, 'status'> | Pick<ImawxSlowRequestRow, 'success'>): 'success' | 'warning' | 'danger' {
    const status = callStatus(call)
    if (status === 'SUCCESS') return 'success'
    if (status === 'TIMEOUT') return 'warning'
    return 'danger'
  }

  // ====================================================================
  // 生命周期
  // ====================================================================

  onMounted(() => {
    reloadAll()
    startPolling()
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  watch(autoRefresh, (enabled) => {
    if (enabled) {
      reloadAll()
      startPolling()
    } else {
      stopPolling()
    }
  })

  onBeforeUnmount(() => {
    stopPolling()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })
</script>

<style lang="scss" scoped>
  .imawx-dashboard {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding-bottom: 40px;
    box-sizing: border-box;

    &__cards {
      margin-bottom: 0;
    }

    &__realtime {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 14px 18px;
      border: 1px solid var(--el-border-color-light);
      border-radius: 8px;
      background: var(--el-bg-color);
      box-sizing: border-box;
    }

    &__realtime-main {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        line-height: 22px;
        color: var(--el-text-color-primary);
      }

      p {
        margin: 2px 0 0;
        font-size: 12px;
        line-height: 18px;
        color: var(--el-text-color-secondary);
      }
    }

    &__realtime-actions {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-shrink: 0;
    }

    &__live {
      margin-bottom: 0;
    }

    &__panel {
      min-height: 292px;
      padding: 16px 20px;
      box-sizing: border-box;

      &--tall {
        height: 610px;
        display: flex;
        flex-direction: column;
      }
    }

    &__recent-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-top: 14px;
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding-right: 4px;
    }

    &__recent-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      min-height: 44px;
      padding: 8px 0;
      border-bottom: 1px solid var(--el-border-color-lighter);
      box-sizing: border-box;

      &:last-child {
        border-bottom: 0;
      }
    }

    &__recent-row > div:first-child {
      flex: 1;
      min-width: 0;

      span,
      small {
        display: block;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      span {
        font-size: 13px;
        line-height: 20px;
        color: var(--el-text-color-primary);
      }

      small {
        margin-top: 2px;
        font-size: 12px;
        line-height: 16px;
        color: var(--el-text-color-secondary);
      }
    }

    &__recent-meta {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
      flex-shrink: 0;
      color: var(--el-text-color-secondary);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    &__recent-cost {
      min-width: 54px;
      text-align: right;
    }

    &__recent-time {
      min-width: 72px;
      color: var(--el-text-color-placeholder);
      text-align: right;
    }

    &__recent-tool {
      font-family:
        ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
      font-size: 12px;
    }

    &__row {
      margin-bottom: 32px;
    }

    &__chart {
      height: 340px;
      padding: 16px 20px;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
    }

    &__chart-body {
      position: relative;
      flex: 1;
      min-height: 0;
    }

    &__transport {
      height: 500px;
      padding: 16px 20px;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
    }

    &__transport-tags {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    }

    &__transport-body {
      flex: 1;
      min-height: 0;
      margin-top: 8px;
    }

    &__mono {
      font-family:
        ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
      font-size: 12px;

      &.is-muted {
        color: var(--el-text-color-placeholder);
      }
    }

    &__slow-cost {
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      color: var(--el-text-color-primary);

      &.is-slow {
        color: var(--el-color-warning);
      }
    }

    &__empty {
      height: 100%;
      min-height: 200px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
      color: var(--el-text-color-placeholder);
      font-size: 13px;

      &.is-compact {
        min-height: 126px;
      }
    }

    &__empty-icon {
      font-size: 40px;
      opacity: 0.4;
    }
  }

  @media (max-width: 768px) {
    .imawx-dashboard {
      &__realtime {
        align-items: flex-start;
        flex-direction: column;
      }

      &__realtime-actions {
        width: 100%;
        justify-content: space-between;
      }

      &__panel--tall {
        height: auto;
        min-height: 380px;
      }

      &__recent-list {
        max-height: 520px;
      }

      &__recent-row {
        align-items: flex-start;
        flex-direction: column;
      }

      &__recent-meta {
        justify-content: flex-start;
        flex-wrap: wrap;
      }
    }
  }
</style>

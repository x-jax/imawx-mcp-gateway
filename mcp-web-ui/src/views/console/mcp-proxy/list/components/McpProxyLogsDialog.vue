<!--
  MCP 服务调用日志抽屉。用于在服务卡片中查看单个 MCP 实例的审计日志。
-->
<template>
  <ElDrawer
    :model-value="visible"
    :title="title"
    direction="rtl"
    size="80%"
    :with-header="true"
    :close-on-click-modal="true"
    destroy-on-close
    body-class="imawx-mcp-proxy-logs-drawer"
    @update:model-value="(v) => emit('update:visible', v)"
    @open="onOpen"
  >
    <div class="imawx-mcp-proxy-logs imawx-mcp-proxy-logs__body">
      <ArtSearchBar
        v-if="showSearchBar"
        v-model="searchForm"
        :items="searchItems"
        :span="8"
        :show-expand="false"
        :default-expanded="true"
        @search="handleSearch"
        @reset="handleReset"
      />

      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:show-search-bar="showSearchBar"
        :loading="loading"
        full-class="imawx-mcp-proxy-logs"
        @refresh="reload"
      />

      <ArtTable
        class="imawx-mcp-proxy-logs__table"
        :loading="loading"
        :data="records"
        :columns="columns"
        :pagination="pagination"
        empty-text="该 MCP 暂无调用记录"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #toolName="{ row }">
          <ElTooltip
            placement="top"
            :disabled="!row.toolDescription"
            :content="row.toolDescription"
          >
            <span class="imawx-mcp-proxy-logs__tool-name">{{ row.toolName || '—' }}</span>
          </ElTooltip>
        </template>

        <template #status="{ row }">
          <span :class="['imawx-mcp-proxy-logs__tag', statusTagClass(row.status)]">
            {{ row.status || '—' }}
          </span>
        </template>

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

        <template #operation="{ row }">
          <ArtButtonTable type="view" :row="row" @click="openDetail(row)" />
        </template>
      </ArtTable>

      <ImawxJsonViewer
        v-model="jsonViewer.visible"
        :json="jsonViewer.payload"
        :title="jsonViewer.title"
      />
    </div>
  </ElDrawer>

  <ElDialog
    v-model="detailVisible"
    :title="`调用详情 #${detail?.id ?? ''}`"
    width="680px"
    top="8vh"
    class="imawx-dialog-fixed imawx-dialog-fixed--compact"
    :show-close="true"
    destroy-on-close
  >
    <div v-if="detail" class="imawx-mcp-proxy-logs__detail">
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">状态</span>
        <span :class="['imawx-mcp-proxy-logs__tag', statusTagClass(detail.status)]">
          {{ detail.status || '—' }}
        </span>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">调用时间</span>
        <span class="text-g-700">{{ formatTime(detail.invokedAt) }}</span>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">Mcp</span>
        <span class="text-g-700">{{ detail.serverName || props.serverName || '—' }}</span>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">Tool</span>
        <code class="text-g-700">{{ detail.toolName || '—' }}</code>
      </div>
      <div v-if="detail.toolDescription" class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">Tool 描述</span>
        <span class="text-g-700">{{ detail.toolDescription }}</span>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">耗时</span>
        <span class="text-g-700">{{ formatDuration(detail.costMs) }}</span>
      </div>
      <div v-if="detail.traceId" class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">Trace ID</span>
        <code class="text-g-700">{{ detail.traceId }}</code>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">用户邮箱</span>
        <span class="text-g-700">{{ detail.userEmail || '—' }}</span>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-row">
        <span class="imawx-mcp-proxy-logs__detail-key">Token 前缀</span>
        <code class="text-g-700">{{ detail.tokenPrefix || '—' }}</code>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-block">
        <div class="imawx-mcp-proxy-logs__detail-key">请求参数</div>
        <pre class="imawx-mcp-proxy-logs__pre">{{ formatJson(detail.argumentsJson) }}</pre>
      </div>
      <div class="imawx-mcp-proxy-logs__detail-block">
        <div class="imawx-mcp-proxy-logs__detail-key">响应结果</div>
        <pre class="imawx-mcp-proxy-logs__pre">{{ formatJson(detail.resultJson) }}</pre>
      </div>
      <div v-if="detail.streamLogsJson" class="imawx-mcp-proxy-logs__detail-block">
        <div class="imawx-mcp-proxy-logs__detail-key">流式日志</div>
        <pre class="imawx-mcp-proxy-logs__pre">{{ formatJson(detail.streamLogsJson) }}</pre>
      </div>
      <div v-if="detail.errorMessage" class="imawx-mcp-proxy-logs__detail-block">
        <div class="imawx-mcp-proxy-logs__detail-key">错误信息</div>
        <ElAlert type="error" :closable="false" class="!items-start">
          <pre class="imawx-mcp-proxy-logs__pre imawx-mcp-proxy-logs__pre--error">{{
            detail.errorMessage
          }}</pre>
        </ElAlert>
      </div>
    </div>
  </ElDialog>
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
  import { useConstants } from '@/composables/useConstants'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import ImawxJsonViewer from '@/components/imawx/ImawxJsonViewer.vue'

  type SupportedServerType = 'EXTERNAL'

  interface Props {
    visible: boolean
    serverId: string
    serverName?: string
    serverType?: SupportedServerType
  }
  const props = defineProps<Props>()
  const emit = defineEmits<{
    'update:visible': [boolean]
  }>()

  defineOptions({ name: 'ImawxMcpProxyLogsDialog' })

  const { getOptions } = useConstants()

  const title = computed(() => {
    const name = props.serverName ? `「${props.serverName}」` : ''
    return `调用日志 ${name}`
  })

  const searchForm = ref<{
    daterange?: string[]
    toolName?: string
    status?: ImawxInvokeStatus
  }>({
    daterange: undefined,
    toolName: undefined,
    status: undefined
  })

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
      key: 'toolName',
      label: 'Tool 名',
      type: 'input',
      props: { placeholder: '模糊搜索', clearable: true }
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

  const showSearchBar = ref(false)
  const records = ref<ImawxInvokeLog[]>([])

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
  const total = ref(0)
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const pagination = reactive({ current: 1, size: 10, total: 0 })

  const { columns, columnChecks } = useTableColumns<ImawxInvokeLog>(() => [
    {
      prop: 'invokedAt',
      label: '调用时间',
      width: 170,
      formatter: (row) => formatTime(row.invokedAt)
    },
    {
      prop: 'toolName',
      label: 'Tool',
      minWidth: 180,
      useSlot: true,
      slotName: 'toolName'
    },
    {
      prop: 'status',
      label: '状态',
      width: 100,
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
      prop: 'userAgent',
      label: 'User-Agent',
      minWidth: 200,
      showOverflowTooltip: true,
      formatter: (row) => row.userAgent || '—'
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

  const detailVisible = ref(false)
  const detail = ref<ImawxInvokeLog | null>(null)

  function buildQueryParams(): ImawxInvokeLogQuery {
    const sf = searchForm.value
    const params: ImawxInvokeLogQuery = {
      backendId: props.serverId,
      pageNum: pagination.current,
      pageSize: pagination.size
    }
    if (Array.isArray(sf.daterange) && sf.daterange.length === 2) {
      params.startTime = sf.daterange[0]
      params.endTime = sf.daterange[1]
    }
    if (sf.toolName) params.toolName = sf.toolName
    if (sf.status) params.status = sf.status
    return params
  }

  async function reload() {
    if (!props.serverId) {
      loadError.value = '未指定 MCP 服务 ID'
      return
    }
    loading.value = true
    loadError.value = null
    try {
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

  function handleSearch() {
    pagination.current = 1
    reload()
  }

  function handleReset() {
    pagination.current = 1
    searchForm.value = {
      daterange: undefined,
      toolName: undefined,
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

  async function openDetail(row: ImawxInvokeLog) {
    try {
      detail.value = await fetchInvokeLogDetailSys(row.id)
      detailVisible.value = true
    } catch {
      ElMessage.error('加载调用详情失败')
    }
  }

  function onOpen() {
    pagination.current = 1
    searchForm.value = {
      daterange: undefined,
      toolName: undefined,
      status: undefined
    }
    reload()
  }

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

  function formatTime(iso?: string): string {
    if (!iso) return '—'
    return iso.replace('T', ' ')
  }

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

  function formatJson(s?: string | null): string {
    if (!s) return '（空）'
    try {
      return JSON.stringify(JSON.parse(s), null, 2)
    } catch {
      return s
    }
  }

</script>

<style lang="scss">
  .el-drawer__body.imawx-mcp-proxy-logs-drawer {
    display: flex;
    flex-direction: column;
  }
  .el-drawer__body.imawx-mcp-proxy-logs-drawer {
    flex: 1;
    min-height: 0;
    overflow: hidden;
    padding: 16px 20px;
  }
  .el-drawer__body.imawx-mcp-proxy-logs-drawer .imawx-mcp-proxy-logs__body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  /* ArtTable 容器(flex column 里 flex: 1 撑满剩余空间)—— 用 CSS Grid 让内部
     el-table 撑满 + pag 吸底。强制 height: 100% !important 覆盖基座 useTableHeight
     算的 calc(100% - 82px),让容器高度跟父 flex 联动,而不是被基座 offset 算小。
     选择器用 .el-drawer__body.imawx-mcp-proxy-logs-drawer(命名空间) */
  .el-drawer__body.imawx-mcp-proxy-logs-drawer
    .imawx-mcp-proxy-logs__table {
    flex: 1 1 0%;
    min-height: 0;
  }
  .el-drawer__body.imawx-mcp-proxy-logs-drawer
    .imawx-mcp-proxy-logs__table.art-table {
    display: grid !important;
    grid-template-rows: 1fr auto !important;
    height: 100% !important;
    min-height: 0;
  }
  .el-drawer__body.imawx-mcp-proxy-logs-drawer
    .imawx-mcp-proxy-logs__table .el-table {
    height: auto !important;
    min-height: 0;
  }
  .el-drawer__body.imawx-mcp-proxy-logs-drawer
    .imawx-mcp-proxy-logs__table .pagination {
    margin-top: 0 !important;
    padding-top: 12px;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .imawx-mcp-proxy-logs {
    // 状态 chip
    &__tag {
      display: inline-flex;
      align-items: center;
      padding: 1px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
      line-height: 1.6;
      border: 1px solid transparent;
    }
    &__tag.is-ok {
      background: var(--el-color-success-light-9);
      color: var(--el-color-success);
      border-color: var(--el-color-success-light-7);
    }
    &__tag.is-err {
      background: var(--el-color-danger-light-9);
      color: var(--el-color-danger);
      border-color: var(--el-color-danger-light-7);
    }
    &__tag.is-timeout {
      background: var(--el-color-warning-light-9);
      color: var(--el-color-warning);
      border-color: var(--el-color-warning-light-7);
    }
    &__tag.is-unknown {
      background: var(--el-fill-color-light);
      color: var(--el-text-color-placeholder);
      border-color: var(--el-border-color-lighter);
    }

    &__tool-name {
      display: inline-block;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      vertical-align: bottom;
      white-space: nowrap;
    }

    &__detail {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    &__detail-row {
      display: grid;
      grid-template-columns: 92px 1fr;
      align-items: start;
      gap: 12px;
      min-height: 24px;
    }

    &__detail-key {
      color: var(--el-text-color-secondary);
      font-size: 13px;
    }

    &__detail-block {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-top: 4px;
    }

    &__pre {
      max-height: 260px;
      margin: 0;
      padding: 10px 12px;
      overflow: auto;
      color: var(--el-text-color-regular);
      background: var(--el-fill-color-light);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 6px;
      font-size: 12px;
      line-height: 1.6;
      white-space: pre-wrap;
      word-break: break-word;
    }

    &__pre--error {
      max-height: 180px;
      padding: 0;
      background: transparent;
      border: 0;
      color: inherit;
    }
  }
</style>

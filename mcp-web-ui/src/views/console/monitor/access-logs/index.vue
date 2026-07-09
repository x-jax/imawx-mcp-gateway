<template>
  <div class="imawx-access-logs flex flex-col gap-3 art-full-height">
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

    <ElCard class="art-table-card flex-1 min-h-0" shadow="never">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:show-search-bar="showSearchBar"
        :loading="loading"
        full-class="imawx-access-logs"
        @refresh="reload"
      >
        <template #left>
          <ElTag v-if="loadError" type="danger" effect="light">{{ loadError }}</ElTag>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :pagination="pagination"
        :data="records"
        :columns="columns"
        empty-text="暂无访问记录"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #result="{ row }">
          <span :class="['imawx-access-logs__tag', resultTagClass(row.result)]">
            {{ row.result || '—' }}
          </span>
        </template>

        <template #status="{ row }">
          <span :class="['imawx-access-logs__status', statusClass(row.status)]">
            {{ row.status }}
          </span>
        </template>

      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchAccessLogsSys,
    type ImawxAccessLog,
    type ImawxAccessLogQuery,
    type ImawxAccessLogResult
  } from '@/api/sys/access-logs'
  import { useTableColumns } from '@/hooks/core/useTableColumns'

  defineOptions({ name: 'ImawxAccessLogs' })

  const showSearchBar = ref(false)
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const records = ref<ImawxAccessLog[]>([])
  const pagination = reactive({ current: 1, size: 20, total: 0 })

  const searchForm = ref<{
    daterange?: string[]
    keyword?: string
    userEmail?: string
    ip?: string
    method?: string
    result?: ImawxAccessLogResult
    status?: number
  }>({
    daterange: undefined,
    keyword: undefined,
    userEmail: undefined,
    ip: undefined,
    method: undefined,
    result: undefined,
    status: undefined
  })

  const methodOptions = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'HEAD'].map((v) => ({
    label: v,
    value: v
  }))

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
      key: 'keyword',
      label: '关键词',
      type: 'input',
      props: { placeholder: '路径 / User-Agent / Token 前缀', clearable: true }
    },
    {
      key: 'userEmail',
      label: '邮箱',
      type: 'input',
      props: { placeholder: '登录账号邮箱', clearable: true }
    },
    {
      key: 'ip',
      label: 'IP',
      type: 'input',
      props: { placeholder: '模糊搜索 IP', clearable: true }
    },
    {
      key: 'method',
      label: '方法',
      type: 'select',
      props: { placeholder: '全部', clearable: true, options: methodOptions }
    },
    {
      key: 'result',
      label: '结果',
      type: 'select',
      props: {
        placeholder: '全部',
        clearable: true,
        options: [
          { label: '成功', value: 'SUCCESS' },
          { label: '失败', value: 'FAILED' }
        ]
      }
    },
    {
      key: 'status',
      label: '状态码',
      type: 'number',
      props: { placeholder: '如 403', min: 100, max: 599, controls: false, style: 'width: 100%' }
    }
  ])

  const { columns, columnChecks } = useTableColumns<ImawxAccessLog>(() => [
    {
      prop: 'createTime',
      label: '时间',
      width: 170,
      formatter: (row) => formatTime(row.createTime)
    },
    { prop: 'ip', label: 'IP', width: 150, showOverflowTooltip: true },
    { prop: 'method', label: '方法', width: 90, align: 'center' },
    { prop: 'uri', label: '路径', minWidth: 240, showOverflowTooltip: true },
    {
      prop: 'result',
      label: '结果',
      width: 100,
      align: 'center',
      useSlot: true,
      slotName: 'result'
    },
    {
      prop: 'status',
      label: '状态码',
      width: 100,
      align: 'center',
      useSlot: true,
      slotName: 'status'
    },
    {
      prop: 'costMs',
      label: '耗时',
      width: 120,
      align: 'right',
      formatter: (row) => formatDuration(row.costMs)
    },
    { prop: 'userAgent', label: 'User-Agent', minWidth: 240, showOverflowTooltip: true },
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
    }
  ])

  function buildQueryParams(): ImawxAccessLogQuery {
    const sf = searchForm.value
    const params: ImawxAccessLogQuery = {
      pageNum: pagination.current,
      pageSize: pagination.size
    }
    if (Array.isArray(sf.daterange) && sf.daterange.length === 2) {
      params.startTime = sf.daterange[0]
      params.endTime = sf.daterange[1]
    }
    if (sf.keyword) params.keyword = sf.keyword
    if (sf.userEmail) params.userEmail = sf.userEmail
    if (sf.ip) params.ip = sf.ip
    if (sf.method) params.method = sf.method
    if (sf.result) params.result = sf.result
    if (sf.status) params.status = sf.status
    return params
  }

  async function reload() {
    loading.value = true
    loadError.value = null
    try {
      const page = await fetchAccessLogsSys(buildQueryParams())
      records.value = page?.records ?? []
      pagination.total = page?.total ?? 0
    } catch (e) {
      records.value = []
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
      keyword: undefined,
      userEmail: undefined,
      ip: undefined,
      method: undefined,
      result: undefined,
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

  function formatTime(iso?: string): string {
    return iso ? iso.replace('T', ' ') : '—'
  }

  function formatDuration(ms?: number | null): string {
    if (ms == null) return '—'
    const total = Math.max(0, Math.floor(ms))
    if (total < 1000) return `${total}ms`
    const seconds = Math.floor(total / 1000)
    const millis = total % 1000
    return `${seconds}s${String(millis).padStart(3, '0')}ms`
  }

  function resultTagClass(result?: string) {
    return result === 'SUCCESS' ? 'is-success' : 'is-danger'
  }

  function statusClass(status?: number) {
    if (!status) return ''
    if (status >= 500) return 'is-danger'
    if (status >= 400) return 'is-warning'
    return 'is-success'
  }

  onMounted(reload)
</script>

<style scoped lang="scss">
  .imawx-access-logs {
    min-height: 0;
  }

  .imawx-access-logs__tag,
  .imawx-access-logs__status {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 72px;
    height: 24px;
    padding: 0 8px;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 600;
    line-height: 1;
  }

  .imawx-access-logs__tag.is-success,
  .imawx-access-logs__status.is-success {
    color: var(--el-color-success);
    background: var(--el-color-success-light-9);
  }

  .imawx-access-logs__tag.is-danger,
  .imawx-access-logs__status.is-danger {
    color: var(--el-color-danger);
    background: var(--el-color-danger-light-9);
  }

  .imawx-access-logs__status.is-warning {
    color: var(--el-color-warning);
    background: var(--el-color-warning-light-9);
  }
</style>

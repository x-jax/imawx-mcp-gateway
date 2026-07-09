<!--
  imawx-mcp 外部 MCP Server 详情页

  对应 prd.md 第 7 章 7.2.4 规格：
  - 顶部：基本信息（只读）
  - 中部：当前连接状态 + 最近一次测试结果
  - 下部：该 MCP 提供的 Tools 列表（Tool 名 / 描述 / 参数 Schema）
  - 操作：重新测试 / 启用 / 禁用 / 编辑
-->
<template>
  <div class="imawx-mcp-proxy-detail">
    <header class="imawx-mcp-proxy-detail__header">
      <ElButton link @click="goBack">← 返回列表</ElButton>
      <h2 class="imawx-mcp-proxy-detail__title">
        <!-- 头部 icon:跟 list 卡片一致 ——
             svgl brand icon(具体品牌) 优先,fallback transport icon -->
        <img
          v-if="detail && brandIconOf(detail.serverName)"
          :src="`/svgl-icons/${brandIconOf(detail.serverName)!.route}`"
          :alt="detail.serverName"
          :title="brandIconOf(detail.serverName)!.title"
          class="imawx-mcp-proxy-detail__brand-icon"
        />
        <ArtSvgIcon
          v-else-if="detail"
          :icon="transportIcon(detail.transportType)"
          :style="{ color: transportColor(detail.transportType) }"
          class="imawx-mcp-proxy-detail__transport-icon"
        />
        {{ detail?.serverName ?? (loading ? '加载中…' : 'MCP 服务不存在') }}
      </h2>
      <div v-if="detail && !loading" class="imawx-mcp-proxy-detail__actions">
        <ElButton :loading="actionLoading.test" @click="handleTest">重新测试</ElButton>
        <ElButton
          v-if="detail?.enabled === 0"
          type="success"
          :loading="actionLoading.enable"
          @click="handleEnable"
        >
          启用
        </ElButton>
        <ElButton v-else :loading="actionLoading.disable" @click="handleDisable"> 禁用 </ElButton>
        <ElButton type="primary" @click="openEdit">编辑</ElButton>
      </div>
    </header>

    <ElCard v-if="loading" shadow="never" class="imawx-mcp-proxy-detail__card">
      <ElSkeleton :rows="6" animated />
    </ElCard>

    <ElCard v-else-if="!detail" shadow="never" class="imawx-mcp-proxy-detail__card">
      <ElEmpty :description="errorMessage || 'MCP 服务不存在或已删除'" :image-size="96">
        <ElButton type="primary" @click="goBack">返回 MCP 列表</ElButton>
      </ElEmpty>
    </ElCard>

    <!-- 基本信息 -->
    <ElCard v-if="detail && !loading" shadow="never" class="imawx-mcp-proxy-detail__card">
      <template #header><b>基本信息</b></template>
      <ElDescriptions :column="2" border size="default">
        <ElDescriptionsItem label="服务 ID">#{{ detail.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="服务名">{{ detail.serverName }}</ElDescriptionsItem>
        <ElDescriptionsItem label="传输类型">
          <ElTag :type="transportTagType(detail.transportType)" size="small">{{
            detail.transportType
          }}</ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="启用状态">
          <ElTag :type="detail.enabled === 1 ? 'success' : 'info'" size="small">
            {{ detail.enabled === 1 ? '已启用' : '已禁用' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Endpoint" :span="2">
          <span class="imawx-mcp-proxy-detail__mono">{{ detail.endpoint }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="detail.extraConfig" label="扩展配置" :span="2">
          <pre class="imawx-mcp-proxy-detail__json">{{ formatJson(detail.extraConfig) }}</pre>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="detail.remark" label="备注" :span="2">
          <span class="imawx-mcp-proxy-detail__remark">{{ detail.remark }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ formatTime(detail.createdAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="更新时间">{{ formatTime(detail.updatedAt) }}</ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <!-- 连接状态 -->
    <ElCard v-if="detail && !loading" shadow="never" class="imawx-mcp-proxy-detail__card">
      <template #header>
        <b>连接状态</b>
        <span class="imawx-mcp-proxy-detail__last-check">
          最近测试：{{ detail.lastCheckAt ? formatTime(detail.lastCheckAt) : '从未' }}
          <template v-if="detail.lastSyncAt">
            · 最近同步：{{ formatTime(detail.lastSyncAt) }}
          </template>
        </span>
      </template>
      <!-- 优先级:lastSyncError 存在 → 同步失败(独立 alert,跟 test 失败分开);
           否则 status=1 → 成功;status=2 + lastError → 测试失败;否则未连接 -->
      <template v-if="detail.lastSyncError">
        <ElAlert type="error" :closable="false" title="同步失败" show-icon>
          <pre class="imawx-mcp-proxy-detail__error">{{ detail.lastSyncError }}</pre>
          <div class="imawx-mcp-proxy-detail__error-hint">
            listTools 拉取失败，快照未更新。检查第三方 MCP Server 是否可达，回到列表页点「同步」重试。
          </div>
        </ElAlert>
        <ElAlert
          v-if="detail.lastError"
          class="imawx-mcp-proxy-detail__error-secondary"
          type="warning"
          :closable="false"
          title="测试连接也失败"
          show-icon
        >
          <pre class="imawx-mcp-proxy-detail__error">{{ detail.lastError }}</pre>
        </ElAlert>
      </template>
      <ElAlert
        v-else-if="detail.status === 'CONNECTED'"
        type="success"
        :closable="false"
        title="已连接"
        show-icon
      >
        最近一次测试成功，本地 MCP Server 已加载该服务的 Tool 列表。
      </ElAlert>
      <ElAlert
        v-else-if="detail.status === 'FAILED' && detail.lastError"
        type="error"
        :closable="false"
        title="测试连接失败"
        show-icon
      >
        <pre class="imawx-mcp-proxy-detail__error">{{ detail.lastError }}</pre>
      </ElAlert>
      <ElAlert v-else type="info" :closable="false" title="未连接" show-icon>
        点右上角"重新测试"验证配置是否正确。
      </ElAlert>
    </ElCard>

    <!-- Tool 列表 -->
    <ElCard v-if="detail && !loading" shadow="never" class="imawx-mcp-proxy-detail__card">
      <template #header>
        <b>Tools（{{ tools.length }}）</b>
        <span class="imawx-mcp-proxy-detail__last-check">
          本地注册名：<code>ext_&lt;{{ detail.id }}&gt;_&lt;original&gt;</code>
        </span>
      </template>
      <template v-if="tools.length === 0">
        <ElEmpty
          :description="
            detail.lastCheckAt
              ? '已测试但未发现 Tool'
              : '尚未测试，点右上角 &quot;重新测试&quot; 发现 Tool'
          "
          :image-size="80"
        />
      </template>
      <template v-else>
        <div v-for="tool in tools" :key="tool.name" class="imawx-mcp-proxy-detail__tool">
          <div class="imawx-mcp-proxy-detail__tool-head">
            <span class="imawx-mcp-proxy-detail__tool-name">{{ tool.name }}</span>
            <ElTag v-if="tool.inputSchema" size="small" type="info">含参数 Schema</ElTag>
          </div>
          <p v-if="tool.description" class="imawx-mcp-proxy-detail__tool-desc">
            {{ tool.description }}
          </p>
          <pre v-if="tool.inputSchema" class="imawx-mcp-proxy-detail__json">{{
            formatJson(tool.inputSchema)
          }}</pre>
        </div>
      </template>
    </ElCard>

    <McpProxyFormDialog v-if="detail" v-model:visible="editDialogVisible" :record="detail" @saved="reload" />
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    disableMcpProxySys,
    enableMcpProxySys,
    fetchMcpProxyDetailSys,
    testMcpProxySys,
    type ImawxMcpProxy,
    type ImawxMcpProxyToolPreview
  } from '@/api/sys/mcp-proxy'
  import { matchByServerName } from '@/composables/useSvglIcons'
  import type { SvglIconEntry } from '@/api/sys/icons'
  import McpProxyFormDialog from '../list/components/McpProxyFormDialog.vue'

  defineOptions({ name: 'ImawxMcpProxyDetail' })

  const route = useRoute()
  const router = useRouter()

  // id 用 string：雪花 ID 必须 string，避免 Number 精度丢失（同 list 修复）
  const id = computed(() => String(route.params.id ?? ''))
  const detail = ref<ImawxMcpProxy | null>(null)
  const tools = ref<ImawxMcpProxyToolPreview[]>([])
  const loading = ref(false)
  const errorMessage = ref('')
  const actionLoading = reactive({
    test: false,
    enable: false,
    disable: false
  })
  const editDialogVisible = ref(false)

  async function reload() {
    if (!id.value || id.value === 'undefined' || id.value === 'null') {
      detail.value = null
      tools.value = []
      errorMessage.value = '无效的 MCP 服务 ID'
      return
    }
    loading.value = true
    errorMessage.value = ''
    try {
      const vo = await fetchMcpProxyDetailSys(id.value)
      detail.value = vo
      // 详情接口不一定回填 tools（有就展示，没有就让用户点测试）
      tools.value = vo.tools ?? []
    } catch (e) {
      detail.value = null
      tools.value = []
      errorMessage.value = (e as Error)?.message || 'MCP 服务加载失败'
    } finally {
      loading.value = false
    }
  }

  function goBack() {
    router.push({ name: 'ImawxMcpProxyList' })
  }

  function openEdit() {
    editDialogVisible.value = true
  }

  async function handleTest() {
    actionLoading.test = true
    try {
      const list = await testMcpProxySys(id.value)
      tools.value = list
      ElMessage.success(`测试通过，发现 ${list.length} 个 Tool`)
      await reload()
    } catch {
      // 错误已弹
    } finally {
      actionLoading.test = false
    }
  }

  async function handleEnable() {
    actionLoading.enable = true
    try {
      await enableMcpProxySys(id.value)
      await reload()
    } catch {
      // 错误已弹
    } finally {
      actionLoading.enable = false
    }
  }

  async function handleDisable() {
    actionLoading.disable = true
    try {
      await disableMcpProxySys(id.value)
      await reload()
    } catch {
      // 错误已弹
    } finally {
      actionLoading.disable = false
    }
  }

  function formatTime(iso?: string): string {
    if (!iso) return '—'
    return iso.replace('T', ' ').slice(0, 19)
  }

  function formatJson(s: string): string {
    try {
      return JSON.stringify(JSON.parse(s), null, 2)
    } catch {
      return s
    }
  }

  function transportTagType(t?: string): 'primary' | 'success' | 'warning' {
    switch (t) {
      case 'HTTP':
        return 'primary'
      case 'SSE':
        return 'success'
      case 'STDIO':
        return 'warning'
      case 'DB':
      case 'REDIS':
      case 'OPENAPI':
        return 'success'
      default:
        return 'primary'
    }
  }

  /**
   * 头部 icon(品牌 → transport)匹配:跟 list 卡片一致逻辑,见 composable useSvglIcons 注释。
   */
  function brandIconOf(name: string | undefined): SvglIconEntry | null {
    return matchByServerName(name)
  }

  /**
   * transport icon(同 list 卡片 transportIcon):fallback 用。
   */
  function transportIcon(t?: string): string {
    switch (t) {
      case 'HTTP':
        return 'lucide:globe'
      case 'SSE':
        return 'lucide:antenna'
      case 'STDIO':
        return 'lucide:terminal'
      case 'DB':
        return 'ri:database-2-line'
      case 'REDIS':
        return 'ri:database-line'
      case 'ALIYUN_DNS':
        return 'ri:global-line'
      case 'ALIYUN_OSS':
        return 'ri:archive-2-line'
      case 'OPENAPI':
        return 'ri:file-code-line'
      default:
        return 'lucide:server'
    }
  }

  function transportColor(t?: string): string {
    switch (t) {
      case 'HTTP':
        return '#3B82F6'
      case 'SSE':
        return '#8B5CF6'
      case 'STDIO':
        return '#10B981'
      case 'DB':
        return '#F59E0B'
      case 'REDIS':
        return '#DC2626'
      case 'ALIYUN_DNS':
        return '#2563EB'
      case 'ALIYUN_OSS':
        return '#EA580C'
      case 'OPENAPI':
        return '#0F766E'
      default:
        return 'var(--el-text-color-placeholder)'
    }
  }

  onMounted(reload)
</script>

<style scoped lang="scss">
  .imawx-mcp-proxy-detail {
    &__header {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 16px;
      flex-wrap: wrap;

      h2 {
        margin: 0;
        font-size: 20px;
        font-weight: 600;
        flex: 1;
      }
    }

    /* 标题里嵌的 icon:跟 list 卡片风格统一 */
    &__title {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    &__brand-icon {
      width: 28px;
      height: 28px;
      object-fit: contain;
    }
    &__transport-icon {
      font-size: 28px;
    }

    &__actions {
      display: flex;
      gap: 8px;
    }

    &__card {
      margin-bottom: 16px;
    }

    &__last-check {
      margin-left: 12px;
      font-size: 13px;
      font-weight: normal;
      color: var(--el-text-color-secondary);

      code {
        background: var(--el-fill-color-light);
        padding: 1px 6px;
        border-radius: 3px;
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
        font-size: 12px;
      }
    }

    &__mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
    }

    &__json {
      margin: 0;
      padding: 12px;
      background: var(--el-fill-color-light);
      border-radius: 4px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      line-height: 1.5;
      max-height: 320px;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-all;
    }

    &__error {
      margin: 0;
      padding: 0;
      background: transparent;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      white-space: pre-wrap;
      word-break: break-all;
      color: var(--el-color-error);
    }

    /* 同步失败 alert 下面的 hint:跟 test 失败的 alert 隔开,提示"这是同步失败不是测试失败" */
    &__error-hint {
      margin-top: 8px;
      font-size: 12px;
      color: var(--el-text-color-regular);
      line-height: 1.5;
    }

    /* 同步失败主 alert 下面,如果 test 也有错,跟一个 warning 二级 alert(说明连接本身也挂) */
    &__error-secondary {
      margin-top: 12px;
    }

    /* 备注(在基本信息卡里,跟其他描述信息视觉一致,不强制 monospace 字体) */
    &__remark {
      font-size: 13px;
      line-height: 1.6;
      color: var(--el-text-color-regular);
      white-space: pre-wrap;
      word-break: break-word;
    }

    &__tool {
      padding: 12px;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 4px;
      margin-bottom: 8px;

      &-head {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 6px;
      }

      &-name {
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
        font-weight: 600;
        font-size: 14px;
      }

      &-desc {
        margin: 4px 0;
        font-size: 13px;
        color: var(--el-text-color-secondary);
        line-height: 1.5;
      }
    }
  }
</style>

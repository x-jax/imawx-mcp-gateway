<!--
  imawx-mcp MCP 服务管理（卡片网格风格，照搬 awx-ui mcp-server/index.vue）

  对应 prd.md 第 7 章 7.2.3 规格：
  - 顶部 ArtSearchBar 搜索（默认隐藏，点工具栏搜索按钮展开）
  - 工具栏：新增 / 刷新 / 搜索切换（实心 icon 按钮风格）
  - 卡片网格：动态列数（useCardColumns）
  - 卡片行：服务名 / endpoint / transport / 状态 / 启用 / 操作
  - 底部分页（ElPagination 居中）

  数据源：imawx 自己的 /api/sys/mcp-proxy（字段：serverName/endpoint/transportType/status/enabled/updatedAt）
  注意：imawx 后端没 toolCount / healthStatus / description 字段，所以对应格子不显示
-->
<template>
  <div class="mcp-page art-full-height">
    <!-- 1) 搜索区：默认隐藏，点工具栏搜索按钮展开 -->
    <ArtSearchBar
      v-if="searchVisible"
      v-model="searchForm"
      :items="searchItems"
      :span="8"
      label-width="60px"
      @reset="handleReset"
      @search="handleSearch"
    />

    <!-- 2) 内容区：toolbar + 卡片网格 + 底部分页 -->
    <!-- :body-style 显式设 padding:0 + flex column,跟 DB 页面的 .db-body :deep(.el-card__body) 等效;
         (MCP 这里用 wrapper 多包了一层,原 .mcp-body :deep(.el-card__body) CSS 选择器路径错,
         .el-card__body 实际是 .mcp-body 祖先不是后代,匹配 0 个元素 → 默认 20px padding 一直没被覆盖) -->
    <ElCard
      class="art-table-card"
      shadow="never"
      :body-style="cardBodyStyle"
    >
      <div class="mcp-body">
      <!-- 2.1 toolbar：三个按钮全在左边一排,视觉跟基座 .button 完全同源
           (size-8 rounded-md bg-g-300/55 等 Tailwind utility,无 scoped CSS)。
           ArtTableHeader 是 flex-cb 左右分布,会把次操作顶到右边,不符合此页布局要求 -->
      <div class="mcp-toolbar flex items-center gap-2 px-4 py-3 border-b border-[var(--default-border)]">
        <!-- 新增主操作：实心蓝 -->
        <div
          class="size-8 flex items-center justify-center cursor-pointer rounded-md bg-primary text-white hover:bg-primary/80"
          title="新增 MCP 服务"
          @click="openCreate"
        >
          <ArtSvgIcon icon="ri:add-fill" />
        </div>
        <!-- 搜索切换：基座浅灰 -->
        <div
          class="size-8 flex items-center justify-center cursor-pointer rounded-md bg-g-300/55 text-g-700 hover:bg-g-300"
          :title="searchVisible ? '收起搜索' : '展开搜索'"
          @click="searchVisible = !searchVisible"
        >
          <ArtSvgIcon :icon="searchVisible ? 'ri:search-eye-line' : 'ri:search-line'" />
        </div>
        <!-- 刷新：基座浅灰,loading 时 spin -->
        <div
          class="size-8 flex items-center justify-center cursor-pointer rounded-md bg-g-300/55 text-g-700 hover:bg-g-300"
          :class="{ 'pointer-events-none opacity-60': loading }"
          title="刷新"
          @click="reload"
        >
          <ArtSvgIcon icon="ri:refresh-line" :class="{ 'animate-spin': loading }" />
        </div>
      </div>

      <!-- 2.2 卡片网格 -->
      <div
        v-loading="loading"
        ref="mcpGridRef"
        class="mcp-grid"
        :style="{ gridTemplateColumns: `repeat(${cardCols}, 1fr)` }"
      >
        <div
          v-for="item in list"
          :key="item.id"
          class="art-card mcp-card"
          :class="cardClass(item)"
          @click="openDrawer(item)"
        >
          <!-- 头部：transport icon + 名称 + endpoint -->
          <div class="mcp-card-head">
            <div class="mcp-card-icon">
              <!-- 优先 svgl brand icon(serverName 拆词匹配到具体品牌,例 "GitHub MCP" → GitHub logo);
                   没匹配上 fallback 到 transport icon(HTTP/SSE/STDIO 通用 icon) -->
              <img
                v-if="shouldUseServerBrand(item)"
                :src="`/svgl-icons/${brandIconOf(item.serverName)!.route}`"
                :alt="item.serverName"
                :title="brandIconOf(item.serverName)!.title"
                class="mcp-card-brand-icon"
              />
              <ArtSvgIcon
                v-else
                :icon="displayIcon(item)"
                :style="{ color: transportColor(item.transportType) }"
                class="text-3xl"
              />
            </div>
            <div class="min-w-0 flex-1 ml-3">
              <h3 class="mcp-card-title text-g-800" :title="item.serverName">{{ item.serverName }}</h3>
              <span class="mcp-card-meta text-g-500" :title="item.endpoint">
                {{ item.endpoint || '未配置地址' }}
              </span>
              <!-- 2026-07-01 加:用户标签 chips。最多展示前 5 个 + "..." 提示;hover 完整 list。
                   卡片里只展示,不展示 closeable —— 编辑/删除走卡片底部"编辑"按钮进 dialog。 -->
              <div v-if="item.tags && item.tags.length > 0" class="mcp-card-tags">
                <ElTag
                  v-for="tag in item.tags.slice(0, 5)"
                  :key="tag"
                  size="small"
                  :type="tagColor(tag)"
                  effect="light"
                  :disable-transitions="true"
                  class="mcp-card-tag"
                >
                  {{ tag }}
                </ElTag>
                <ElTooltip
                  v-if="item.tags.length > 5"
                  placement="top"
                  :show-after="200"
                  :content="item.tags.slice(5).join('、')"
                >
                  <span class="mcp-card-tags__more">+{{ item.tags.length - 5 }}</span>
                </ElTooltip>
              </div>
            </div>
          </div>

          <!-- 描述区拆成两段:
               ① 备注 (有 remark 才有):3 行截断 + hover tooltip
               ② 状态/异常信息:2 行截断,4 个优先级 —
                  lastSyncError → 红色「同步失败:XXX (YYYY-MM-DD HH:MM:SS)」
                  lastError(FAILED) → 红色「测试失败:XXX (YYYY-MM-DD HH:MM:SS)」
                  正常 → 「最近测试:YYYY-MM-DD HH:MM:SS · 最近同步:YYYY-MM-DD HH:MM:SS」/「尚未同步」
                  兜底 → 「尚未测试」

               时间带日期:用户做调试看「最近测试」时分秒一样看不出是哪天,加上日期一眼能区分。

               之前只有 lastError 一个字段,test 失败和 sync 失败都写它,
               卡片 desc 看不出「这次状态=2 到底是测试挂的还是同步挂的」。
               现在分两个字段 → 用户一眼能区分。

               拆成两段是因为备注 3 行 / 异常 2 行需要不同上限,
               同一个 <p> 用 line-clamp 没法给两段设不同的值。 -->

          <p v-if="item.remark" class="mcp-card-remark">
            <ElTooltip :content="item.remark" placement="top" :show-after="300">
              <span class="mcp-card-remark__text">{{ item.remark }}</span>
            </ElTooltip>
          </p>

          <!-- 状态 / 异常信息:2 行截断。
               顺序：lastSyncError → lastError(test 失败) → 正常最近测试/同步 → 尚未同步。
               2026-07-01 改:之前外层 lastCheckAt 守卫导致 lastSyncAt 有值但 lastCheckAt 没值时
               (比如只 sync 没 test) 整个分支被跳过,desc 显示"尚未测试"。
               现在按"是否有有效数据"分支:
               - 有 lastSyncAt → 显示同步时间
                 + 若还有 lastCheckAt,前缀"最近测试时间 ·"补充上下文
               - 只有 lastCheckAt → 显示最近测试时间(只 test 没 sync)
               - 都没有 → "尚未同步" -->
          <p
            class="mcp-card-status"
            :class="{
              'text-g-500': !item.lastSyncError && !item.lastError,
              'text-error': !!item.lastSyncError || (item.status === 'FAILED' && !!item.lastError)
            }"
          >
            <template v-if="item.lastSyncError">
              同步失败：{{ item.lastSyncError }}<template v-if="item.lastCheckAt">（{{ formatTime(item.lastCheckAt) }}）</template>
            </template>
            <template v-else-if="item.status === 'FAILED' && item.lastError">
              测试失败：{{ item.lastError }}<template v-if="item.lastCheckAt">（{{ formatTime(item.lastCheckAt) }}）</template>
            </template>
            <template v-else-if="item.lastSyncAt">
              <template v-if="item.lastCheckAt">
                最近测试：{{ formatTime(item.lastCheckAt) }} ·
              </template>
              最近同步：{{ formatTime(item.lastSyncAt) }}
            </template>
            <template v-else-if="item.lastCheckAt">
              最近测试：{{ formatTime(item.lastCheckAt) }}
            </template>
            <template v-else>尚未同步</template>
          </p>

          <!-- chips：transport / 状态 / 启用 -->
          <div class="mcp-card-binds">
            <span class="bind-chip" :style="{ color: transportColor(item.transportType) }">
              <ArtSvgIcon :icon="transportIcon(item.transportType)" />
              <span class="lbl">{{ item.transportType }}</span>
            </span>
            <span class="bind-chip" :class="healthClass(item.status)">
              <ArtSvgIcon :icon="healthIcon(item.status)" />
              <span class="lbl">{{ statusLabel(item.status) }}</span>
            </span>
            <span class="bind-chip" :class="item.enabled === 1 ? 'is-on' : 'is-off'">
              <ArtSvgIcon :icon="item.enabled === 1 ? 'ri:check-line' : 'ri:close-line'" />
              <span class="lbl">{{ item.enabled === 1 ? '已启用' : '已禁用' }}</span>
            </span>
          </div>

          <!-- 底部:状态开关 + 编辑/日志/删除
               2026-06-28:测试按钮从卡片 actions 移除 —— 移到新增/编辑弹窗里,
               要求测试通过才能保存(用户反馈)。卡片上保留编辑入口,
               抽屉 head 还有「测试」+「同步」,用户测试已注册服务的渠道没断。
               2026-07-01:再移除卡片 actions 的「同步」按钮 —— 新增时后端会自动同步一次,
               老卡片如果远端 tool 有更新,需要手动同步走抽屉 head 按钮(单次操作不算高频)。
               卡片只保留编辑/日志/删除三个低频管理操作,抽屉提供详情调试入口。 -->
          <div class="mcp-card-actions">
            <ElSwitch
              :model-value="item.enabled === 1"
              :loading="statusLoading === item.id"
              size="small"
              active-text=""
              inactive-text=""
              inline-prompt
              @click.stop
              @change="(v) => toggleEnabled(item, !!v)"
            />
            <ElSpace :size="4">
              <ElButton
                text
                size="small"
                :loading="editLoading && editingId === item.id"
                @click.stop="openEdit(item)"
              >
                编辑
              </ElButton>
              <ElButton text size="small" @click.stop="openLogs(item)">日志</ElButton>
              <ElButton text size="small" type="danger" @click.stop="handleDelete(item)">删除</ElButton>
            </ElSpace>
          </div>
        </div>

        <div v-if="!loading && list.length === 0" class="mcp-empty">
          <ElEmpty description="尚未注册任何 MCP 服务" />
        </div>
      </div>

      <!-- 2.3 底部分页：居中 -->
      <div v-if="pagination.total > 0" class="mcp-pagination">
        <ElPagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="pageSizeOptions"
          :background="true"
          layout="total, sizes, prev, pager, next, jumper"
          small
          @size-change="(s) => { pagination.size = s; reload() }"
          @current-change="(c) => { pagination.current = c; reload() }"
        />
      </div>
      </div>
    </ElCard>

    <!-- 新增 / 编辑对话框（imawx 自己的，跟原 list 一样） -->
    <McpProxyFormDialog v-model:visible="dialogVisible" :record="editing" @saved="reload" />

    <!-- 测试结果弹窗:2026-06-28 卡片"测试"按钮移到了新增/编辑弹窗里,
         这里 dialog 暂时无调用方 —— 保留组件文件 + import 但不挂载到模板上。
         后续如果需要"详情页/抽屉里批量看 tool 列表"再加回去。 -->
    <!-- <McpTestResultDialog ... />  -->

    <!-- 单个 Tool 调用对话框:抽屉内每个 tool 卡片的"测试"按钮触发。
         跟 McpTestResultDialog 的区别:这里是单 tool 的 callTool 响应(Content 列表 + isError),
         不是 listTools 响应(Tool 列表)。

         2026-06-28 改造:不再由父组件立刻调 API 拿空 args 结果,而是把 inputSchema
         传给 dialog,dialog 解析后渲染参数表单,admin 填完点"执行"才真正调 API。
         dialog 自己持有 formValues / result / executing,parent 只关心开/关 + 按钮 loading。 -->
    <McpToolCallResultDialog
      v-model:visible="toolCallResultVisible"
      :server-id="selectedId"
      :server-name="drawerDetail?.serverName"
      :tool-name="toolCallResultToolName"
      :input-schema="toolCallResultInputSchema"
      @loading-change="onToolCallLoadingChange"
    />

    <ElDialog
      v-model="toolOverrideVisible"
      title="Tool 重写"
      width="720px"
      class="imawx-dialog-fixed imawx-dialog-fixed--medium"
      :close-on-click-modal="false"
    >
      <ElForm label-position="top">
        <ElFormItem label="原始 Tool">
          <ElInput v-model="toolOverrideForm.toolName" disabled />
        </ElFormItem>
        <ElFormItem label="展示名称">
          <ElInput
            v-model="toolOverrideForm.displayName"
            maxlength="128"
            placeholder="留空则使用原始 Tool 名称"
          />
        </ElFormItem>
        <ElFormItem label="描述">
          <ElInput
            v-model="toolOverrideForm.description"
            type="textarea"
            :rows="3"
            maxlength="4096"
            show-word-limit
            placeholder="留空则使用原始描述"
          />
        </ElFormItem>
        <ElFormItem label="入参 Schema">
          <ElInput
            v-model="toolOverrideForm.inputSchema"
            type="textarea"
            :rows="8"
            placeholder="留空则使用原始 inputSchema；填写时必须是 JSON 对象"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="toolOverrideVisible = false">取消</ElButton>
        <ElButton :loading="toolOverrideSaving" @click="clearToolOverride">恢复原始</ElButton>
        <ElButton type="primary" :loading="toolOverrideSaving" @click="saveToolOverride">
          保存
        </ElButton>
      </template>
    </ElDialog>

    <!--
      卡片底部"日志"按钮触发:展示这个 MCP 自己的所有调用记录。
      弹框内部走 /api/sys/monitor/logs + serverId eq 过滤,serverType 锁死 EXTERNAL。
    -->
    <McpProxyLogsDialog
      v-model:visible="logsDialogVisible"
      :server-id="logsDialogServerId"
      :server-name="logsDialogServerName"
      server-type="EXTERNAL"
    />

    <!-- 详情抽屉:点击卡片不再跳页,直接右侧弹出。
         size="50%" 占屏幕一半;direction="rtl" 从右往左滑入。
         最小可见版本只展示基本信息 + 编辑入口,后续补测试/启用禁用/Tools 列表。 -->
    <ElDrawer
      v-model="drawerVisible"
      direction="rtl"
      size="50%"
      :with-header="false"
      :close-on-click-modal="true"
      @closed="closeDrawer"
    >
      <div v-if="drawerDetail" class="mcp-drawer">
        <header class="mcp-drawer__head">
          <div class="mcp-drawer__title">
            <img
              v-if="shouldUseServerBrand(drawerDetail)"
              :src="`/svgl-icons/${brandIconOf(drawerDetail.serverName)!.route}`"
              :alt="drawerDetail.serverName"
              class="mcp-drawer__brand-icon"
            />
            <ArtSvgIcon
              v-else
              :icon="displayIcon(drawerDetail)"
              :style="{ color: transportColor(drawerDetail.transportType) }"
              class="mcp-drawer__transport-icon"
            />
            <span>{{ drawerDetail.serverName }}</span>
          </div>
          <ElSpace :size="8">
            <!-- 2026-07-01 移除抽屉 head 的「测试」按钮 —— 单 tool 卡片的"测试"按钮走
                 /api/sys/mcp-proxy/{id}/tools/{toolName}/test 已经能验证单 tool,
                 drawer head 整 server 测试是冗余入口;新增时已经自动同步过了。
                 「同步」保留 —— 远端 tool 变更时手动刷新本地快照。 -->
            <ElButton :loading="drawerSyncLoading" @click="drawerSync">
              <template #icon><ArtSvgIcon icon="ri:cloud-line" /></template>
              同步
            </ElButton>
          </ElSpace>
        </header>

        <!-- 工具列表:抽屉打开时 openDrawer 会并行调 fetchMcpProxyToolsSys 读快照填充,
             没快照过时这里空,显示下面的 ElEmpty。
             点右上角"测试"重跑 listTools 拿最新;"同步"则写快照(也顺便拿最新)。 -->
        <div v-if="drawerTools.length > 0" class="mcp-drawer__tools">
          <div class="mcp-drawer__tools-header">Tools（{{ drawerTools.length }}）</div>
          <div v-for="tool in drawerTools" :key="tool.name" class="mcp-drawer__tool">
            <div class="mcp-drawer__tool-head">
              <span class="mcp-drawer__tool-name">{{ tool.name }}</span>
              <span class="mcp-drawer__tool-spacer" />
              <!-- 2026-06-28 加:单个 tool 的"测试"按钮,点开 dialog 后填参数调 callTool 探活。
                   2026-07-01 移除 ElSwitch 启用开关(用户原话),也不再展示 inputSchema —— 这俩信息在
                   测试 dialog 里填参时会按 schema 渲染表单,卡片本身只展示 tool 名称 + 描述 + 测试入口。 -->
              <ElButton
                size="small"
                text
                :loading="toolCallLoading[tool.name]"
                @click="testTool(tool)"
              >
                <template #icon><ArtSvgIcon icon="ri:play-line" /></template>
                测试
              </ElButton>
              <ElButton size="small" text @click="openToolOverride(tool)">
                <template #icon><ArtSvgIcon icon="ri:edit-2-line" /></template>
                重写
              </ElButton>
            </div>
            <p v-if="tool.description" class="mcp-drawer__tool-desc">{{ tool.description }}</p>
          </div>
        </div>
        <ElEmpty
          v-else
          description="尚未同步，点右上角「同步」或「测试」发现 Tool"
          :image-size="80"
        />
      </div>
      <div v-else class="mcp-drawer__loading">加载中…</div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router'
  import type { CSSProperties } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    deleteMcpProxySys,
    disableMcpProxySys,
    enableMcpProxySys,
    fetchMcpProxyDetailSys,
    fetchMcpProxyPageSys,
    fetchMcpProxyToolsSys,
    saveMcpToolOverrideSys,
    syncMcpProxySys,
    testMcpProxySys, // 保留 import —— drawer head 测试按钮移除后这个函数没用了但留着 import 不报错
    type ImawxMcpProxy,
    type ImawxMcpProxyQuery,
    type ImawxMcpProxyToolPreview
  } from '@/api/sys/mcp-proxy'
  import { useCardColumns } from '@/composables/useCardColumns'
  import { useConstants } from '@/composables/useConstants'
  import { matchByServerName } from '@/composables/useSvglIcons'
  import { tagColor } from '@/composables/useTagColors'
  import type { SvglIconEntry } from '@/api/sys/icons'
  import McpProxyFormDialog from './components/McpProxyFormDialog.vue'
  import McpTestResultDialog from './components/McpTestResultDialog.vue'
  import McpToolCallResultDialog from './components/McpToolCallResultDialog.vue'
  import McpProxyLogsDialog from './components/McpProxyLogsDialog.vue'

  defineOptions({ name: 'ImawxMcpProxyList' })

  // 下拉框常量（protocol / connectionStatus）—— App.vue 启动时已拉,这里读缓存
  // 用 computed 包装 searchItems:顶层 const 求值时如果常量还没回来,options
  // 数组会被固化成空;computed 跟随 useConstants 模块级 ref 自动重算。
  const { getOptions } = useConstants()

  const router = useRouter()

  // el-card__body 行内样式:跟 DB 页面 .db-body :deep(.el-card__body) 等效
  // MCP 多包了 .mcp-body wrapper,导致原 .mcp-body :deep(.el-card__body) 选择器路径错(祖先非后代),
  // 默认 20px padding 一直没被覆盖 → toolbar 离 el-card 左边比 DB 多 20px。
  // 这里走 ElCard :body-style prop,inline 设进 el-card__body,绕开选择器坑。
  const cardBodyStyle: CSSProperties = {
    padding: '0',
    display: 'flex',
    flexDirection: 'column',
    flex: '1 1 auto',
    height: '100%',
    minHeight: '0',
    overflow: 'hidden'
  }

  // ===== 状态 =====
  const loading = ref(false)
  const list = ref<ImawxMcpProxy[]>([])
  const actionLoading = reactive<Record<string, boolean>>({})
  // id 是 string（雪花 ID 见 ImawxMcpProxy.id 注释），不能用 number 收
  const statusLoading = ref<string | undefined>()

  /**
   * 按 serverName 查 svgl brand icon(用 module-scope 缓存,单例)。
   * 没匹配上(没启动 / 拉失败 / 名字不像品牌)返 null,模板走 fallback transport icon。
   */
  function brandIconOf(name: string | undefined): SvglIconEntry | null {
    return matchByServerName(name)
  }

  function transportBrandIcon(t?: string): string | null {
    switch (t) {
      case 'MYSQL':
        return 'simple-icons:mysql'
      case 'POSTGRESQL':
        return 'simple-icons:postgresql'
      case 'ORACLE':
        return 'simple-icons:oracle'
      case 'SQLSERVER':
        return 'simple-icons:microsoftsqlserver'
      case 'REDIS':
        return 'simple-icons:redis'
      case 'MONGODB':
        return 'simple-icons:mongodb'
      case 'ELASTICSEARCH':
        return 'simple-icons:elasticsearch'
      case 'ALIYUN_DNS':
      case 'ALIYUN_OSS':
        return 'simple-icons:alibabacloud'
      case 'OPENAPI':
        return 'simple-icons:swagger'
      case 'DRONE':
        return 'simple-icons:drone'
      default:
        return null
    }
  }

  function shouldUseServerBrand(item: ImawxMcpProxy): boolean {
    return !transportBrandIcon(item.transportType) && !!brandIconOf(item.serverName)
  }

  function displayIcon(item: ImawxMcpProxy): string {
    return transportBrandIcon(item.transportType) || transportIcon(item.transportType)
  }

  const searchVisible = ref(false)
  const searchForm = ref<ImawxMcpProxyQuery>({
    serverName: undefined,
    transportType: undefined,
    enabled: undefined
  })

  const searchItems = computed(() => [
    {
      key: 'serverName',
      label: '名称',
      type: 'input',
      props: { clearable: true, placeholder: '服务名' }
    },
    {
      key: 'transportType',
      label: '协议',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '全部',
        // options 必须放 props 里:ArtSearchBar.getProps(item) 在 item.props
        // 存在时只返回 item.props 本身,item 顶层 options 会被丢弃
        options: mcpTransportOptions()
      }
    },
    {
      key: 'enabled',
      label: '启用',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '全部',
        options: [
          { label: '已启用', value: 1 },
          { label: '已禁用', value: 0 }
        ]
      }
    }
  ])

  const dialogVisible = ref(false)
  const editing = ref<ImawxMcpProxy | null>(null)
  const editLoading = ref(false)
  const editingId = ref<string | null>(null)

  // 测试结果弹窗状态:2026-06-28 卡片「测试」按钮移除 → 调用方没了,这里变死代码,先注释保留。
  // 后续若要重新启用,反注释 + 挂回模板即可。
  // const testResultVisible = ref(false)
  // const testResultServerName = ref<string>('')
  // const testResultTools = ref<ImawxMcpProxyToolPreview[]>([])
  // const testResultCheckedAt = ref<string>('')

  // ===== 抽屉：点击卡片弹详情(替代之前的 router.push 跳详情页) =====
  // 抽屉内只展示工具列表 + 测试按钮(2026-06-28 简化:不再放基本信息)。
  // 工具列表默认空——后端目前没暴露快照 tools 接口,点"测试"才拉 listTools。
  const selectedId = ref<string | undefined>()
  const drawerVisible = ref(false)
  const drawerDetail = ref<ImawxMcpProxy | null>(null)
  const drawerTools = ref<ImawxMcpProxyToolPreview[]>([])
  // 抽屉"同步"按钮 loading — 抽屉 head 唯一保留的批量操作按钮。
  // 2026-07-01 移除抽屉 head 的「测试」按钮后,这里不再需要 drawerTestLoading。
  const drawerSyncLoading = ref(false)

  // 单个 tool 测试对话框的状态(2026-06-28 加:tool 卡片右侧"测试"按钮触发)。
  //
  // 2026-06-28 二次改造:之前是 click → 立刻调 API(空 args),dialog 拿到 result 就展示;
  // 现在是 click → 打开 dialog,dialog 解析 inputSchema 渲染参数表单 → admin 填参数点"执行"
  // → dialog 自己调 API。所以 result 不再在 parent 缓存(inputSchema + 一次调用就够了),
  // 只剩:visible / toolName / inputSchema 三个 prop 透传给 dialog。
  //
  // toolCallLoading 仍然由 parent 持有 ——— dialog 在执行 API 时通过 emit('loading-change')
  // 通知 parent 更新,tool 卡片上的"测试"按钮 :loading 才能正确反映。
  const toolCallResultVisible = ref(false)
  const toolCallResultToolName = ref<string | undefined>(undefined)
  const toolCallResultInputSchema = ref<string | undefined>(undefined)
  const toolCallLoading = reactive<Record<string, boolean>>({})
  const toolOverrideVisible = ref(false)
  const toolOverrideSaving = ref(false)
  const toolOverrideForm = reactive({
    toolName: '',
    displayName: '',
    description: '',
    inputSchema: ''
  })

  // 调用日志弹框:卡片底部"日志"按钮触发,展示**单个 MCP** 的所有调用记录
  const logsDialogVisible = ref(false)
  const logsDialogServerId = ref<string>('')
  const logsDialogServerName = ref<string>('')

  async function openDrawer(item: ImawxMcpProxy) {
    selectedId.value = item.id
    drawerDetail.value = item // 先用 list 已有数据,避免空白
    drawerTools.value = []
    drawerVisible.value = true
    // 并行拉详情 + 快照 tools,抽屉先出现空状态再填充
    Promise.all([reloadDrawer(), reloadDrawerTools()])
  }

  async function reloadDrawer() {
    if (!selectedId.value) return
    try {
      drawerDetail.value = await fetchMcpProxyDetailSys(selectedId.value)
    } catch {
      // 错误已弹;保留旧数据
    }
  }

  async function reloadDrawerTools() {
    if (!selectedId.value) return
    try {
      drawerTools.value = await fetchMcpProxyToolsSys(selectedId.value)
    } catch {
      // 错误已弹;保留旧数据
    }
  }

  function closeDrawer() {
    drawerVisible.value = false
    drawerDetail.value = null
    drawerTools.value = []
    selectedId.value = undefined
  }

  /**
   * 打开单个 Tool 的测试对话框(2026-06-28 二次改造):
   * 不再立刻调 API —— 把 tool 上下文(toolName / serverId / inputSchema)透传给 dialog,
   * dialog 渲染参数表单,admin 填完点"执行"才真正调 API(在 dialog 内部完成)。
   *
   * <p>为什么不在父组件直接调:tool 卡片右侧的"测试"按钮只表达"打开调试面板"的意图,
   * 不应该 click → 立刻 fire 一个注定失败的请求(空 args 必败,反而吓用户一跳)。
   *
   * <p>toolCallLoading 不在这里 set true —— dialog 内部 API 执行时通过 emit('loading-change')
   * 通知 parent 更新,这样"测试"按钮 :loading 反映真实 API 调用状态,而不是 dialog 打开期间常亮。
   */
  function testTool(tool: ImawxMcpProxyToolPreview) {
    if (!selectedId.value) return
    toolCallResultToolName.value = tool.name
    toolCallResultInputSchema.value = tool.inputSchema
    toolCallResultVisible.value = true
  }

  function openToolOverride(tool: ImawxMcpProxyToolPreview) {
    toolOverrideForm.toolName = tool.originalName || tool.name
    toolOverrideForm.displayName = tool.originalName && tool.originalName !== tool.name ? tool.name : ''
    toolOverrideForm.description = tool.description ?? ''
    toolOverrideForm.inputSchema = tool.inputSchema ? formatJsonText(tool.inputSchema) : ''
    toolOverrideVisible.value = true
  }

  async function saveToolOverride() {
    if (!selectedId.value || !toolOverrideForm.toolName) return
    toolOverrideSaving.value = true
    try {
      await saveMcpToolOverrideSys(selectedId.value, {
        toolName: toolOverrideForm.toolName,
        displayName: toolOverrideForm.displayName || undefined,
        description: toolOverrideForm.description || undefined,
        inputSchema: normalizeSchemaOverride(toolOverrideForm.inputSchema)
      })
      toolOverrideVisible.value = false
      await reloadDrawerTools()
    } finally {
      toolOverrideSaving.value = false
    }
  }

  async function clearToolOverride() {
    if (!selectedId.value || !toolOverrideForm.toolName) return
    toolOverrideSaving.value = true
    try {
      await saveMcpToolOverrideSys(selectedId.value, {
        toolName: toolOverrideForm.toolName
      })
      toolOverrideVisible.value = false
      await reloadDrawerTools()
    } finally {
      toolOverrideSaving.value = false
    }
  }

  function normalizeSchemaOverride(value: string) {
    if (!value.trim()) return undefined
    try {
      return JSON.stringify(JSON.parse(value))
    } catch (e) {
      ElMessage.error(`入参 Schema JSON 不合法: ${(e as Error).message}`)
      throw e
    }
  }

  function formatJsonText(value: string) {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }

  /**
   * dialog 内部 API 调用状态同步回调 —— 保持 tool 卡片"测试"按钮 :loading 跟实际请求同步。
   * dialog 在 finally 也会 emit(false),所以一定会清。
   */
  function onToolCallLoadingChange(toolName: string, loading: boolean) {
    toolCallLoading[toolName] = loading
  }

  // ===== 数据加载 =====
  async function reload() {
    loading.value = true
    try {
      const r = await fetchMcpProxyPageSys({
        ...searchForm.value,
        pageNum: pagination.current,
        pageSize: pagination.size
      })
      list.value = r.records ?? []
      pagination.total = r.total ?? list.value.length
    } catch {
      list.value = []
      pagination.total = 0
    } finally {
      loading.value = false
    }
  }

  function handleSearch() {
    pagination.current = 1
    reload()
  }

  function handleReset() {
    searchForm.value = {
      serverName: undefined,
      transportType: undefined,
      enabled: undefined
    }
    pagination.current = 1
    reload()
  }

  // ===== 操作 =====
  function openCreate() {
    editing.value = null
    dialogVisible.value = true
  }

  async function openEdit(row: ImawxMcpProxy) {
    if (!row.id || editLoading.value) return
    editLoading.value = true
    editingId.value = row.id
    try {
      editing.value = await fetchMcpProxyDetailSys(row.id)
      dialogVisible.value = true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载 MCP 服务详情失败')
    } finally {
      editLoading.value = false
      editingId.value = null
    }
  }

  /**
   * 卡片底部"日志"按钮:打开调用日志弹框,锁死这个 MCP 的 serverId。
   * 不 await:UI 不阻塞,弹框 @open 时再 reload。
   */
  function openLogs(row: ImawxMcpProxy) {
    logsDialogServerId.value = row.id
    logsDialogServerName.value = row.serverName
    logsDialogVisible.value = true
  }

  function goDetail(id?: string) {
    if (!id) return
    router.push({ name: 'ImawxMcpProxyDetail', params: { id } })
  }

  /**
   * 抽屉内"同步":外部 MCP 刷新本地 tool 表;内置 provider 只刷新同步状态。
   *
   * <p>2026-07-01 改:卡片底部「同步」按钮移除后,这是唯一手动同步入口。
   * 新增时后端会自动同步一次;老卡片远端 tool 变更需要重拉时走这里。
   *
   * <p>showSuccessMessage:false —— 后端 SyncOutcome 自带 success 字段,
   * 前端按 success 弹 message(成功绿 / 失败红),request util 默认 message 不准确。
   */
  async function drawerSync() {
    if (!selectedId.value) return
    drawerSyncLoading.value = true
    try {
      const r = await syncMcpProxySys(selectedId.value)
      if (r.success) {
        ElMessage.success('同步成功')
      } else {
        ElMessage.error(`同步失败:${r.errorMessage ?? r.errorCode ?? '未知错误'}`)
      }
      // 无论成功失败都刷新(成功刷 lastSyncAt,失败刷 lastSyncError 红色 desc)
      reloadDrawer()
      reload()
    } catch {
      // 网络/服务器 5xx —— 全局拦截已弹
    } finally {
      drawerSyncLoading.value = false
    }
  }

  async function toggleEnabled(row: ImawxMcpProxy, enabled: boolean) {
    if (statusLoading.value === row.id) return
    const prev = row.enabled
    statusLoading.value = row.id
    // 乐观更新
    row.enabled = enabled ? 1 : 0
    try {
      if (enabled) {
        await enableMcpProxySys(row.id)
      } else {
        await disableMcpProxySys(row.id)
      }
      ElMessage.success(enabled ? '已启用' : '已禁用')
    } catch (e) {
      row.enabled = prev
    } finally {
      statusLoading.value = undefined
    }
  }

  async function handleDelete(row: ImawxMcpProxy) {
    try {
      await ElMessageBox.confirm(
        `确认删除 MCP 服务「${row.serverName}」？删除后该服务注册的 Tool 会从本地 MCP Server 注销。`,
        '删除确认',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    await deleteMcpProxySys(row.id)
    ElMessage.success('已删除')
    reload()
  }

  // ===== 展示格式化 =====
  /**
   * transport icon —— lucide 实心 icon,fill="currentColor" 可染色。
   * 跟 database 卡片思路一致:每个类型用自己的视觉特征 + 语义色。
   * Iconify 命名已逐个验证可用:
   *   - lucide:globe   地球(网络/对外)         → HTTP
   *   - lucide:antenna 信号塔(单向实时流)     → SSE (Server-Sent Events)
   *   - lucide:terminal 终端 `>_` (本地进程) → STDIO
   *   - lucide:plug-zap  闪电插头(双向实时流) → STREAMABLE_HTTP (MCP 2.0 新协议)
   *     (Streamable HTTP 是 MCP 2.0 推荐的新传输,基于 HTTP POST + 可选 SSE,
   *      比纯 SSE 多双向 + 状态管理;plug-zap 体现"插电 + 即时"的特性,
   *      区别 SSE 的 antenna「单向广播」)
   *   - 默认 lucide:server —— 兜底(未知 transport 不应出现,出现就是数据脏)
   */
  function transportIcon(t?: string): string {
    switch (t) {
      case 'HTTP':
        return 'lucide:globe'
      case 'SSE':
        return 'lucide:antenna'
      case 'STDIO':
        return 'lucide:terminal'
      case 'STREAMABLE_HTTP':
        return 'lucide:plug-zap'
      case 'MYSQL':
        return 'simple-icons:mysql'
      case 'POSTGRESQL':
        return 'simple-icons:postgresql'
      case 'ORACLE':
        return 'simple-icons:oracle'
      case 'SQLSERVER':
        return 'simple-icons:microsoftsqlserver'
      case 'DB':
        return 'ri:database-2-line'
      case 'REDIS':
        return 'simple-icons:redis'
      case 'MONGODB':
        return 'simple-icons:mongodb'
      case 'ELASTICSEARCH':
        return 'simple-icons:elasticsearch'
      case 'ALIYUN_DNS':
      case 'ALIYUN_OSS':
        return 'simple-icons:alibabacloud'
      case 'OPENAPI':
        return 'simple-icons:swagger'
      case 'SSH':
        return 'ri:terminal-box-line'
      case 'DRONE':
        return 'simple-icons:drone'
      default:
        return 'lucide:server'
    }
  }

  function mcpTransportOptions() {
    return getOptions('protocol')
      .filter((o) => o.value !== undefined && o.value !== null && String(o.value).trim() !== '')
      .map((o) => ({ label: `远程 / ${o.label || o.desc || String(o.value)}`, value: o.value }))
      .concat([
        { label: '内部 / MySQL', value: 'MYSQL' },
        { label: '内部 / PostgreSQL', value: 'POSTGRESQL' },
        { label: '内部 / Oracle', value: 'ORACLE' },
        { label: '内部 / SQL Server', value: 'SQLSERVER' },
        { label: '内部 / Redis', value: 'REDIS' },
        { label: '内部 / MongoDB', value: 'MONGODB' },
        { label: '内部 / Elasticsearch', value: 'ELASTICSEARCH' },
        { label: '内部 / 阿里云 DNS', value: 'ALIYUN_DNS' },
        { label: '内部 / 阿里云 OSS', value: 'ALIYUN_OSS' },
        { label: '内部 / SSH', value: 'SSH' },
        { label: '内部 / Drone', value: 'DRONE' },
        { label: '内部 / Swagger API', value: 'OPENAPI' }
      ])
  }

  /**
   * transport 语义色 —— 协议类型对应的颜色直觉:
   *   - HTTP             蓝(网络)
   *   - SSE              紫(实时流)
   *   - STDIO            绿(本地进程)
   *   - STREAMABLE_HTTP  青蓝(MCP 2.0 新协议,用 cyan-500 跟传统 HTTP 蓝区分)
   * chip / 头部 icon 共用,出现 1+ 次就能让人一眼区分传输类型。
   */
  function transportColor(t?: string): string {
    switch (t) {
      case 'HTTP':
        return '#3B82F6' // 蓝
      case 'SSE':
        return '#8B5CF6' // 紫
      case 'STDIO':
        return '#10B981' // 绿
      case 'STREAMABLE_HTTP':
        return '#06B6D4' // 青蓝(cyan-500)
      case 'DB':
        return '#F59E0B'
      case 'MYSQL':
        return '#4479A1'
      case 'POSTGRESQL':
        return '#4169E1'
      case 'ORACLE':
      case 'SQLSERVER':
        return '#CC2927'
      case 'REDIS':
        return '#DC2626'
      case 'MONGODB':
        return '#47A248'
      case 'ELASTICSEARCH':
        return '#005571'
      case 'ALIYUN_DNS':
      case 'ALIYUN_OSS':
        return '#EA580C'
      case 'OPENAPI':
        return '#0F766E'
      case 'SSH':
        return '#10B981'
      case 'DRONE':
        return '#111827'
      default:
        return 'var(--el-text-color-placeholder)'
    }
  }

  function statusLabel(s?: string): string {
    const found = getOptions('connectionStatus').find((o) => o.value === s)
    return found?.label ?? found?.desc ?? s ?? '未知'
  }

  function healthIcon(s?: string): string {
    switch (s) {
      case 'CONNECTED':
        return 'ri:check-line'
      case 'FAILED':
        return 'ri:close-line'
      default:
        return 'ri:question-line'
    }
  }

  function healthClass(s?: string): string {
    switch (s) {
      case 'CONNECTED':
        return 'is-on'
      case 'FAILED':
        return 'is-off'
      default:
        return 'is-unknown'
    }
  }

  function cardClass(item: ImawxMcpProxy) {
    return { 'is-disabled': item.enabled !== 1 }
  }

  // ISO 'YYYY-MM-DDTHH:MM:SS[.sss][Z]' → 'YYYY-MM-DD HH:MM:SS'
  // 卡片 desc 区要带日期(只看时分分不清是哪天测/同步的),
  // 直接 substring,不做时区转换 — 后端 Java LocalDateTime 序列化的 ISO 没带时区,
  // 本身就是本地时间;line 526 的 new Date().toISOString() 是 UTC,那边本来就有时区 bug,
  // 不在本任务范围内,保持原行为避免引入新的不一致。
  function formatTime(iso?: string): string {
    if (!iso) return ''
    return iso.length >= 19 ? `${iso.substring(0, 10)} ${iso.substring(11, 19)}` : iso
  }

  // ===== 卡片列数（按 viewport 断点封顶 + 容器宽） =====
  // 大屏(≥1920) 5 / 中屏(≥1280) 3 / 移动(<1280) 2
  // 列数 = min(数据量, maxByMin, maxByBp)  →  数据少时只会更少,不会硬撑满列
  // viewport 断点：1920+ → 5 列 / 1280-1919 → 3 列 / 768-1279 → 3 列 / <768 → 2 列
  // 1280-1919 改 3 列:13 寸 Mac(1280-1440 viewport)4 列太挤,3 列每列 ~380px 更舒服;
  // 768-1279 也保持 3 列(平板/竖屏中段),实际跟 1280-1919 合并成一档视觉等价
  // minCardWidth 280:3 列时每列 ~300px+ 够用不挤
  const mcpGridRef = ref<HTMLElement | null>(null)
  const cardCols = useCardColumns(mcpGridRef, () => list.value.length, {
    minCardWidth: 280,
    gap: 12,
    padding: 32,
    breakpoints: [
      { minWidth: 1920, cols: 5 },
      { minWidth: 1280, cols: 3 },
      { minWidth: 768, cols: 3 },
      { minWidth: 0, cols: 2 }
    ]
  })

  // ===== 分页: pageSize 跟列数联动 =====
  // 默认每页 = 列数 × 3 行（不挤也不空）
  // 选项 = [列×3, 列×4, 列×6, 列×9]（3/4/6/9 行 4 档）
  // 列数变化时,如果当前 size 不在新选项里,自动切到 default 并 reload
  const pageSizeOptions = computed(() => {
    const cols = Math.max(1, cardCols.value)
    return [cols * 3, cols * 4, cols * 6, cols * 9]
  })
  const defaultPageSize = computed(() => Math.max(1, cardCols.value) * 3)

  const pagination = reactive({ current: 1, size: 9, total: 0 })

  watch(cardCols, (newCols) => {
    const opts = [newCols * 3, newCols * 4, newCols * 6, newCols * 9]
    if (!opts.includes(pagination.size)) {
      pagination.size = newCols * 3
      pagination.current = 1
      reload()
    }
  })

  onMounted(reload)
</script>

<style scoped>
  /* ============ 整体：搜索区 + 内容区（toolbar + 网格 + 分页） ============ */
  .mcp-page {
    /* art-full-height 是 layout 算的 CSS 变量(100vh - 头部高度) */
    height: var(--art-full-height);
    min-height: 0;
    display: flex;
    flex-direction: column;
    width: 100%;
  }

  /* 内容区：内部 flex column 容器, 让 toolbar/grid/pagination 按比例分配高度。
     注意:这里不写 :deep(.el-card__body) 穿透规则 —— .el-card__body 是 .mcp-body 祖先非后代,
     原选择器路径错(2026-06-27 排查 toolbar 边距不对齐 DB 时发现),改用 ElCard :body-style prop
     直接 inline 设进 el-card__body（见 cardBodyStyle） */
  .mcp-body {
    height: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0; /* 关键：让 flex 子项（grid）能压缩, 不被 intrinsic height 撑爆 */
    min-width: 0;
    overflow: hidden;
  }

  /* toolbar 用 Tailwind utility class 实现(复用基座 token: size-8 rounded-md bg-g-300/55 等),
     不写 scoped 样式；ArtTableHeader 是 flex-cb 左右分布会把次操作顶到右边,本页不用 */

  /* 卡片网格 */
  .mcp-grid {
    /* 关键：display:grid 作为 flex item 时, 高度由 grid-template-rows 决定 (intrinsic content height),
       flex 算法压不动, 必须用 max-height:100% 强制约束在 mcp-body 内,
       否则 grid 把分页推出 viewport */
    flex: 1 1 0;
    min-height: 0;
    max-height: 100%;
    min-width: 0;
    width: 100%;
    overflow-y: auto;
    display: grid;
    grid-template-columns: repeat(var(--mcp-cols, 1), 1fr);
    gap: 12px;
    padding: 12px 16px;
    align-content: start;
  }

  .mcp-empty {
    grid-column: 1 / -1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 40px 0;
  }

  /* 底部分页：居中 */
  .mcp-pagination {
    flex-shrink: 0;
    display: flex;
    justify-content: center;
    padding: 8px 16px;
    border-top: 1px solid var(--default-border);
  }

  /* ============ 卡片 ============ */
  .mcp-card {
    position: relative;
    display: flex;
    flex-direction: column;
    /* 2026-06-28 改固定高度 280px（原 min-height 232px + 调整）：
       用户确认可以固定,卡片不再按内容伸缩,所有卡片等高对齐。 */
    height: 280px;
    min-width: 0;
    padding: 20px;
    cursor: pointer;
    background: var(--el-color-primary-light-9);
    transition: box-shadow 0.2s, transform 0.2s, background-color 0.2s;
  }
  .mcp-card:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
  }
  .mcp-card.is-disabled {
    background: var(--el-fill-color-light);
    opacity: 0.7;
  }
  .mcp-card.is-disabled:hover {
    transform: none;
    box-shadow: none;
  }

  .mcp-card-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
  }
  .mcp-card-icon {
    width: 44px;
    height: 44px;
    border-radius: 12px;
    background: var(--el-color-primary-light-9);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    overflow: hidden;
  }

  /* svgl brand icon:img 28x28 在 44x44 容器里留 8px 边距;
     object-fit:contain 防止 SVG 自身 viewBox 不一致时被裁;
     容器 background 仍然是 primary-light-9,light 主题下大多数彩色 logo 都能看清;
     dark 主题下 light SVG 仍然可见(只是不再有浅蓝底对比,先这样,后续接 dark 模式再调) */
  .mcp-card-brand-icon {
    width: 28px;
    height: 28px;
    object-fit: contain;
  }
  .mcp-card-title {
    display: block;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.4;
    margin: 0 0 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mcp-card-meta {
    display: block;
    font-size: 11px;
    line-height: 1.4;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  }

  /* 标签 chips(2026-07-01 加):在 endpoint 下面一行展示,卡片里只读不可点。
     列表超出 5 个时尾巴显示 "+N" 提示,hover 看完整列表(由 ElTooltip 实现)。 */
  .mcp-card-tags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
    margin-top: 4px;
  }
  .mcp-card-tag {
    /* ElTag 默认 22px 行高,但在 11px meta 行下视觉偏大,缩小一档贴合密集卡片 */
    height: 18px !important;
    line-height: 16px !important;
    padding: 0 6px !important;
    font-size: 10px !important;
  }
  .mcp-card-tags__more {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 18px;
    padding: 0 6px;
    font-size: 10px;
    line-height: 1;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border-radius: 2px;
    cursor: help;
  }

  /* 备注:独立成段,3 行截断 + hover tooltip 看完整。无 icon 无背景,
     跟状态/异常信息视觉同源(同字号 12px / 1.6 行高 / secondary 文本色)。
     拆成独立 <p> 是为了跟下面的 .mcp-card-status 用不同行数,父级共用 line-clamp
     会一刀切,inline span 的 line-clamp 会被父级 clamp 截掉,显示不出来。 */
  .mcp-card-remark {
    margin: 0 0 6px;
    font-size: 12px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
    word-break: break-all;
    color: var(--el-text-color-secondary);
  }
  .mcp-card-remark__text {
    cursor: help;
  }

  /* 状态 / 异常信息:2 行截断。错误态 (text-error) 由父 class 切换颜色。 */
  .mcp-card-status {
    margin: 0 0 12px;
    font-size: 12px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .mcp-card-binds {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    /* 2026-06-28 调整:margin-top: auto 把 chip 行推到底,紧贴虚线上方;
       之前是 margin-bottom: 12px + actions 的 margin-top: auto,导致 chip 浮在中间。 */
    margin-top: auto;
    margin-bottom: 12px;
  }
  .bind-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 9px;
    font-size: 11px;
    border-radius: 999px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-placeholder);
    transition: all 0.15s;
  }
  .bind-chip.is-on {
    background: var(--el-color-success-light-9);
    color: var(--el-color-success);
  }
  .bind-chip.is-off {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
  }
  .bind-chip.is-unknown {
    background: var(--el-fill-color-light);
    color: var(--el-text-color-regular);
  }
  .bind-chip .lbl {
    font-weight: 400;
  }

  .mcp-card-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    /* 2026-06-28 调整:margin-top 改 0,不再 auto 推底;
       改让 .mcp-card-binds 推到虚线上方,actions 自然紧跟在虚线下面。 */
    padding-top: 12px;
    border-top: 1px dashed var(--default-border-dashed);
  }

  /* ============ 详情抽屉(2026-06-28 替换原详情页跳转) ============
     el-drawer 默认 body 有 padding,这里关掉让我们自己控制节奏 */
  .mcp-drawer {
    padding: 20px 24px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .mcp-drawer__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .mcp-drawer__title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    min-width: 0;
  }
  .mcp-drawer__brand-icon,
  .mcp-drawer__transport-icon {
    width: 22px;
    height: 22px;
    flex-shrink: 0;
  }
  .mcp-drawer__card {
    border: 0;
  }
  .mcp-drawer__mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    word-break: break-all;
  }
  .mcp-drawer__loading {
    padding: 40px;
    text-align: center;
    color: var(--el-text-color-placeholder);
  }
  /* 工具列表 */
  .mcp-drawer__tools {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .mcp-drawer__tools-header {
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    padding-bottom: 8px;
    border-bottom: 1px solid var(--default-border);
  }
  .mcp-drawer__tool {
    padding: 12px 14px;
    border: 1px solid var(--default-border);
    border-radius: var(--el-border-radius-base);
    background: var(--el-fill-color-blank);
  }
  .mcp-drawer__tool-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }
  .mcp-drawer__tool-name {
    font-weight: 600;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    color: var(--el-text-color-primary);
    word-break: break-all;
  }
  .mcp-drawer__tool-desc {
    margin: 0 0 8px;
    font-size: 13px;
    color: var(--el-text-color-regular);
    line-height: 1.5;
  }
</style>

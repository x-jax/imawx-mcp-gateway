<!--
  imawx-mcp 账号 → 我的 MCP Token 管理页(2026-07-01 重构;2026-07-03 加授权)

  之前 280 行 → 现在 380 行,核心变化(2026-07-03):
  - 加授权模式字段:restrictMode (0=全开放 / 1=严格) + authorizedBackends + authorizedTools
  - 创建对话框加"分配 MCP 授权"按钮 → 弹 TokenAuthorizeDialog
  - 列表新增"授权范围"列 + "修改授权"按钮 → 弹 TokenAuthorizeDialog
  - 调 PUT /api/sys/tokens/{id}/authorizations 单独更新授权(不影响 token 名/过期/哈希)

  对应后端:/api/sys/tokens  CRUD + PUT /{id}/authorizations + GET /available-backends
-->
<template>
  <div class="imawx-account-tokens art-full-height">
    <ElCard class="imawx-account-tokens__card art-full-height" shadow="never">
      <template #header>
        <div class="flex-cb">
          <div class="flex items-center gap-2">
            <h4 class="m-0">我的 MCP Token</h4>
            <ElTag type="info" size="small" effect="light">用于调用 /api/** 接口(Authorization: Bearer ...)</ElTag>
          </div>
          <ElSpace>
            <ElButton type="primary" :icon="Plus" @click="openCreateDialog">创建 Token</ElButton>
          </ElSpace>
        </div>
      </template>

      <!-- 顶部说明 -->
      <ElAlert type="info" :closable="false" class="imawx-account-tokens__alert">
        Token 等同于账号的「API 密钥」,给第三方脚本 / CI / 其他客户端用,
        调 /api/** 接口时塞 <code>Authorization: Bearer imwx_xxx</code> 头。
        <b>创建后明文只展示一次</b>,关掉弹窗后无法再次查看 —— 请立刻复制保存。
      </ElAlert>

      <!-- 搜索 -->
      <ArtSearchBar
        v-model:model="searchForm"
        :items="searchItems"
        :span="6"
        :default-expanded="true"
        :show-expand="true"
        @search="handleSearch"
        @reset="handleReset"
      />

      <!-- 表格 -->
      <ArtTable
        :loading="loading"
        :data="filteredTokens"
        :columns="columns"
        :pagination="pagination"
        empty-text="还没有任何 Token,点右上「创建 Token」开始"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #status="{ row }">
          <ElTag :type="row.status === 'active' ? 'success' : 'info'" size="small" effect="light">
            {{ row.status === 'active' ? '有效' : '已撤销' }}
          </ElTag>
        </template>
        <template #restrictMode="{ row }">
          <ElTag :type="row.restrictMode === 1 ? 'warning' : 'success'" size="small" effect="light">
            {{ row.restrictMode === 1 ? '严格' : '全开放' }}
          </ElTag>
        </template>
        <template #authorization="{ row }">
          <span class="imawx-account-tokens__auth-summary">
            <ElTag v-if="row.authorizedBackends.length > 0" size="small" type="primary" effect="plain">
              {{ row.authorizedBackends.length }} backend
            </ElTag>
            <ElTag v-if="row.authorizedTools.length > 0" size="small" type="success" effect="plain">
              {{ row.authorizedTools.length }} tool
            </ElTag>
            <span v-if="row.restrictMode === 0" class="imawx-account-tokens__auth-open">任意 backend/tool</span>
            <span v-else-if="row.authorizedBackends.length === 0 && row.authorizedTools.length === 0" class="imawx-account-tokens__auth-empty">
              未授权
            </span>
          </span>
        </template>
        <template #scopes="{ row }">
          <ElTag
            v-for="scope in row.scopes"
            :key="scope"
            size="small"
            type="info"
            effect="plain"
            class="mr-1"
          >
            {{ scope }}
          </ElTag>
        </template>
        <template #operation="{ row }">
          <ElButton
            size="small"
            type="primary"
            text
            :disabled="row.status !== 'active'"
            @click="openAuthorizeDialog(row)"
          >
            修改授权
          </ElButton>
          <ElButton
            size="small"
            type="primary"
            text
            :disabled="row.status !== 'active'"
            @click="openExportDialog(row)"
          >
            导出配置
          </ElButton>
          <ElButton
            size="small"
            type="danger"
            text
            :disabled="row.status !== 'active'"
            @click="handleRevoke(row)"
          >
            撤销
          </ElButton>
          <ElButton size="small" type="danger" text @click="handleDelete(row)">
            删除
          </ElButton>
        </template>
      </ArtTable>
    </ElCard>

    <!-- 创建 Token 弹框(2026-07-01 加;2026-07-03 加授权按钮)——
         两步式:Step 1 填 name + scope + 授权;Step 2 展示明文 + 复制 -->
    <ElDialog
      v-model="createDialogVisible"
      :title="createStep === 1 ? '创建 Token' : 'Token 已创建,立刻复制保存'"
      width="640px"
      top="8vh"
      class="imawx-dialog-fixed imawx-dialog-fixed--medium el-dialog-border"
      :close-on-click-modal="false"
      @close="onCreateDialogClose"
    >
      <!-- Step 1: 填表单 -->
      <div v-if="createStep === 1" class="imawx-account-tokens__form">
        <ElForm
          ref="createFormRef"
          :model="createForm"
          :rules="createRules"
          label-width="100px"
          label-position="right"
        >
          <ElFormItem label="用途" prop="name">
            <ElInput
              v-model.trim="createForm.name"
              placeholder="例:我的笔记本 / CI 脚本 / GitHub Actions"
              maxlength="64"
              show-word-limit
            />
          </ElFormItem>
          <ElFormItem label="权限范围" prop="scopes">
            <ElSelect v-model="createForm.scopes" multiple placeholder="选择权限范围" style="width: 100%">
              <ElOption label="读 (read)" value="read" />
              <ElOption label="写 (write)" value="write" />
              <ElOption label="管理 (admin)" value="admin" />
            </ElSelect>
            <div class="imawx-account-tokens__hint">
              权限范围当前阶段是元数据(标记用途),后续阶段按 scope 鉴权。
            </div>
          </ElFormItem>
          <ElFormItem label="过期时间">
            <ElDatePicker
              v-model="createForm.expiresAt"
              type="datetime"
              placeholder="不填 = 永不过期"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="IP 白名单">
            <ElInput
              v-model="createForm.ipWhitelistText"
              type="textarea"
              :rows="3"
              placeholder="每行一个 IP 或 CIDR,不填表示不限制。例: 42.200.230.156 或 192.168.31.0/24"
            />
          </ElFormItem>
          <!-- 2026-07-03 加:授权配置入口 -->
          <ElFormItem label="MCP 授权">
            <div class="imawx-account-tokens__auth-entry">
              <ElButton @click="openCreateAuthorizeDialog" :icon="Setting">
                {{ createFormAuthorizations.authSummaryText || '分配 MCP 授权' }}
              </ElButton>
              <div class="imawx-account-tokens__hint">
                <span v-if="createForm.restrictMode === 0">
                  全开放模式:可调用当前账号下所有 backend 的所有 tool
                </span>
                <span v-else>
                  严格模式:已授权 {{ createFormAuthorizations.backendCount }} 个 backend ·
                  {{ createFormAuthorizations.toolCount }} 个 tool
                </span>
              </div>
            </div>
          </ElFormItem>
        </ElForm>
      </div>

      <!-- Step 2: 展示明文 + 复制 -->
      <div v-else-if="createStep === 2 && createdToken" class="imawx-account-tokens__created">
        <ElAlert type="success" :closable="false" class="imawx-account-tokens__success-alert">
          <div class="flex items-center gap-2">
            <ArtSvgIcon icon="ri:check-line" class="text-xl" />
            <span>Token 创建成功!请立刻复制保存 —— 关掉弹窗后不再展示。</span>
          </div>
        </ElAlert>
        <div class="imawx-account-tokens__plaintext-box">
          <code ref="plaintextRef" class="imawx-account-tokens__plaintext">{{ createdToken.plaintext }}</code>
        </div>
        <div class="imawx-account-tokens__meta">
          <div><b>用途:</b>{{ createdToken.name }}</div>
          <div><b>前缀:</b><code>{{ createdToken.tokenPrefix }}</code>(完整明文见上)</div>
          <div v-if="createdToken.expiresAt"><b>过期:</b>{{ createdToken.expiresAt }}</div>
          <div><b>创建时间:</b>{{ createdToken.createdAt }}</div>
        </div>
        <ElAlert type="warning" :closable="false" class="imawx-account-tokens__warning-alert">
          ⚠️ 明文 token 不会再次显示!请立刻复制到安全位置(如密码管理器)。
          如丢失只能删除重建。
        </ElAlert>
      </div>

      <template #footer>
        <div v-if="createStep === 1" class="flex-cc gap-2">
          <ElButton @click="createDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="creating" @click="handleCreateSubmit">创建</ElButton>
        </div>
        <div v-else class="flex-cc gap-2">
          <ElButton @click="copyPlaintext">
            <ArtSvgIcon icon="ri:file-copy-line" class="mr-1" />
            复制明文
          </ElButton>
          <ElButton type="primary" @click="finishCreate">完成</ElButton>
        </div>
      </template>
    </ElDialog>

    <!--
      修改授权 dialog(2026-07-03 加)——
      调 PUT /api/sys/tokens/{id}/authorizations 单独更新授权,不改 token 名/哈希/过期。
    -->
    <TokenAuthorizeDialog
      v-model:visible="authorizeDialogVisible"
      :token="authorizeTarget"
      title="修改 MCP 授权"
      @success="reload"
    />

    <!--
      创建时分配授权 dialog(2026-07-03 加)——
      不调 API,只在关闭时把授权配置带回到 createForm,跟其他字段一起提交。
    -->
    <TokenAuthorizeDialog
      v-model:visible="createAuthorizeDialogVisible"
      @selected="onCreateAuthorizeSelected"
    />

    <!-- 导出配置 dialog -->
    <TokenExportConfigDialog v-model:visible="exportDialogVisible" :token="exportToken" />
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import { Plus, Setting } from '@element-plus/icons-vue'
  import {
    createMyTokenSys,
    fetchMyTokensSys,
    hardDeleteMyTokenSys,
    revokeMyTokenSys,
    type ImawxApiToken,
    type ImawxApiTokenCreated,
    type ImawxApiTokenToolRef
  } from '@/api/sys/tokens'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import TokenExportConfigDialog from './components/TokenExportConfigDialog.vue'
  import TokenAuthorizeDialog, { type TokenAuthConfig } from './components/TokenAuthorizeDialog.vue'

  defineOptions({ name: 'ImawxAccountTokens' })

  // ===== 列表 + 搜索 =====
  const tokens = ref<ImawxApiToken[]>([])
  const loading = ref(false)
  const pagination = reactive({ current: 1, size: 20, total: 0 })

  const searchForm = ref<{ name: string; status: '' | 'active' | 'revoked' }>({
    name: '',
    status: ''
  })

  const searchItems = computed(() => [
    {
      key: 'name',
      label: '用途',
      type: 'input',
      props: { placeholder: '模糊搜索用途', clearable: true }
    },
    {
      key: 'status',
      label: '状态',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '全部',
        options: [
          { label: '有效', value: 'active' },
          { label: '已撤销', value: 'revoked' }
        ]
      }
    }
  ])

  // 前端过滤
  const filteredTokens = computed(() => {
    const sf = searchForm.value
    return tokens.value.filter((t) => {
      if (sf.name && !t.name.includes(sf.name)) return false
      if (sf.status && t.status !== sf.status) return false
      return true
    })
  })

  const { columns, columnChecks } = useTableColumns<ImawxApiToken>(() => [
    { prop: 'name', label: '用途', minWidth: 180, showOverflowTooltip: true },
    {
      prop: 'status',
      label: '状态',
      width: 90,
      align: 'center',
      useSlot: true,
      slotName: 'status'
    },
    {
      prop: 'restrictMode',
      label: '授权模式',
      width: 100,
      align: 'center',
      useSlot: true,
      slotName: 'restrictMode'
    },
    {
      prop: 'authorization',
      label: '授权范围',
      minWidth: 200,
      useSlot: true,
      slotName: 'authorization'
    },
    {
      prop: 'tokenPrefix',
      label: '前缀',
      width: 140,
      showOverflowTooltip: true
    },
    {
      prop: 'ipWhitelist',
      label: 'IP 白名单',
      minWidth: 180,
      showOverflowTooltip: true,
      formatter: (row) => formatIpWhitelist(row.ipWhitelist)
    },
    {
      prop: 'lastUsedAt',
      label: '最近调用',
      width: 170,
      formatter: (row) => (row.lastUsedAt ? row.lastUsedAt.replace('T', ' ') : '从未')
    },
    {
      prop: 'expiresAt',
      label: '过期',
      width: 170,
      formatter: (row) => (row.expiresAt ? row.expiresAt.replace('T', ' ') : '永不过期')
    },
    {
      prop: 'createdAt',
      label: '创建时间',
      width: 170,
      formatter: (row) => row.createdAt.replace('T', ' ')
    },
    {
      prop: 'operation',
      label: '操作',
      width: 240,
      fixed: 'right',
      align: 'center',
      useSlot: true,
      slotName: 'operation'
    }
  ])

  async function reload() {
    loading.value = true
    try {
      tokens.value = (await fetchMyTokensSys()) ?? []
      pagination.total = tokens.value.length
    } catch {
      tokens.value = []
      pagination.total = 0
    } finally {
      loading.value = false
    }
  }

  function handleSearch() {
    pagination.current = 1
  }

  function handleReset() {
    searchForm.value = { name: '', status: '' }
    pagination.current = 1
  }

  function handleSizeChange(size: number) {
    pagination.size = size
    pagination.current = 1
  }

  function handleCurrentChange(current: number) {
    pagination.current = current
  }

  function parseIpWhitelistText(value: string): string[] {
    return value
      .split(/[,;\n\r]+/)
      .map((item) => item.trim())
      .filter(Boolean)
  }

  function formatIpWhitelist(values?: string[]): string {
    return values && values.length > 0 ? values.join(', ') : '不限制'
  }

  // ===== 创建 Token =====
  const createDialogVisible = ref(false)
  const createStep = ref<1 | 2>(1)
  const creating = ref(false)
  const createdToken = ref<ImawxApiTokenCreated | null>(null)
  const createFormRef = ref<FormInstance>()
  const plaintextRef = ref<HTMLElement>()

  const createForm = reactive<{
    name: string
    scopes: string[]
    expiresAt: '' | string
    ipWhitelistText: string
    restrictMode: 0 | 1
    authorizedBackends: string[]
    authorizedTools: ImawxApiTokenToolRef[]
  }>({
    name: '',
    scopes: ['read', 'write'],
    expiresAt: '',
    ipWhitelistText: '',
    restrictMode: 1,
    authorizedBackends: [],
    authorizedTools: []
  })

  // 授权摘要(给"分配 MCP 授权"按钮文案用)
  const createFormAuthorizations = computed(() => {
    const b = createForm.authorizedBackends.length
    const t = createForm.authorizedTools.length
    if (createForm.restrictMode === 0) {
      return {
        backendCount: 0,
        toolCount: 0,
        authSummaryText: '全开放模式'
      }
    }
    let text: string
    if (b === 0 && t === 0) text = '分配 MCP 授权 (必填)'
    else text = `已配 ${b} backend / ${t} tool`
    return { backendCount: b, toolCount: t, authSummaryText: text }
  })

  const createRules: FormRules = {
    name: [
      { required: true, message: '请输入用途', trigger: 'blur' },
      { max: 64, message: '最长 64 字符', trigger: 'blur' }
    ],
    scopes: [{ required: true, message: '至少选一个权限', trigger: 'change' }]
  }

  function openCreateDialog() {
    createStep.value = 1
    createdToken.value = null
    createForm.name = ''
    createForm.scopes = ['read', 'write']
    createForm.expiresAt = ''
    createForm.ipWhitelistText = ''
    createForm.restrictMode = 1
    createForm.authorizedBackends = []
    createForm.authorizedTools = []
    createDialogVisible.value = true
  }

  async function handleCreateSubmit() {
    if (!createFormRef.value) return
    try {
      await createFormRef.value.validate()
    } catch {
      return
    }
    // 严格模式必须至少 1 个授权(后端再校验一次,前端先弹错少往返一次)
    if (createForm.restrictMode === 1
        && createForm.authorizedBackends.length === 0
        && createForm.authorizedTools.length === 0) {
      ElMessage.warning('严格模式下请至少分配 1 个 backend 或 1 个 tool')
      return
    }
    creating.value = true
    try {
      const payload = {
        name: createForm.name,
        scopes: createForm.scopes,
        expiresAt: createForm.expiresAt || null,
        ipWhitelist: parseIpWhitelistText(createForm.ipWhitelistText),
        restrictMode: createForm.restrictMode,
        authorizedBackends: createForm.authorizedBackends,
        authorizedTools: createForm.authorizedTools
      }
      const resp = await createMyTokenSys(payload)
      createdToken.value = resp
      createStep.value = 2
      // 后台刷新列表(用户切走后再回来看能看到新 token)
      await reload()
    } catch {
      // 错误已被 request util 弹 ElMessage
    } finally {
      creating.value = false
    }
  }

  // ===== 创建时分配授权 dialog =====
  const createAuthorizeDialogVisible = ref(false)
  function openCreateAuthorizeDialog() {
    createAuthorizeDialogVisible.value = true
  }
  /**
   * 创建模式下,TokenAuthorizeDialog emit('selected') 的回调。
   * 实际把授权写回 createForm,跟其他字段一起在 handleCreateSubmit 提交。
   * 注:TokenAuthorizeDialog 内部用的是自己的 form.restrictMode,跟 createForm 不同步,
   * 我们用 emit 把完整 cfg 拿过来直接覆盖(更可靠)。
   */
  function onCreateAuthorizeSelected(cfg: TokenAuthConfig) {
    createForm.restrictMode = cfg.restrictMode
    createForm.authorizedBackends = cfg.authorizedBackends
    createForm.authorizedTools = cfg.authorizedTools
    ElMessage.success('授权已设置,记得点底部「创建」提交')
  }

  async function copyPlaintext() {
    if (!createdToken.value?.plaintext) return
    try {
      await navigator.clipboard.writeText(createdToken.value.plaintext)
      ElMessage.success('明文已复制到剪贴板')
    } catch {
      if (plaintextRef.value) {
        const range = document.createRange()
        range.selectNodeContents(plaintextRef.value)
        const sel = window.getSelection()
        sel?.removeAllRanges()
        sel?.addRange(range)
        ElMessage.warning('请按 Ctrl+C 复制选中的文本')
      }
    }
  }

  function finishCreate() {
    createDialogVisible.value = false
  }

  function onCreateDialogClose() {
    createStep.value = 1
    createdToken.value = null
  }

  // ===== 修改授权 dialog =====
  const authorizeDialogVisible = ref(false)
  const authorizeTarget = ref<ImawxApiToken | null>(null)
  function openAuthorizeDialog(row: ImawxApiToken) {
    authorizeTarget.value = row
    authorizeDialogVisible.value = true
  }

  // ===== 导出配置 =====
  const exportDialogVisible = ref(false)
  const exportToken = ref<ImawxApiToken | null>(null)

  function openExportDialog(row: ImawxApiToken) {
    exportToken.value = row
    exportDialogVisible.value = true
  }

  // ===== 撤销 / 删除 =====
  async function handleRevoke(row: ImawxApiToken) {
    try {
      await ElMessageBox.confirm(
        `确认撤销「${row.name}」?撤销后调用 API 会立即拒绝。`,
        '撤销 Token',
        { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await revokeMyTokenSys(row.id)
      ElMessage.success('已撤销')
      await reload()
    } catch {
      // 已弹 ElMessage
    }
  }

  async function handleDelete(row: ImawxApiToken) {
    try {
      await ElMessageBox.confirm(
        `硬删「${row.name}」?删除后记录无法恢复。`,
        '删除 Token',
        { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await hardDeleteMyTokenSys(row.id)
      ElMessage.success('已删除')
      await reload()
    } catch {
      // 已弹 ElMessage
    }
  }

  onMounted(reload)
</script>

<style scoped lang="scss">
  .imawx-account-tokens {
    &__alert {
      margin-bottom: 12px;

      code {
        background: var(--el-fill-color-light);
        padding: 1px 6px;
        border-radius: 3px;
        font-size: 11px;
        margin: 0 2px;
      }
    }

    &__form {
      padding: 4px 0;
    }

    &__hint {
      font-size: 11px;
      color: var(--el-text-color-placeholder);
      margin-top: 4px;
      line-height: 1.5;
    }

    &__auth-entry {
      width: 100%;
    }

    &__auth-summary {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 4px;
      align-items: center;
    }

    &__auth-open {
      font-size: 11px;
      color: var(--el-text-color-placeholder);
    }

    &__auth-empty {
      font-size: 11px;
      color: var(--el-color-warning);
      font-weight: 600;
    }

    &__created {
      padding: 4px 0;
    }

    &__success-alert {
      margin-bottom: 16px;
    }

    &__plaintext-box {
      padding: 14px 16px;
      background: var(--el-color-primary-light-9);
      border: 1px dashed var(--el-color-primary-light-5);
      border-radius: 6px;
      margin-bottom: 16px;
    }

    &__plaintext {
      display: block;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 13px;
      color: var(--el-color-primary);
      line-height: 1.6;
      word-break: break-all;
      user-select: all; /* 让用户全选 → Ctrl+C 复制 */
    }

    &__meta {
      padding: 0 4px 16px;
      font-size: 12px;
      color: var(--el-text-color-regular);
      line-height: 1.8;

      code {
        background: var(--el-fill-color-light);
        padding: 1px 6px;
        border-radius: 3px;
        font-size: 11px;
      }
    }

    &__warning-alert {
      margin: 0;
    }
  }
</style>

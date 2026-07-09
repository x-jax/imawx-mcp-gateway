<!--
  imawx-mcp 账号 → Token 分配 MCP 授权对话框(2026-07-03 加)

  把 token 的 mcp server+tool 授权做成 3 段配置:
  1) restrictMode —— 0=全开放 / 1=严格(只能调授权列表里的 backend/tool)
  2) authorizedBackends —— 授权可访问的整个 backend 列表(全 bk_xxx 列表,粗粒度)
  3) authorizedTools —— 授权可调用的具体 tool(backendId + toolName 二元组,细粒度)

  两种场景:
  - 新建 token 时分配(无 tokenId,emit('selected', authConfig) 给父组件 createMyTokenSys 用)
  - 已有 token 重新分配(传 tokenId,保存时调 updateMyTokenAuthorizationsSys)

  关键设计:
  - 数据源:拉 GET /api/sys/tokens/available-backends,拿到当前用户所有 enabled backend + 同步过的 tool
  - UI:ElTree 树形勾选(serverType group → backend → tool),已禁用的 tool 灰掉不让勾
  - "整个 backend 授权" vs "授权具体 tool" 互斥:
    勾了 backend 节点 → 视为整个 backend 授权,不进 authorizedTools
    没勾 backend 但勾了子 tool → 进 authorizedTools
  - 提交时:
    authorizedBackends = 后端勾上的 backend 节点集合
    authorizedTools = 后端勾上的 tool 节点集合(转 {backendId, toolName})
-->
<template>
  <ElDialog
    :model-value="visible"
    :title="title"
    width="720px"
    align-center
    class="imawx-dialog-fixed el-dialog-border"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v) => emit('update:visible', v)"
    @open="onOpen"
    @close="onClose"
  >
    <ElScrollbar v-loading="loading" height="62vh">
      <!-- ===== 授权模式 ===== -->
      <div class="imawx-token-auth-dialog__mode">
        <span class="imawx-token-auth-dialog__mode-label">授权模式:</span>
        <ElRadioGroup v-model="form.restrictMode" :disabled="readonly">
          <ElRadio :value="0">全开放 (可调任何 backend/tool)</ElRadio>
          <ElRadio :value="1">严格 (只能调下方授权的 backend/tool)</ElRadio>
        </ElRadioGroup>
      </div>

      <div v-if="props.token" class="imawx-token-auth-dialog__ip">
        <span class="imawx-token-auth-dialog__mode-label">IP 白名单:</span>
        <ElInput
          v-model="form.ipWhitelistText"
          type="textarea"
          :rows="3"
          placeholder="每行一个 IP 或 CIDR,不填表示不限制。例: 42.200.230.156 或 192.168.31.0/24"
        />
      </div>

      <!-- ===== 授权树(只有非全开放模式才显示)=====
           后端要 authorizedBackends / authorizedTools 之一非空才能保存
           0 模式下后端不强校验,前端也允许空树提交
      -->
      <div v-show="form.restrictMode === 1" class="imawx-token-auth-dialog__tree-wrap">
        <ElDivider content-position="left">
          <span class="imawx-token-auth-dialog__divider-text">
            授权 backend / tool <ElTag size="small" type="info">严格模式必填</ElTag>
          </span>
        </ElDivider>
        <ElEmpty v-if="!loading && backends.length === 0" description="当前账号下还没有可用 backend,请先到「外部 MCP 服务」接入" :image-size="80" />
        <ElTree
          v-else
          ref="treeRef"
          :data="treeData"
          show-checkbox
          node-key="id"
          :default-expand-all="true"
          :default-checked-keys="defaultCheckedKeys"
          :props="{ children: 'children', label: 'label', disabled: 'disabled' }"
          @check="onTreeCheck"
        >
          <template #default="{ data }">
            <div class="imawx-token-auth-dialog__node">
              <ArtSvgIcon
                v-if="data.nodeType === 'group'"
                :icon="groupIcon(data.transportType)"
                class="imawx-token-auth-dialog__icon imawx-token-auth-dialog__icon--group"
              />
              <ArtSvgIcon
                v-else-if="data.nodeType === 'backend'"
                :icon="backendIcon(data.transportType)"
                class="imawx-token-auth-dialog__icon imawx-token-auth-dialog__icon--backend"
              />
              <ArtSvgIcon
                v-else
                icon="ri:function-line"
                class="imawx-token-auth-dialog__icon imawx-token-auth-dialog__icon--tool"
              />
              <span class="imawx-token-auth-dialog__label">
                {{ data.label }}
                <ElTag v-if="data.nodeType === 'tool' && data.disabled" size="small" type="info" effect="plain">禁用</ElTag>
              </span>
              <ElTooltip
                v-if="data.nodeType === 'tool' && data.description"
                :content="data.description"
                placement="top"
                :show-after="300"
              >
                <ArtSvgIcon icon="ri:information-line" class="imawx-token-auth-dialog__desc-icon" />
              </ElTooltip>
              <span v-if="data.nodeType === 'backend'" class="imawx-token-auth-dialog__count">
                {{ data.transportType }} · {{ data.children?.length ?? 0 }} 个 tool
              </span>
            </div>
          </template>
        </ElTree>
      </div>

      <!-- ===== 全开放模式说明 ===== -->
      <ElAlert
        v-show="form.restrictMode === 0"
        type="info"
        :closable="false"
        class="imawx-token-auth-dialog__open-alert"
      >
        <b>全开放模式:</b> 该 token 可调用当前账号下所有 backend 的所有 tool(不校验授权范围)。
        <br />
        <span class="text-xs">
          ⚠️ 高风险模式,只建议临时排查或可信自动化使用。新建 token 建议用「严格」模式 —— 最小权限原则。
        </span>
      </ElAlert>
    </ElScrollbar>

    <template #footer>
      <div class="imawx-token-auth-dialog__footer">
        <div class="imawx-token-auth-dialog__footer-left">
          <span class="imawx-token-auth-dialog__stat">
            <template v-if="form.restrictMode === 1">
              已选 <b>{{ selectedBackendCount }}</b> 个 backend ·
              <b>{{ selectedToolCount }}</b> 个 tool
            </template>
            <template v-else>全开放模式</template>
          </span>
        </div>
        <div class="imawx-token-auth-dialog__footer-right">
          <ElButton v-if="form.restrictMode === 1" @click="toggleSelectAll">
            {{ isSelectAll ? '取消全选' : '全选所有 tool' }}
          </ElButton>
          <ElButton @click="onClose">取消</ElButton>
          <ElButton type="primary" :loading="submitting" :disabled="!canSubmit" @click="onSubmit">
            保存
          </ElButton>
        </div>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchAvailableBackendsSys,
    updateMyTokenAuthorizationsSys,
    type ImawxApiToken,
    type ImawxApiTokenAvailableBackend,
    type ImawxApiTokenToolRef
  } from '@/api/sys/tokens'

  defineOptions({ name: 'ImawxTokenAuthorizeDialog' })

  interface Props {
    visible: boolean
    /** 重新分配时传;不传 = 单纯"选授权"模式,父组件拿 emit('selected', authConfig) 走。 */
    token?: ImawxApiToken | null
    title?: string
  }
  const props = withDefaults(defineProps<Props>(), {
    token: null,
    title: '分配 MCP 授权'
  })
  const readonly = false

  /**
   * 授权配置 payload(传给后端的结构)。
   * <p>对齐后端 McpApiTokenCreateDTO.AuthorizedToolRef / McpApiTokenAuthorizationsUpdateDTO。
   */
  export interface TokenAuthConfig {
    restrictMode: 0 | 1
    ipWhitelist?: string[]
    authorizedBackends: string[]
    authorizedTools: ImawxApiTokenToolRef[]
  }

  const emit = defineEmits<{
    'update:visible': [boolean]
    /** 父组件捕获后调 createMyTokenSys({ ..., restrictMode, authorizedBackends, authorizedTools }) */
    selected: [TokenAuthConfig]
    /** 重新分配保存成功后触发,父组件 reload 列表 */
    success: []
  }>()

  // ===== 树数据 =====
  interface TreeNode {
    id: string
    label: string
    transportType?: string
    nodeType: 'group' | 'backend' | 'tool'
    description?: string
    disabled?: boolean
    children?: TreeNode[]
  }

  const treeData = ref<TreeNode[]>([])
  const defaultCheckedKeys = ref<string[]>([])
  const backends = ref<ImawxApiTokenAvailableBackend[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const treeRef = ref()
  const selectedToolCount = ref(0)
  const selectedBackendCount = ref(0)
  const isSelectAll = ref(false)

  // ===== 表单(restrictMode 双向)=====
  const form = reactive<{ restrictMode: 0 | 1; ipWhitelistText: string }>({
    restrictMode: 1,
    ipWhitelistText: ''
  })

  // ===== 加载 =====
  async function onOpen() {
    loading.value = true
    defaultCheckedKeys.value = []
    form.restrictMode = props.token?.restrictMode ?? 1
    form.ipWhitelistText = props.token?.ipWhitelist?.join('\n') ?? ''
    isSelectAll.value = false
    try {
      backends.value = (await fetchAvailableBackendsSys()) ?? []
      treeData.value = buildTree(backends.value)
      // 重新分配时,预勾已授权的 backend + tool
      if (props.token) {
        defaultCheckedKeys.value = buildDefaultCheckedKeys(props.token)
      }
      await nextTick()
      refreshStat()
    } catch {
      treeData.value = []
      backends.value = []
    } finally {
      loading.value = false
    }
  }

  function onClose() {
    emit('update:visible', false)
  }

  // ===== 树构建 =====
  function buildTree(items: ImawxApiTokenAvailableBackend[]): TreeNode[] {
    // 按 transportType 分组
    const groupMap = new Map<string, TreeNode>()
    for (const b of items) {
      const t = b.transportType || 'OTHER'
      if (!groupMap.has(t)) {
        groupMap.set(t, {
          id: `group:${t}`,
          label: transportGroupLabel(t),
          transportType: t,
          nodeType: 'group',
          disabled: true,
          children: []
        })
      }
      const tools = b.tools ?? []
      groupMap.get(t)!.children!.push({
        id: `backend:${b.backendId}`,
        label: b.serverName,
        transportType: b.transportType,
        nodeType: 'backend',
        children: tools.map((tool) => ({
          id: `tool:${b.backendId}:${tool.name}`,
          label: tool.name,
          transportType: b.transportType,
          nodeType: 'tool',
          description: tool.description,
          disabled: !!tool.disabled // 禁用的 tool 灰掉不让勾
        }))
      })
    }
    return [...groupMap.values()]
  }

  function transportGroupLabel(t: string): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'Streamable HTTP'
    if (t === 'SSE') return 'SSE'
    if (t === 'STDIO') return 'STDIO'
    return t
  }

  /**
   * 把 token 的 authorizedBackends / authorizedTools 转 ElTree 的 checkedKeys:
   * - 整个 backend 授权 → 勾 backend 节点(ElTree 自动联动子节点全勾,统计时按 backend 算)
   * - 单独 tool 授权 → 只勾 tool 节点
   * 注:ElTree 用 show-checkbox + default-checked-keys 时,传 backend 节点 id 会自动勾全部子节点
   */
  function buildDefaultCheckedKeys(token: ImawxApiToken): string[] {
    const keys: string[] = []
    for (const bid of token.authorizedBackends ?? []) {
      keys.push(`backend:${bid}`)
    }
    for (const ref of token.authorizedTools ?? []) {
      keys.push(`tool:${ref.backendId}:${ref.toolName}`)
    }
    return keys
  }

  // ===== 勾选 → 统计 =====
  function onTreeCheck() {
    refreshStat()
  }

  function refreshStat() {
    if (!treeRef.value) return
    const checkedNodes = treeRef.value.getCheckedNodes(false, true) as TreeNode[]
    const toolNodes = checkedNodes.filter((n) => n.nodeType === 'tool' && !n.disabled)
    selectedToolCount.value = toolNodes.length

    // backend 节点数(只看用户显式勾的,不算因父勾自动连带的)
    // 业务上区分:
    //   - 用户勾了 backend 节点 → 视为"整个 backend 授权",不进 authorizedTools
    //   - 用户没勾 backend 但勾了子 tool → 视为"精确 tool 授权",进 authorizedTools
    // 所以我们要找的是用户"主动勾的" backend 节点
    const halfChecked = treeRef.value.getHalfCheckedNodes() as TreeNode[]
    const directBackendNodes = (treeRef.value.store.nodesMap
      ? Object.values(treeRef.value.store.nodesMap as Record<string, any>)
          .filter((n: any) => n.data?.nodeType === 'backend' && n.checked && !n.indeterminate)
          .map((n: any) => n.data)
      : []) as TreeNode[]
    selectedBackendCount.value = directBackendNodes.length

    // 全选状态:所有非禁用 tool 都勾了
    const allToolKeys = getAllEnabledToolKeys()
    isSelectAll.value = allToolKeys.length > 0 && toolNodes.length === allToolKeys.length
    void halfChecked // 占位未用
  }

  function getAllEnabledToolKeys(): string[] {
    const keys: string[] = []
    const walk = (nodes: TreeNode[]) => {
      for (const n of nodes) {
        if (n.nodeType === 'tool' && !n.disabled) keys.push(n.id)
        if (n.children) walk(n.children)
      }
    }
    walk(treeData.value)
    return keys
  }

  function toggleSelectAll() {
    if (!treeRef.value) return
    if (!isSelectAll.value) {
      treeRef.value.setCheckedKeys(getAllEnabledToolKeys())
    } else {
      treeRef.value.setCheckedKeys([])
    }
    refreshStat()
  }

  // ===== 提交校验 =====
  const canSubmit = computed(() => {
    // 全开放模式随便提交;严格模式必须至少 1 个 backend 或 1 个 tool
    if (form.restrictMode === 0) return true
    return selectedBackendCount.value > 0 || selectedToolCount.value > 0
  })

  /**
   * 收集当前 UI 的授权配置,转后端要的格式:
   * - 直接勾的 backend 节点 → 进 authorizedBackends
   * - 直接勾的 tool 节点(父 backend 未被勾)→ 进 authorizedTools
   * 注:ElTree 的 getCheckedNodes() 返回所有 checked 的叶子+中间节点;我们靠"用户主动勾"判别
   */
  function collectAuthConfig(): TokenAuthConfig {
    const ipWhitelist = parseIpWhitelistText(form.ipWhitelistText)
    if (form.restrictMode === 0) {
      return { restrictMode: 0, ipWhitelist, authorizedBackends: [], authorizedTools: [] }
    }
    // 拿所有被勾的 backend 节点(用户显式,不包括因父-子联动自动勾的)
    const store = treeRef.value?.store.nodesMap as Record<string, any> | undefined
    const directBackendNodes = store
      ? (Object.values(store)
          .filter((n: any) => n.data?.nodeType === 'backend' && n.checked && !n.indeterminate)
          .map((n: any) => n.data) as TreeNode[])
      : []
    const directBackendIds = new Set(directBackendNodes.map((n) => (n.id.split(':')[1] ?? '')))

    // 拿所有 checked 的 tool 节点(去掉禁用 + 父 backend 已被显式勾的:后者归到 authorizedBackends)
    const checked = (treeRef.value?.getCheckedNodes(false, true) ?? []) as TreeNode[]
    const tools: ImawxApiTokenToolRef[] = []
    for (const n of checked) {
      if (n.nodeType !== 'tool' || n.disabled) continue
      // id 格式:tool:<backendId>:<toolName>
      const parts = n.id.split(':')
      const backendId = parts[1]
      const toolName = parts.slice(2).join(':')
      // 父 backend 已被显式勾 → 该 tool 由 backend 级授权覆盖,不算精确授权
      if (directBackendIds.has(backendId)) continue
      tools.push({ backendId, toolName })
    }
    return {
      restrictMode: 1,
      ipWhitelist,
      authorizedBackends: [...directBackendIds],
      authorizedTools: tools
    }
  }

  function parseIpWhitelistText(value: string): string[] {
    return value
      .split(/[,;\n\r]+/)
      .map((item) => item.trim())
      .filter(Boolean)
  }

  async function onSubmit() {
    const cfg = collectAuthConfig()
    if (!canSubmit.value) {
      ElMessage.warning('严格模式下请至少勾选 1 个 backend 或 1 个 tool')
      return
    }
    // 新建模式:不调 API,直接 emit('selected') 给父组件走 createMyTokenSys
    if (!props.token) {
      emit('selected', cfg)
      onClose()
      return
    }
    // 已有 token:调 PUT /{id}/authorizations
    submitting.value = true
    try {
      await updateMyTokenAuthorizationsSys(props.token.id, cfg)
      ElMessage.success('授权已更新')
      emit('success')
      onClose()
    } catch {
      // 错误已弹
    } finally {
      submitting.value = false
    }
  }

  // ===== icon =====
  function groupIcon(t: string | undefined): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'ri:cloud-line'
    if (t === 'SSE') return 'ri:broadcast-line'
    if (t === 'STDIO') return 'ri:terminal-box-line'
    return 'ri:question-line'
  }
  function backendIcon(t: string | undefined): string {
    if (t === 'HTTP' || t === 'STREAMABLE_HTTP') return 'ri:plug-line'
    if (t === 'SSE') return 'ri:link'
    if (t === 'STDIO') return 'ri:terminal-line'
    return 'ri:question-line'
  }
</script>

<style scoped lang="scss">
  .imawx-token-auth-dialog {
    &__mode {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px 16px;
      background: var(--el-fill-color-light);
      border-radius: 6px;
      margin-bottom: 12px;
    }

    &__ip {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px 16px;
      background: var(--el-fill-color-lighter);
      border-radius: 6px;
      margin-bottom: 12px;
    }

    &__mode-label {
      flex-shrink: 0;
      font-size: 13px;
      font-weight: 600;
      color: var(--el-text-color-regular);
      padding-top: 6px;
    }

    &__divider-text {
      font-size: 12px;
      font-weight: 600;
      color: var(--el-text-color-regular);
    }

    &__tree-wrap {
      padding: 4px 0;
    }

    &__open-alert {
      margin-top: 12px;
    }

    &__node {
      display: flex;
      align-items: center;
      gap: 6px;
      width: 100%;
      font-size: 13px;
    }

    &__icon {
      flex-shrink: 0;
      font-size: 14px;

      &--group {
        color: var(--el-color-primary);
      }

      &--backend {
        color: var(--el-color-info);
      }

      &--tool {
        color: var(--el-text-color-regular);
      }
    }

    &__label {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: flex;
      align-items: center;
      gap: 4px;
    }

    &__desc-icon {
      color: var(--el-text-color-placeholder);
      cursor: help;
    }

    &__count {
      flex-shrink: 0;
      color: var(--el-text-color-placeholder);
      font-size: 11px;
    }

    &__footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }

    &__footer-left {
      flex: 1;
      min-width: 0;
    }

    &__footer-right {
      display: flex;
      gap: 8px;
    }

    &__stat {
      color: var(--el-text-color-regular);
      font-size: 12px;

      b {
        color: var(--el-color-primary);
        font-weight: 600;
        margin: 0 2px;
      }
    }
  }
</style>

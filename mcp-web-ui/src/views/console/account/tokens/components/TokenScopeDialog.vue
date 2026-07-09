<!--
  imawx-mcp 账号 → Token 分配 MCP 弹框(2026-06-29 新增)

  用基座 ElTree 树形勾选模式(对齐 system/role/modules/role-permission-dialog 的风格),
  把可选的 MCP server+tool 渲染成 3 层树(serverType → server → tool),
  用户勾选要授权的 tool,后端用 PUT /api/sys/account/tokens/{id}/scope 覆盖式写入。

  支持两种场景:
  - 创建新 token 时分配(无 tokenId,只触发"已选 scope"事件,父组件把 scope 跟 label 一起提交)
  - 已有 token 重新分配(传 tokenId,后端先查已分配 scope 预勾选,保存时调 setMyTokenScopeSys)

  关键设计:
  - treeData 用基座 ElTree 约定的 { id, label, children } 结构,node-key = id
  - serverType 顶层 group 用 disabled 隐藏 checkbox(只是展示分组,不参与选择)
  - 提交时把 checkedKeys 拍扁回 List<ImawxTokenScopeItem>(serverId → toolNames[])
  - 全选/展开/收起 3 个工具按钮(对齐 role-permission-dialog 风格)
-->
<template>
  <ElDialog
    :model-value="visible"
    :title="title"
    width="640px"
    align-center
    class="el-dialog-border"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v) => emit('update:visible', v)"
    @open="onOpen"
    @close="onClose"
  >
    <ElScrollbar v-loading="loading" height="60vh">
      <!--
        树:group(serverType)→ server → tool
        - show-checkbox:只 server / tool 节点要 checkbox,group 节点用 disabled 关掉勾选
        - node-key="id":用 id 字符串全局唯一定位
        - check-strictly=false(默认):父选=全选子,半选状态由 ElTree 自动算
        - default-expand-all:打开弹框默认全部展开
      -->
      <ElTree
        ref="treeRef"
        :data="treeData"
        show-checkbox
        node-key="id"
        :default-expand-all="true"
        :default-checked-keys="defaultCheckedKeys"
        :props="{ children: 'children', label: 'label' }"
        @check="onTreeCheck"
      >
        <template #default="{ data }">
          <div class="imawx-token-scope-dialog__node">
            <!--
              group 节点(serverType)显示一个 group icon + 标签
              server 节点显示 server icon + 名称 + tool 数量
              tool 节点显示 tool icon + 名称 + 描述(tooltip)
            -->
            <ArtSvgIcon
              v-if="data.nodeType === 'group'"
              :icon="groupIcon(data.serverType)"
              class="imawx-token-scope-dialog__icon imawx-token-scope-dialog__icon--group"
            />
            <ArtSvgIcon
              v-else-if="data.nodeType === 'server'"
              :icon="serverIcon(data.serverType)"
              class="imawx-token-scope-dialog__icon imawx-token-scope-dialog__icon--server"
            />
            <ArtSvgIcon
              v-else
              icon="ri:function-line"
              class="imawx-token-scope-dialog__icon imawx-token-scope-dialog__icon--tool"
            />
            <span class="imawx-token-scope-dialog__label">{{ data.label }}</span>
            <ElTooltip
              v-if="data.nodeType === 'tool' && data.description"
              :content="data.description"
              placement="top"
              :show-after="300"
            >
              <ArtSvgIcon
                icon="ri:information-line"
                class="imawx-token-scope-dialog__desc-icon"
              />
            </ElTooltip>
            <span v-if="data.nodeType === 'server'" class="imawx-token-scope-dialog__count">
              {{ data.children.length }} 个 tool
            </span>
          </div>
        </template>
      </ElTree>
    </ElScrollbar>

    <template #footer>
      <div class="imawx-token-scope-dialog__footer">
        <div class="imawx-token-scope-dialog__footer-left">
          <span class="imawx-token-scope-dialog__stat">
            已选 <b>{{ selectedToolCount }}</b> 个 tool · 跨
            <b>{{ selectedServerCount }}</b> 个 server
          </span>
        </div>
        <div class="imawx-token-scope-dialog__footer-right">
          <ElButton @click="toggleExpandAll">
            {{ isExpandAll ? '全部收起' : '全部展开' }}
          </ElButton>
          <ElButton @click="toggleSelectAll">
            {{ isSelectAll ? '取消全选' : '全选所有 tool' }}
          </ElButton>
          <ElButton @click="onClose">取消</ElButton>
          <ElButton type="primary" :loading="submitting" :disabled="selectedToolCount === 0" @click="onSubmit">
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
    type ImawxApiTokenAvailableBackend,
    type ImawxApiTokenAvailableTool,
    type ImawxApiTokenToolRef
  } from '@/api/sys/tokens'

  defineOptions({ name: 'ImawxTokenScopeDialog' })

  interface Props {
    visible: boolean
    /** 重新分配时传;不传 = 单纯"选 scope"模式,父组件拿 emit('selected', scope) 走。 */
    tokenId?: string
    /** 弹框标题;默认"分配 MCP 授权范围" */
    title?: string
  }
  const props = withDefaults(defineProps<Props>(), {
    tokenId: '',
    title: '分配 MCP 授权范围'
  })

  const emit = defineEmits<{
    'update:visible': [boolean]
    /** 父组件捕获后调 createMyTokenSys({ label, scope }) */
    selected: [ImawxTokenScopeItem[]]
    /** 重新分配保存成功后触发,父组件 reload 列表 */
    success: []
  }>()

  // ===== 树数据 =====
  interface TreeNode {
    id: string
    label: string
    serverType: string
    nodeType: 'group' | 'server' | 'tool'
    description?: string
    disabled?: boolean
    children?: TreeNode[]
  }

  const treeData = ref<TreeNode[]>([])
  const defaultCheckedKeys = ref<string[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const isExpandAll = ref(true)
  const isSelectAll = ref(false)
  const treeRef = ref()
  const selectedToolCount = ref(0)
  const selectedServerCount = ref(0)

  interface ImawxScopeOptionGroup {
    serverType: string
    label: string
    servers: ImawxScopeOptionServer[]
  }
  interface ImawxScopeOptionServer {
    id: string
    name: string
    serverType: string
    tools: ImawxScopeOptionTool[]
  }
  interface ImawxScopeOptionTool {
    name: string
    label?: string
    description?: string
  }
  interface ImawxTokenScopeEntry {
    serverType: string
    serverId: string
    toolNames: string[]
  }
  interface ImawxTokenScopeItem extends ImawxTokenScopeEntry {
    serverName: string
  }

  // ElTree 的 defaultProps 用基座约定的 string 字段名(label = 'label',children = 'children'),
  // 不用函数形式以兼容基座 TreeOptionProps 类型。
  // nodeType 字段给自定义 slot 区分(group/server/tool 不同 icon),不进 defaultProps。

  // ===== 加载 =====
  async function onOpen() {
    loading.value = true
    defaultCheckedKeys.value = []
    isSelectAll.value = false
    try {
      // 1) 拉可选项树
      const backends = (await fetchAvailableBackendsSys()) ?? []
      const groups = toScopeGroups(backends)
      treeData.value = buildTree(groups)
      // 2) 重新分配时,拉已分配 scope → 预勾选
      if (props.tokenId) {
        const assigned: ImawxTokenScopeEntry[] = []
        defaultCheckedKeys.value = assignedToCheckedKeys(assigned)
      }
      await nextTick()
      // 3) 同步统计
      refreshStat()
    } catch (e) {
      // 错误已弹
      treeData.value = []
    } finally {
      loading.value = false
    }
  }

  function toScopeGroups(backends: ImawxApiTokenAvailableBackend[]): ImawxScopeOptionGroup[] {
    const grouped = new Map<string, ImawxScopeOptionServer[]>()
    for (const backend of backends) {
      const serverType = backend.transportType || 'MCP'
      if (!grouped.has(serverType)) grouped.set(serverType, [])
      grouped.get(serverType)!.push({
        id: backend.backendId,
        name: backend.serverName,
        serverType,
        tools: backend.tools.map((tool: ImawxApiTokenAvailableTool) => ({
          name: tool.name,
          label: tool.name,
          description: tool.description
        }))
      })
    }
    return [...grouped.entries()].map(([serverType, servers]) => ({
      serverType,
      label: serverType,
      servers
    }))
  }

  function onClose() {
    emit('update:visible', false)
  }

  // ===== 树数据组装(scope-options group → ElTree node)=====
  function buildTree(groups: ImawxScopeOptionGroup[]): TreeNode[] {
    return groups.map((g) => ({
      id: `group:${g.serverType}`,
      label: g.label,
      serverType: g.serverType,
      nodeType: 'group' as const,
      disabled: true,   // group 节点不可勾选,只展示分组
      children: g.servers.map((s) => buildServerNode(s))
    }))
  }

  function buildServerNode(s: ImawxScopeOptionServer): TreeNode {
    return {
      id: `server:${s.serverType}:${s.id}`,
      label: s.name,
      serverType: s.serverType,
      nodeType: 'server' as const,
      children: s.tools.map((t) => buildToolNode(s, t))
    }
  }

  function buildToolNode(s: ImawxScopeOptionServer, t: ImawxScopeOptionTool): TreeNode {
    return {
      id: `tool:${s.serverType}:${s.id}:${t.name}`,
      label: t.label || t.name,
      serverType: s.serverType,
      nodeType: 'tool' as const,
      description: t.description
    }
  }

  /**
   * 把后端返回的扁平 scope(按 serverId 分组) → ElTree 的 checkedKeys。
   * 对应 tree node id 规则:`tool:<serverType>:<serverId>:<toolName>`。
   */
  function assignedToCheckedKeys(entries: ImawxTokenScopeEntry[]): string[] {
    const keys: string[] = []
    for (const e of entries) {
      for (const t of e.toolNames) {
        keys.push(`tool:${e.serverType}:${e.serverId}:${t}`)
      }
    }
    return keys
  }

  // ===== 勾选变化 → 同步统计 =====
  function onTreeCheck() {
    refreshStat()
  }

  function refreshStat() {
    if (!treeRef.value) return
    const checked = treeRef.value.getCheckedNodes(false, true) as TreeNode[]
    const toolNodes = checked.filter((n) => n.nodeType === 'tool')
    selectedToolCount.value = toolNodes.length
    const serverIds = new Set(
      toolNodes.map((n) => n.id.split(':')[2]) // tool:EXTERNAL:123:tool1 → 123
    )
    selectedServerCount.value = serverIds.size

    // 全选状态:所有 tool 节点都勾了
    const allToolKeys = getAllToolKeys()
    isSelectAll.value = allToolKeys.length > 0 && toolNodes.length === allToolKeys.length
  }

  function getAllToolKeys(): string[] {
    const keys: string[] = []
    const walk = (nodes: TreeNode[]) => {
      for (const n of nodes) {
        if (n.nodeType === 'tool') keys.push(n.id)
        if (n.children) walk(n.children)
      }
    }
    walk(treeData.value)
    return keys
  }

  function getAllNodeKeys(nodes: TreeNode[]): string[] {
    const keys: string[] = []
    for (const n of nodes) {
      keys.push(n.id)
      if (n.children?.length) keys.push(...getAllNodeKeys(n.children))
    }
    return keys
  }

  // ===== 工具栏:全选/展开收起 =====
  function toggleExpandAll() {
    if (!treeRef.value) return
    const nodes = treeRef.value.store.nodesMap
    Object.values(nodes).forEach((node: any) => {
      // group/server/tool 都展开/收起
      node.expanded = !isExpandAll.value
    })
    isExpandAll.value = !isExpandAll.value
  }

  function toggleSelectAll() {
    if (!treeRef.value) return
    if (!isSelectAll.value) {
      // 全选所有 tool 节点(只勾 tool,让 server 节点变 checked 状态;ElTree 自动联动)
      treeRef.value.setCheckedKeys(getAllToolKeys())
    } else {
      treeRef.value.setCheckedKeys([])
    }
    // setCheckedKeys 不触发 @check,手动刷统计
    refreshStat()
  }

  // ===== 提交 =====
  /**
   * 拍扁 ElTree 选中的 tool 节点 → ImawxTokenScopeItem[]
   * 同一个 server 下的 tool 合并到 toolNames[] 里
   */
  function collectSelected(): ImawxTokenScopeItem[] {
    if (!treeRef.value) return []
    const checked = treeRef.value.getCheckedNodes(false, true) as TreeNode[]
    const toolNodes = checked.filter((n) => n.nodeType === 'tool')
    // 按 serverId 分组
    const grouped = new Map<string, ImawxTokenScopeItem>()
    for (const t of toolNodes) {
      // id 格式:tool:<serverType>:<serverId>:<toolName>
      const parts = t.id.split(':')
      const serverType = parts[1]
      const serverId = parts[2]
      const toolName = parts.slice(3).join(':')   // tool 名本身可能含 ':'(虽然现状不会)
      // 从 treeData 里拿 serverName(可读,后端也用它做冗余)
      const serverNode = findServerNode(serverType, serverId)
      const serverName = serverNode?.label ?? ''
      const key = `${serverType}:${serverId}`
      if (!grouped.has(key)) {
        grouped.set(key, {
          serverType,
          serverId,
          serverName,
          toolNames: []
        })
      }
      grouped.get(key)!.toolNames.push(toolName)
    }
    return [...grouped.values()]
  }

  function findServerNode(serverType: string, serverId: string): TreeNode | undefined {
    for (const g of treeData.value) {
      if (g.serverType !== serverType) continue
      const s = g.children?.find((c) => c.id === `server:${serverType}:${serverId}`)
      if (s) return s
    }
    return undefined
  }

  async function onSubmit() {
    const scope = collectSelected()
    if (scope.length === 0 || scope.every((s) => s.toolNames.length === 0)) {
      ElMessage.warning('请至少勾选 1 个 tool')
      return
    }
    // 两种模式:有 tokenId = 重新分配;无 tokenId = "已选 scope" 事件(给创建流程用)
    if (!props.tokenId) {
      emit('selected', scope)
      onClose()
      return
    }
    submitting.value = true
    try {
      await updateMyTokenAuthorizationsSys(props.tokenId, {
        restrictMode: 1,
        authorizedBackends: scope.map((item) => item.serverId),
        authorizedTools: scope.flatMap((item) =>
          item.toolNames.map((toolName): ImawxApiTokenToolRef => ({
            backendId: item.serverId,
            toolName
          }))
        )
      })
      ElMessage.success('授权范围已更新')
      emit('success')
      onClose()
    } catch {
      // 错误已弹
    } finally {
      submitting.value = false
    }
  }

  // ===== 节点 icon =====
  function groupIcon(serverType: string): string {
    if (serverType === 'EXTERNAL') return 'ri:cloud-line'
    if (serverType === 'DB') return 'ri:database-2-line'
    if (serverType === 'ALIYUN_DNS') return 'ri:global-line'
    if (serverType === 'ALIYUN_OSS') return 'ri:folder-cloud-line'
    if (serverType === 'REDIS') return 'ri:database-line'
    if (serverType === 'KV_DATABASE') return 'ri:key-2-line'
    return 'ri:question-line'
  }
  function serverIcon(serverType: string): string {
    if (serverType === 'EXTERNAL') return 'ri:plug-line'
    if (serverType === 'DB') return 'ri:server-line'
    if (serverType === 'ALIYUN_DNS') return 'ri:earth-line'
    if (serverType === 'ALIYUN_OSS') return 'ri:cloud-line'
    if (serverType === 'REDIS') return 'ri:database-2-line'
    if (serverType === 'KV_DATABASE') return 'ri:key-line'
    return 'ri:question-line'
  }
</script>

<style scoped lang="scss">
  .imawx-token-scope-dialog {
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
      &--server {
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

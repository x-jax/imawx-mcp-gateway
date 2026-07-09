<!--
  imawx-mcp 系统配置页（基座 art-design-pro 风格）

  对应 prd.md 第 3 章 3.2 模块优先级 P2（系统配置页面）+ 第 6 章 6.1 后台管理 API：
  - GET /api/sys/system/config → 列出全部配置项
  - PUT /api/sys/system/config → 覆盖式写入（key 不存在 insert，存在 update）

  数据源：mcp_system_config 表（主键 configKey 字符串）。

  <p>当前用到的 key（详见后端 McpSystemConfigService 注释）：
  - mcp.global.enabled：全局 MCP Server 开关（0=关闭 1=开启）
  - mcp.session.timeout-hours：Session 超时小时数
  - mcp.tool.log.retention-days：调用日志保留天数（默认 90）

  视觉规范:完全使用基座 art-design-pro 的 ArtTableHeader / ArtTable / ArtButtonTable /
  ElTag 等组件,布局用 Tailwind utility,字体走基座 token,不在本文件自定义 CSS 变量。
-->
<template>
  <div class="imawx-system-config art-full-height flex flex-col gap-3">
    <!-- 表格区(基座 ElCard + ArtTableHeader + ArtTable) -->
    <ElCard class="art-table-card flex-1 min-h-0" shadow="never">
      <template #header>
        <div class="flex-cb">
          <h4 class="m-0">系统配置</h4>
          <ElTag v-if="loadError" type="danger" effect="light">
            {{ loadError }}
          </ElTag>
          <ElTag v-else type="success" effect="light">
            {{ rows.length }} 项配置
          </ElTag>
        </div>
      </template>

      <ArtTableHeader
        v-model:columns="columnChecks"
        :loading="loading"
        full-class="imawx-system-config"
        @refresh="reload"
      >
        <template #left>
          <ElSpace wrap>
            <ElButton type="primary" v-ripple @click="openCreateDialog">
              <ArtSvgIcon icon="ri:add-line" class="mr-1" />
              新增配置项
            </ElButton>
            <!--
              2026-07-02 加:改完 mcp.auth.totp-enabled 等"运行时生效"配置后,
              点"应用变更"调 POST /api/sys/system/config/refresh,后端立即
              重新读 DB 缓存,不需要重启。
            -->
            <ElButton type="warning" v-ripple :loading="refreshing" @click="handleRefresh">
              <ArtSvgIcon icon="ri:refresh-line" class="mr-1" />
              应用变更
            </ElButton>
          </ElSpace>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="pagedRows"
        :columns="columns"
        :pagination="pagination"
        empty-text="尚无配置项"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <!-- Key: 等宽字体方便看 -->
        <template #configKey="{ row }">
          <span class="font-mono text-xs text-g-700">{{ row.configKey }}</span>
        </template>

        <!-- Value: 等宽 + 0/1 额外展示开启/关闭 chip -->
        <template #configValue="{ row }">
          <span class="font-mono text-xs text-g-700">{{ row.configValue }}</span>
          <ElTag
            v-if="isBooleanLike(row.configValue)"
            :type="row.configValue === '1' ? 'success' : 'info'"
            size="small"
            effect="light"
            class="ml-2"
          >
            {{ row.configValue === '1' ? '开启' : '关闭' }}
          </ElTag>
        </template>

        <!-- 最近更新 -->
        <template #updatedAt="{ row }">
          <span v-if="row.updatedAt" class="text-xs text-g-500">
            {{ formatTime(row.updatedAt) }}
          </span>
          <span v-else class="text-xs text-g-400">—</span>
        </template>

        <!-- 操作:基座 ArtButtonTable -->
        <template #operation="{ row }">
          <ArtButtonTable type="edit" :row="row" @click="openEditDialog(row)" />
        </template>
      </ArtTable>
    </ElCard>

    <!-- 新增 / 编辑弹窗 -->
    <ElDialog
      v-model="dialogVisible"
      :title="editing ? '编辑配置项' : '新增配置项'"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
        <ElFormItem label="Key" prop="configKey">
          <ElInput
            v-model.trim="form.configKey"
            placeholder="例：mcp.global.enabled"
            :disabled="!!editing"
          />
          <span class="imawx-system-config__form-hint">
            Key 是主键，新建后不可修改。建议用 <code>mcp.&lt;模块&gt;.&lt;字段&gt;</code> 命名。
          </span>
        </ElFormItem>
        <ElFormItem label="Value" prop="configValue">
          <ElInput
            v-model.trim="form.configValue"
            placeholder="例：1（开启）/ 0（关闭）/ 8（小时）/ 90（天）"
          />
        </ElFormItem>
        <ElFormItem label="说明" prop="description">
          <ElInput
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="该配置项的用途（可空）"
            maxlength="200"
            show-word-limit
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchSystemConfigListSys,
    putSystemConfigSys,
    postSystemConfigRefreshSys,
    type ImawxSystemConfig
  } from '@/api/sys/system-config'
  import { useTableColumns } from '@/hooks/core/useTableColumns'

  defineOptions({ name: 'ImawxSystemConfig' })

  // ===== 列表 =====
  const rows = ref<ImawxSystemConfig[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)

  // 客户端分页（数据量小，最多十几条；size 默认 20 一般一页装下）
  const pagination = reactive({ current: 1, size: 20, total: 0 })
  const pagedRows = computed(() => {
    const start = (pagination.current - 1) * pagination.size
    return rows.value.slice(start, start + pagination.size)
  })

  function handleSizeChange(size: number) {
    pagination.size = size
    pagination.current = 1
  }

  function handleCurrentChange(current: number) {
    pagination.current = current
  }

  async function reload() {
    loading.value = true
    loadError.value = null
    try {
      rows.value = (await fetchSystemConfigListSys()) ?? []
      pagination.total = rows.value.length
      // 当前页越界时回 1
      if ((pagination.current - 1) * pagination.size >= rows.value.length) {
        pagination.current = 1
      }
    } catch (e) {
      rows.value = []
      pagination.total = 0
      loadError.value = (e as Error).message || '加载失败'
    } finally {
      loading.value = false
    }
  }

  // ===== 表格列（基座 useTableColumns：columnChecks 跟 columns 双向同步,列设置才生效） =====
  const { columns, columnChecks } = useTableColumns<ImawxSystemConfig>(() => [
    {
      prop: 'configKey',
      label: 'Key',
      minWidth: 240,
      showOverflowTooltip: true,
      useSlot: true,
      slotName: 'configKey'
    },
    {
      prop: 'configValue',
      label: 'Value',
      minWidth: 220,
      showOverflowTooltip: true,
      useSlot: true,
      slotName: 'configValue'
    },
    {
      prop: 'description',
      label: '说明',
      minWidth: 240,
      showOverflowTooltip: true
    },
    {
      prop: 'updatedAt',
      label: '最近更新',
      width: 180,
      align: 'center',
      useSlot: true,
      slotName: 'updatedAt'
    },
    {
      prop: 'operation',
      label: '操作',
      width: 100,
      fixed: 'right',
      align: 'center',
      useSlot: true,
      slotName: 'operation'
    }
  ])

  // ===== 应用变更(2026-07-02 加)=====
  const refreshing = ref(false)
  async function handleRefresh() {
    refreshing.value = true
    try {
      await postSystemConfigRefreshSys()
      // 重新拉一次列表确认 DB 状态
      await reload()
    } catch {
      // axios 拦截器已统一提示
    } finally {
      refreshing.value = false
    }
  }

  // ===== 弹窗 + 表单 =====
  const dialogVisible = ref(false)
  const submitting = ref(false)
  /** null=新增模式；有值=编辑模式（key 锁定） */
  const editing = ref<ImawxSystemConfig | null>(null)
  const formRef = ref()
  const form = reactive({
    configKey: '',
    configValue: '',
    description: ''
  })

  const rules = {
    configKey: [{ required: true, message: 'Key 必填', trigger: 'blur' }]
  }

  function openCreateDialog() {
    editing.value = null
    dialogVisible.value = true
  }

  function openEditDialog(row: ImawxSystemConfig) {
    editing.value = row
    form.configKey = row.configKey
    form.configValue = row.configValue
    form.description = row.description ?? ''
    dialogVisible.value = true
  }

  function resetForm() {
    form.configKey = ''
    form.configValue = ''
    form.description = ''
    editing.value = null
    formRef.value?.clearValidate()
  }

  async function handleSubmit() {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    submitting.value = true
    try {
      await putSystemConfigSys({
        configKey: form.configKey,
        configValue: form.configValue,
        description: form.description || undefined
      })
      dialogVisible.value = false
      await reload()
    } catch {
      // axios 拦截器已统一提示
    } finally {
      submitting.value = false
    }
  }

  // ===== 展示辅助 =====
  /**
   * 把 0/1 这种值显示成"开启 / 关闭"的小 tag。仅对 mcp.global.enabled 之类 boolean 配置项友好展示。
   */
  function isBooleanLike(v: string): boolean {
    return v === '0' || v === '1'
  }

  function formatTime(iso: string): string {
    if (!iso) return ''
    // 后端返回 LocalDateTime 被 jackson 序列化成 "yyyy-MM-ddTHH:mm:ss"（无时区），
    // 前端直接展示，假设是 server GMT+8 时区
    return iso.replace('T', ' ').substring(0, 19)
  }

  onMounted(reload)
</script>

<style scoped lang="scss">
  /**
   * 弹窗里那一行 Key 命名的提示文字不属于基座任何组件,本地实现一次。
   * 表格 / 卡片 / 工具栏 / 行内操作 / chip 全部走基座 token,本文件不再自定义 BEM。
   */
  .imawx-system-config {
    &__form-hint {
      display: block;
      margin-top: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      line-height: 1.5;

      code {
        background: var(--el-fill-color-light);
        padding: 1px 6px;
        border-radius: 3px;
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
        font-size: 11px;
      }
    }
  }
</style>

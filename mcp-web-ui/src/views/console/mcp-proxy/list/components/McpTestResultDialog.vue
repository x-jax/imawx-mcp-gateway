<!--
  imawx-mcp 外部 MCP Server 测试结果对话框

  来源：list 页面点"测试"成功后,弹出来展示本次测试拿到的 Tool 列表(name + description + inputSchema)。
  跟详情页面的 tool 渲染保持同一套样式(用户记忆里有"基座一致"约束)。

  Props:
  - visible   v-model 双向绑定
  - serverName 服务名(用于标题)
  - tools     后端返回的 Tool 预览列表
  - checkedAt 本次测试时间(本地时间,字符串)

  复用规则：McpProxyFormDialog 是 list + detail 共用;本组件只用于 list(详情页有自己的 tool 卡片区),
  所以放在 list/components/ 而非上提。
-->
<template>
  <ElDialog
    :model-value="visible"
    :title="title"
    width="640px"
    top="8vh"
    class="imawx-dialog-fixed imawx-dialog-fixed--compact"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
  >
    <div v-if="tools.length === 0" class="imawx-mcp-test-result__empty">
      <ElEmpty description="测试通过但未发现 Tool" :image-size="80" />
    </div>
    <div v-else class="imawx-mcp-test-result__tools">
      <div v-for="tool in tools" :key="tool.name" class="imawx-mcp-test-result__tool">
        <div class="imawx-mcp-test-result__tool-head">
          <span class="imawx-mcp-test-result__tool-name">{{ tool.name }}</span>
          <ElTag v-if="tool.inputSchema" size="small" type="info">含参数 Schema</ElTag>
        </div>
        <p v-if="tool.description" class="imawx-mcp-test-result__tool-desc">
          {{ tool.description }}
        </p>
        <pre v-if="tool.inputSchema" class="imawx-mcp-test-result__json">{{
          formatJson(tool.inputSchema)
        }}</pre>
      </div>
    </div>

    <template #footer>
      <ElButton type="primary" @click="emit('update:visible', false)">关闭</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { ImawxMcpProxyToolPreview } from '@/api/sys/mcp-proxy'

  interface Props {
    visible: boolean
    serverName?: string
    tools: ImawxMcpProxyToolPreview[]
    checkedAt?: string
  }
  const props = defineProps<Props>()
  const emit = defineEmits<{
    'update:visible': [boolean]
  }>()

  defineOptions({ name: 'ImawxMcpTestResultDialog' })

  /** 标题：测试结果 — 服务名 (N 个 Tool) */
  const title = computed(() => {
    const name = props.serverName ? `「${props.serverName}」` : ''
    const time = props.checkedAt ? ` · ${props.checkedAt}` : ''
    return `测试结果 ${name}（${props.tools.length} 个 Tool）${time}`
  })

  /** 格式化 inputSchema（JSON 字符串 → 多行可读）。失败时原样返回。 */
  function formatJson(s: string): string {
    try {
      return JSON.stringify(JSON.parse(s), null, 2)
    } catch {
      return s
    }
  }
</script>

<style scoped lang="scss">
  /* 固定高度用全局 styles/imawx.scss 的 .imawx-dialog-fixed--compact(600px) */

  .imawx-mcp-test-result {
    &__empty {
      padding: 8px 0;
    }

    &__tools {
      max-height: 60vh;
      overflow-y: auto;
      padding: 4px 2px;
    }

    &__tool {
      padding: 12px;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 4px;
      margin-bottom: 8px;

      &:last-child {
        margin-bottom: 0;
      }
    }

    &__tool-head {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 6px;
    }

    &__tool-name {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-weight: 600;
      font-size: 14px;
      color: var(--el-text-color-primary);
    }

    &__tool-desc {
      margin: 4px 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
      line-height: 1.5;
    }

    &__json {
      margin: 0;
      padding: 12px;
      background: var(--el-fill-color-light);
      border-radius: 4px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      line-height: 1.5;
      max-height: 200px;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-all;
    }
  }
</style>

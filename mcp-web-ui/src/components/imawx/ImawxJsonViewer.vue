<!--
  imawx-mcp-gateway JSON 查看器组件(2026-07-02 加)。

  <p>用法 — 表格里放一个 {@code ImawxJsonViewerLink} cell,点击弹出 ElDialog 显示
  完整 JSON 内容(高亮 + 自动格式化 + 复制按钮)。用于解决 request/response 一坨
  文字看不清的问题(用户原话"对接一个 json 组件,点击弹出可以看到全部")。

  <p>支持两种输入:
  <ul>
    <li>{@code data: any} —— 自动 JSON.stringify + pretty print(支持对象/数组)</li>
    <li>{@code json: string} —— 直接展示(DB 里 argumentsJson/resultJson 都是字符串)</li>
  </ul>
  <p>两者都传时优先用 {@code data}。

  <p>不引第三方 JSON viewer 库 —— 基座没有,highlight.js 已在 dependencies,手写
  ~80 行更可控;格式 = {@code JSON.stringify(obj, null, 2)} + language-json 高亮
  (只取 token class,token 颜色全部用 Element Plus CSS var 自己控制 —— 这样深浅
  色自动跟随基座 {@code html.dark} 切换,不需要 import 任何 hljs 主题 css,
  避免暗黑模式下 {}/,/ 等标点看不见的对比度问题)。
-->
<template>
  <div class="imawx-json-viewer">
    <ElDialog
      v-model="visible"
      :title="title"
      :width="width"
      :close-on-click-modal="true"
      :destroy-on-close="true"
      top="5vh"
      class="imawx-json-viewer__dialog"
      @closed="onClosed"
    >
      <div class="imawx-json-viewer__toolbar">
        <span class="imawx-json-viewer__meta">
          {{ metaText }}
        </span>
        <div class="imawx-json-viewer__actions">
          <ElButton size="small" :icon="DocumentCopy" @click="copyContent">
            {{ copied ? '已复制' : '复制' }}
          </ElButton>
        </div>
      </div>
      <pre class="imawx-json-viewer__pre"><code ref="codeRef" class="language-json" :class="{ 'is-empty': !content }">{{ content }}</code></pre>
      <template #footer>
        <ElButton @click="visible = false">关闭</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, ref, watch } from 'vue'
  import { DocumentCopy } from '@element-plus/icons-vue'
  import hljs from 'highlight.js/lib/core'
  import json from 'highlight.js/lib/languages/json'

  hljs.registerLanguage('json', json)

  /**
   * 紧凑 JSON 字符串(单行,用于 toolbar 显示字节数/字符数);实际展示用 pretty。
   */
  const props = withDefaults(
    defineProps<{
      /**
       * 弹窗显隐 —— v-model 双向绑定。
       */
      modelValue: boolean
      /**
       * 弹窗标题(默认"JSON 详情")。
       */
      title?: string
      /**
       * JSON 数据 —— 对象/数组/字符串/null 都接。
       * 字符串且能 JSON.parse → 自动格式化展示;否则作为原文。
       * 数据为空 + json 为空时显示空态文案。
       */
      data?: unknown
      /**
       * JSON 字符串(DB argumentsJson/resultJson 都是字符串)—— 优先于 data。
       */
      json?: string | null
      /**
       * 弹窗宽度(默认 720px)。
       */
      width?: string | number
    }>(),
    {
      title: 'JSON 详情',
      width: '720px'
    }
  )

  const emit = defineEmits<{
    (e: 'update:modelValue', val: boolean): void
    (e: 'closed'): void
  }>()

  const visible = computed({
    get: () => props.modelValue,
    set: (val: boolean) => emit('update:modelValue', val)
  })

  const codeRef = ref<HTMLElement | null>(null)
  const copied = ref(false)

  /**
   * 解析 props.json → 内容字符串。
   *
   * <p>解析失败保留原文(DB 里偶尔会有非法 JSON,如 {@code "{malformed}"} 或纯文本
   * 错误信息)—— 这种情况下展示原文而不是抛错。
   */
  const content = computed(() => {
    if (props.json != null && props.json !== '') {
      try {
        return JSON.stringify(JSON.parse(props.json), null, 2)
      } catch {
        return props.json
      }
    }
    if (props.data !== undefined && props.data !== null) {
      try {
        return JSON.stringify(props.data, null, 2)
      } catch {
        return String(props.data)
      }
    }
    return ''
  })

  const metaText = computed(() => {
    const raw = content.value
    if (!raw) return '空内容'
    const lines = raw.split('\n').length
    const bytes = new Blob([raw]).size
    return `${lines} 行 · ${formatBytes(bytes)}`
  })

  function formatBytes(b: number): string {
    if (b < 1024) return `${b} B`
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
    return `${(b / 1024 / 1024).toFixed(2)} MB`
  }

  async function copyContent() {
    if (!content.value) return
    try {
      await navigator.clipboard.writeText(content.value)
      copied.value = true
      window.setTimeout(() => (copied.value = false), 1500)
    } catch (e) {
      console.warn('[ImawxJsonViewer] 复制失败:', e)
    }
  }

  /**
   * 每次内容变化 → 高亮一次。
   */
  watch(
    () => [content.value, visible.value],
    () => {
      if (!visible.value) return
      nextTick(() => {
        if (codeRef.value && content.value) {
          // 先清掉旧高亮 class,防止 watcher 在 dialog 复用时残留
          codeRef.value.removeAttribute('data-highlighted')
          hljs.highlightElement(codeRef.value)
        }
      })
    }
  )

  function onClosed() {
    emit('closed')
  }
</script>

<style lang="scss">
  /* unscoped: dialog 是 Teleport 到 body,scoped 选择器命中不到 */
  .imawx-json-viewer__dialog {
    .el-dialog__body {
      padding: 16px 20px;
    }
  }

  .imawx-json-viewer {
    &__toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 8px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--el-border-color-lighter);
    }

    &__meta {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &__actions {
      display: flex;
      gap: 8px;
    }

    &__pre {
      margin: 0;
      max-height: 60vh;
      overflow: auto;
      padding: 14px 16px;
      border-radius: 6px;
      // 2026-07-02 改:背景用 :where(.dark) 选择器显式切(基座 --el-fill-color-blank
      // 全局被覆盖成 transparent,不能用 var 跟随主题)。light 浅灰 + dark 深灰,
      // 跟基座 dialog 背景差异明显,JSON 字符串一眼能找到。
      background: #fafafa;
      border: 1px solid var(--el-border-color-lighter);

      :where(.dark) & {
        background: #1d1e1f;
        border-color: var(--el-border-color-darker);
      }

      code {
        font-family:
          ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
        font-size: 12px;
        line-height: 1.55;
        white-space: pre;
        color: var(--el-text-color-regular);
      }

      code.is-empty {
        color: var(--el-text-color-placeholder);
        font-style: italic;
      }

      // 2026-07-02 加:不引 hljs 主题 css,自己用 Element Plus var 控 token 颜色。
      // hljs highlightElement 会给 token 加 .hljs-keyword / .hljs-string / .hljs-number 等
      // class,这些 class 在这里用 Element Plus var 着色 —— dark 模式下 var 自动变浅色,
      // 整体视觉跟主题保持一致。
      //
      // :where() 把选择器权重降到 0,避免覆盖 hljs 默认行高 / 字体等非颜色属性。
      :where(.hljs-keyword),
      :where(.hljs-literal),
      :where(.hljs-built_in) {
        color: var(--el-color-primary);
      }
      :where(.hljs-string),
      :where(.hljs-meta-string) {
        color: var(--el-color-success);
      }
      :where(.hljs-number),
      :where(.hljs-symbol) {
        color: var(--el-color-warning);
      }
      :where(.hljs-attr),
      :where(.hljs-attribute),
      :where(.hljs-property) {
        color: var(--el-color-info);
      }
      :where(.hljs-comment) {
        color: var(--el-text-color-placeholder);
        font-style: italic;
      }
      :where(.hljs-punctuation) {
        // {}/,/ 这些结构字符 —— dark 模式下 var(--el-text-color-secondary) 是浅灰,
        // 对比度足够;之前 hljs atom-one-light 在 #fafafa 上 #999 几乎看不见。
        color: var(--el-text-color-secondary);
        font-weight: 600;
      }
    }
  }
</style>
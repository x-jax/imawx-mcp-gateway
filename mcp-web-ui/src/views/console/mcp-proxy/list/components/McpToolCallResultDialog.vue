<!--
  imawx-mcp 单个 Tool 调用对话框（admin 调试用）

  来源：详情抽屉的 tool 卡片右侧"测试"按钮 → 打开本 dialog。

  功能：
  - 上半部分：根据 tool 的 inputSchema 渲染参数表单（必填项标红 *）
  - 中间"执行"按钮：调 testMcpProxyToolSys(id, toolName, args) → 渲染结果
  - 下半部分"调用结果"区，按 MCP 协议 content type 分发渲染:
      - structuredContent（MCP 2.0 标准字段） → 美化 JSON
      - text → 启发式 JSON 美化 / 纯文本 pre-wrap
      - image → <img src="data:${mimeType};base64,${data}">
      - audio → <audio controls src="data:${mimeType};base64,${data}">

  2026-07-02 改：去掉之前的"普通模式 / 流式模式" radio —— 流式模式是死代码,
  前端调 /api/sys/mcp-proxy/{id}/tools/{toolName}/test-stream 但后端没实现这个端点,
  选了流式模式实际永远失败。普通模式(test 端点)已经能正确返所有 transport 的结果。
      - resource → 内嵌(text/mimeType 判断) / 链接
      - 未知 → formatJson

  Props:
  - visible     v-model 双向绑定
  - serverId    外部 MCP Server ID(给 API 用)
  - toolName    被测试的 tool 名(标题 + API 用)
  - serverName  服务名(标题用)
  - inputSchema JSON Schema 字符串(渲染参数表单用,后端从快照读的原始字符串)

  Emits:
  - update:visible  关闭时触发
  - loading-change  (toolName, isLoading) 通知父组件更新 tool 卡片按钮 loading 态
-->
<template>
  <ElDialog
    :model-value="visible"
    :title="title"
    width="760px"
    top="8vh"
    class="imawx-dialog-fixed imawx-dialog-fixed--medium"
    :close-on-click-modal="false"
    @update:model-value="onVisibleChange"
  >
    <!-- 顶部：参数表单 + 流式模式说明(2026-07-02 改:统一走流式) -->
    <div class="imawx-mcp-tool-call__toolbar">
      <span class="imawx-mcp-tool-call__toolbar-hint">
        <ArtSvgIcon icon="ri:radio-button-line" />
        按当前参数执行一次 Tool 调用，并写入审计日志
      </span>
    </div>

    <!-- 参数表单 -->
    <div v-if="formFields.length > 0" class="imawx-mcp-tool-call__form">
      <div class="imawx-mcp-tool-call__form-header">
        <span>参数</span>
        <span v-if="requiredCount > 0" class="imawx-mcp-tool-call__required-hint">
          * 必填
        </span>
      </div>
      <ElForm
        ref="formRef"
        :model="formValues"
        label-position="top"
        size="default"
      >
        <ElFormItem
          v-for="field in formFields"
          :key="field.name"
          :label="field.label"
          :prop="field.name"
          :required="field.required"
          :rules="field.required ? requiredRule(field.name) : []"
        >
          <!-- string + enum → select -->
          <ElSelect
            v-if="field.type === 'string' && field.enum"
            v-model="formValues[field.name]"
            :placeholder="field.description || `请选择 ${field.label}`"
            style="width: 100%"
          >
            <ElOption
              v-for="opt in field.enum"
              :key="String(opt)"
              :label="String(opt)"
              :value="opt as string | number"
            />
          </ElSelect>

          <!-- string + (无 enum 或 多行) → textarea;否则 → ElInput -->
          <ElInput
            v-else-if="field.type === 'string'"
            v-model="formValues[field.name]"
            type="textarea"
            :rows="field.isMultiline ? 4 : 1"
            :autosize="field.isMultiline ? { minRows: 3, maxRows: 8 } : undefined"
            :placeholder="field.description || `请输入 ${field.label}`"
          />

          <!-- number / integer -->
          <ElInputNumber
            v-else-if="field.type === 'number' || field.type === 'integer'"
            v-model="formValues[field.name]"
            :placeholder="field.description || `请输入 ${field.label}`"
            style="width: 100%"
          />

          <!-- boolean -->
          <ElSwitch
            v-else-if="field.type === 'boolean'"
            v-model="formValues[field.name]"
          />

          <!-- object / array / 其它 → JSON 文本框 -->
          <ElInput
            v-else
            v-model="formValues[field.name]"
            type="textarea"
            :rows="4"
            :autosize="{ minRows: 3, maxRows: 10 }"
            :placeholder="jsonPlaceholder(field)"
            @blur="validateJsonField(field.name)"
          />

          <div v-if="field.description" class="imawx-mcp-tool-call__field-desc">
            {{ field.description }}
          </div>
        </ElFormItem>
      </ElForm>
    </div>
    <ElEmpty
      v-else
      :description="emptySchemaDescription"
      :image-size="60"
    />

    <!-- 2026-07-02 改:流式模式是默认(后端 /test-stream 端点真实现了,SSE 实时推 logging/progress)。
         streamLogs 在 server 推一个 chunk 时 push 一条,UI 边收边追加。 -->
    <div v-if="streamLogs.length > 0" class="imawx-mcp-tool-call__stream">
      <div class="imawx-mcp-tool-call__stream-header">
        <span>流式日志</span>
        <ElTag size="small" type="info">{{ streamLogs.length }} 条</ElTag>
        <span v-if="executing" class="imawx-mcp-tool-call__stream-live">
          <span class="imawx-mcp-tool-call__pulse" />接收中
        </span>
      </div>
      <div class="imawx-mcp-tool-call__stream-list">
        <div
          v-for="(log, idx) in streamLogs"
          :key="`${log.receivedAt}-${idx}`"
          class="imawx-mcp-tool-call__stream-item"
          :class="`is-${log.level}`"
        >
          <span class="imawx-mcp-tool-call__stream-kind">{{ log.level }}</span>
          <span v-if="log.logger" class="imawx-mcp-tool-call__stream-logger">[{{ log.logger }}]</span>
          <span class="imawx-mcp-tool-call__stream-text">{{ formatStreamData(log.data) }}</span>
        </div>
      </div>
    </div>

    <!-- 结果区:有 result 时展示(callTool 完结后服务端推 result event 触发) -->
    <div v-if="result" class="imawx-mcp-tool-call__result">
      <div class="imawx-mcp-tool-call__result-header">
        <span>调用结果</span>
        <ElTag v-if="result.isError" type="danger" size="small">失败</ElTag>
        <ElTag v-else type="success" size="small">成功</ElTag>
      </div>

      <div v-if="result.isError" class="imawx-mcp-tool-call__error-banner">
        <ArtSvgIcon icon="ri:error-warning-line" />
        <span>外部 MCP Server 返回了错误（isError=true），通常是参数缺失或 schema 不匹配。</span>
      </div>

      <!-- MCP 2.0 structuredContent —— 有值就优先展示 -->
      <div
        v-if="hasStructuredContent"
        class="imawx-mcp-tool-call__structured"
      >
        <div class="imawx-mcp-tool-call__structured-header">
          <ElTag size="small" type="success">structuredContent</ElTag>
          <span class="imawx-mcp-tool-call__structured-hint">
            MCP 2.0 标准结构化输出，server 按 outputSchema 返回的 typed JSON
          </span>
        </div>
        <pre class="imawx-mcp-tool-call__json">{{ formatStructured(result.structuredContent) }}</pre>
      </div>

      <!-- content 列表 —— 按 type 分发渲染 -->
      <div v-if="result.content && result.content.length > 0" class="imawx-mcp-tool-call__contents">
        <div
          v-for="(c, idx) in result.content"
          :key="idx"
          class="imawx-mcp-tool-call__content"
          :class="{ 'is-error': result.isError }"
        >
          <div class="imawx-mcp-tool-call__content-head">
            <ElTag size="small" :type="contentTagType(c)">
              {{ c.type ?? 'unknown' }}
            </ElTag>
          </div>
          <!-- 2026-07-02 改:后端 ContentBlock 字段是 {@code data} 不是 {@code text}(对齐后端 McpInvokeResultVO.ContentBlock) →
             之前用 c.text 渲染永远空,导致 SSE/STDIO 等 text 类响应显示"成功"但内容是空。
             兼容两层:优先 c.data(后端实际字段),fallback c.text(老数据/聚合路由返回格式)。 -->
          <pre v-if="c.type === 'text'" class="imawx-mcp-tool-call__json">{{ formatText(c.data ?? c.text) }}</pre>

          <!-- image → <img> 直渲染 base64 -->
          <div v-else-if="c.type === 'image'" class="imawx-mcp-tool-call__media">
            <img
              v-if="c.data && c.mimeType"
              :src="`data:${c.mimeType};base64,${c.data}`"
              :alt="c.mimeType"
              class="imawx-mcp-tool-call__image"
            />
            <div v-else class="imawx-mcp-tool-call__media-meta">image 内容缺失 data 或 mimeType</div>
          </div>

          <!-- audio → <audio controls> -->
          <div v-else-if="c.type === 'audio'" class="imawx-mcp-tool-call__media">
            <audio
              v-if="c.data && c.mimeType"
              :src="`data:${c.mimeType};base64,${c.data}`"
              controls
              class="imawx-mcp-tool-call__audio"
            />
            <div v-else class="imawx-mcp-tool-call__media-meta">audio 内容缺失 data 或 mimeType</div>
          </div>

          <!-- resource → 内嵌或链接 -->
          <div v-else-if="c.type === 'resource' && c.resource" class="imawx-mcp-tool-call__media">
            <div class="imawx-mcp-tool-call__resource-meta">
              <ArtSvgIcon icon="ri:file-3-line" />
              <span>{{ c.resource.uri ?? 'embedded resource' }}</span>
              <ElTag v-if="c.resource.mimeType" size="small" type="info">
                {{ c.resource.mimeType }}
              </ElTag>
            </div>
            <!-- text/html 类 → iframe 沙箱渲染 -->
            <iframe
              v-if="isHtmlResource(c.resource)"
              :srcdoc="c.resource.text"
              class="imawx-mcp-tool-call__iframe"
              sandbox=""
            />
            <!-- 文本类 → pre -->
            <pre v-else-if="c.resource.text" class="imawx-mcp-tool-call__json">{{ c.resource.text }}</pre>
            <!-- 二进制(blob) → 提示用户下载 -->
            <div v-else-if="c.resource.blob" class="imawx-mcp-tool-call__media-meta">
              二进制资源（{{ c.resource.blob.length }} 字节 base64），前端暂不展示
            </div>
          </div>

          <!-- 未知 type → JSON dump -->
          <pre v-else class="imawx-mcp-tool-call__json">{{ formatJson(c) }}</pre>
        </div>
      </div>
      <ElEmpty
        v-else-if="!hasStructuredContent"
        description="外部 Tool 返回了空 content（仅 isError 标志位，无内容）"
        :image-size="60"
      />
    </div>

    <template #footer>
      <ElButton @click="onCancel">关闭</ElButton>
      <ElButton type="primary" :loading="executing" @click="onExecute">
        执行
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormItemRule } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import type {
    ImawxMcpCallToolContent,
    ImawxMcpCallToolResult
  } from '@/api/sys/mcp-proxy'
  import {
    testMcpProxyToolSys,
    testMcpProxyToolStreamSys
  } from '@/api/sys/mcp-proxy'

  interface Props {
    visible: boolean
    serverId?: string
    toolName?: string
    serverName?: string
    inputSchema?: string
  }
  const props = defineProps<Props>()
  const emit = defineEmits<{
    'update:visible': [boolean]
    /** 通知父组件更新 tool 卡片按钮 loading 态 */
    'loading-change': [toolName: string, loading: boolean]
  }>()

  defineOptions({ name: 'ImawxMcpToolCallResultDialog' })

  // ===== 标题 =====
  const title = computed(() => {
    const t = props.toolName ? `「${props.toolName}」` : 'Tool 调用'
    const s = props.serverName ? ` · ${props.serverName}` : ''
    return `${t}${s}`
  })

// 2026-07-02 改:流式模式是真实现了(后端 POST /test-stream 端点 + SSE 实时推 logging/progress),
// 删之前的"普通/流式"二选一 radio,统一走流式 dialog。
let streamHandle: { cancel: () => void } | null = null
interface StreamLogEntry {
  level: string
  logger?: string
  data: unknown
  receivedAt: number
}
const streamLogs = ref<StreamLogEntry[]>([])

// ===== inputSchema 解析 =====
  interface FormField {
    name: string
    label: string
    type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | string
    description?: string
    enum?: unknown[]
    required: boolean
    /** string 是否多行 —— JSON Schema 没明确标记,启发式:有 format=textarea / multiline / 含 \n 默认值 → true;否则 false */
    isMultiline?: boolean
  }

  const parsedSchema = computed(() => {
    if (!props.inputSchema) return null
    try {
      return JSON.parse(props.inputSchema) as {
        properties?: Record<string, Record<string, unknown>>
        required?: string[]
      }
    } catch {
      return null
    }
  })

  const formFields = computed<FormField[]>(() => {
    const s = parsedSchema.value
    if (!s?.properties) return []
    const requiredSet = new Set(s.required ?? [])
    return Object.entries(s.properties).map(([name, spec]) => {
      const type = (spec.type as FormField['type']) ?? 'string'
      const isMultiline =
        type === 'string' &&
        (spec.format === 'textarea' ||
          spec.format === 'multiline' ||
          (typeof spec.default === 'string' && spec.default.includes('\n')))
      return {
        name,
        label: (spec.title as string | undefined) ?? name,
        type,
        description: spec.description as string | undefined,
        enum: spec.enum as unknown[] | undefined,
        required: requiredSet.has(name),
        isMultiline
      }
    })
  })

  const emptySchemaDescription = computed(() =>
    parsedSchema.value
      ? '该 Tool 无需参数,将以空参数调用'
      : '该 Tool 未声明 inputSchema,将以空参数调用'
  )

  const requiredCount = computed(() => formFields.value.filter((f) => f.required).length)

  // ===== 表单状态 =====
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const formValues = reactive<Record<string, any>>({})
  const formRef = ref<FormInstance | null>(null)

  function defaultValueFor(f: FormField): unknown {
    switch (f.type) {
      case 'boolean':
        return false
      case 'number':
      case 'integer':
        return undefined
      default:
        return ''
    }
  }

  function resetForm(): void {
    for (const key of Object.keys(formValues)) delete formValues[key]
    for (const f of formFields.value) {
      formValues[f.name] = defaultValueFor(f)
    }
    formRef.value?.clearValidate()
  }

  /** 每次 dialog visible=true & toolName 变化时,清空上次结果 / loading / 流式日志 */
  watch(
    () => [props.visible, props.toolName] as const,
    ([vis, name]) => {
      if (vis && name) {
        result.value = null
        streamLogs.value = []
        resetForm()
      }
    },
    { immediate: true }
  )

  // ===== 校验 =====
  function requiredRule(name: string): FormItemRule[] {
    return [
      {
        required: true,
        validator: (_rule, value, callback) => {
          if (value === '' || value === undefined || value === null) {
            callback(new Error(`「${name}」为必填参数`))
          } else {
            callback()
          }
        },
        trigger: 'blur'
      }
    ]
  }
  function validateJsonField(name: string): void {
    const raw = formValues[name]
    if (typeof raw !== 'string' || raw.trim() === '') return
    try {
      JSON.parse(raw)
    } catch (e) {
      ElMessage.warning(`「${name}」JSON 格式不合法: ${(e as Error).message}`)
    }
  }
  function jsonPlaceholder(f: FormField): string {
    const base = f.description || `请输入 ${f.label} (JSON)`
    return `${base}\n例: ${f.type === 'array' ? '["a", "b"]' : '{"key": "value"}'}`
  }

  // ===== 执行 =====
  const executing = ref(false)
  const result = ref<ImawxMcpCallToolResult | null>(null)

  async function onExecute(): Promise<void> {
    if (!props.serverId || !props.toolName) return

    // 1. 跑 ElForm 校验
    try {
      await formRef.value?.validate()
    } catch {
      ElMessage.warning('请先填写必填参数')
      return
    }

    // 2. 收集 args
    const args: Record<string, unknown> = {}
    for (const f of formFields.value) {
      const v = formValues[f.name]
      if (v === '' || v === undefined || v === null) continue
      if ((f.type === 'object' || f.type === 'array') && typeof v === 'string') {
        try {
          args[f.name] = JSON.parse(v)
        } catch {
          ElMessage.warning(`「${f.name}」JSON 格式不合法,未提交`)
          continue
        }
      } else {
        args[f.name] = v
      }
    }

    // 3. 清上次结果,执行
    result.value = null
    streamLogs.value = []
    await runPlain(args)
  }

  async function runPlain(args: Record<string, unknown>): Promise<void> {
    executing.value = true
    emit('loading-change', props.toolName!, true)
    try {
      result.value = await testMcpProxyToolSys(props.serverId!, props.toolName!, args)
    } finally {
      executing.value = false
      emit('loading-change', props.toolName!, false)
    }
  }

  /**
   * 2026-07-02 改:删原来的"普通模式" — 全部走流式。流式版边收边追加 logging,
   * 100 个 chunk × 5 秒 = 8 分钟也不会超时(普通模式会等完才显示)。
   */
  function runStreaming(args: Record<string, unknown>): void {
    executing.value = true
    emit('loading-change', props.toolName!, true)

    const finalize = (): void => {
      if (!executing.value) return
      executing.value = false
      emit('loading-change', props.toolName!, false)
      streamHandle = null
    }

    const inner = testMcpProxyToolStreamSys(props.serverId!, props.toolName!, args, {
      onLogging: (e) => {
        // server 推一个 logging message → 立刻追加到流式日志区
        streamLogs.value.push({
          level: e.level,
          logger: e.logger,
          data: e.data,
          receivedAt: Date.now()
        })
      },
      onProgress: (e) => {
        streamLogs.value.push({
          level: 'progress',
          data: `${e.total ? `${Math.round((e.progress / e.total) * 100)}%` : `${e.progress}`}${e.message ? ` — ${e.message}` : ''}`,
          receivedAt: Date.now()
        })
      },
      onResult: (e) => {
        result.value = {
          content: e.content,
          isError: e.isError,
          structuredContent: e.structuredContent
        }
        finalize()
      },
      onError: (e) => {
        ElMessage.error(`调用失败: ${e.message}`)
        finalize()
      },
      onNetworkError: (err) => {
        ElMessage.error(`流式连接失败: ${err.message}`)
        finalize()
      }
    })

    streamHandle = {
      cancel: () => {
        inner.cancel()
        finalize()
      }
    }
  }

  function onCancel(): void {
    streamHandle?.cancel()
    streamHandle = null
    emit('update:visible', false)
  }

  function onVisibleChange(v: boolean): void {
    if (!v) {
      streamHandle?.cancel()
      streamHandle = null
    }
    emit('update:visible', v)
  }

  /**
   * 流式日志的 data 字段可能是 string/object/array — 统一转 string 给 pre 展示。
   * object/array 走 formatJson 美化(跟其他 text content 一样)。
   */
  function formatStreamData(data: unknown): string {
    if (data === undefined || data === null) return ''
    if (typeof data === 'string') return data
    return formatJson(data)
  }

  // ===== 渲染 helpers =====

  /** 有 structuredContent 展示 */
  const hasStructuredContent = computed(() => {
    const sc = result.value?.structuredContent
    return sc !== undefined && sc !== null
  })

  /**
   * 渲染 text content block。
   *
   * 关键：MCP server 经常把 List<Map> 这种结构化结果序列化成 JSON 字符串塞进 text
   * （外部 server 实现不规范,应该走 structuredContent 但我们管不了）。
   * 启发式:看起来像 JSON（trim 后 {/} 或 [/] 包围）就尝试 parse 美化输出;
   * parse 失败 / 看起来不像 JSON 就原样输出（覆盖错误堆栈 / SQL / 日志场景）。
   */
  function formatText(text: string | undefined): string {
    if (text === undefined || text === null) return ''
    const trimmed = String(text).trim()
    if (
      (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('[') && trimmed.endsWith(']'))
    ) {
      try {
        return JSON.stringify(JSON.parse(String(text)), null, 2)
      } catch {
        return String(text)
      }
    }
    return String(text)
  }

  /** 渲染 structuredContent —— 永远走美化 JSON(本身就是 typed) */
  function formatStructured(value: unknown): string {
    return formatJson(value)
  }

  /** 未知 type → JSON dump */
  function formatJson(obj: unknown): string {
    try {
      return JSON.stringify(obj, null, 2)
    } catch {
      return String(obj)
    }
  }

  /** content block 的 tag 类型 */
  function contentTagType(
    c: ImawxMcpCallToolContent
  ): 'success' | 'warning' | 'info' | 'danger' {
    switch (c.type) {
      case 'text':
        return 'success'
      case 'image':
        return 'warning'
      case 'audio':
        return 'warning'
      case 'resource':
        return 'info'
      default:
        return 'info'
    }
  }

  /** resource 是否 HTML 类型（→ iframe 沙箱渲染） */
  function isHtmlResource(r: NonNullable<ImawxMcpCallToolContent['resource']>): boolean {
    const mt = (r.mimeType ?? '').toLowerCase()
    return mt === 'text/html' || mt === 'text/html+mcp' || mt === 'application/xhtml+xml'
  }
</script>

<style scoped lang="scss">
  /* 固定高度用全局 styles/imawx.scss 的 .imawx-dialog-fixed--medium(680px) */

  .imawx-mcp-tool-call {
    &__toolbar {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 16px;
      padding-bottom: 12px;
      border-bottom: 1px dashed var(--el-border-color-lighter);
    }

    &__toolbar-hint {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    /* 2026-07-02 加:流式日志区 —— 边收边追加 server-sent logging/progress notification */
    &__stream {
      margin-bottom: 16px;
      max-height: 280px;
      overflow-y: auto;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 6px;
      background: var(--el-fill-color-light);
    }

    &__stream-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      border-bottom: 1px solid var(--el-border-color-lighter);
      background: var(--el-fill-color-blank);
      font-size: 13px;
      font-weight: 600;
      position: sticky;
      top: 0;
      z-index: 1;
    }

    &__stream-live {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      margin-left: auto;
      font-size: 12px;
      font-weight: 400;
      color: var(--el-color-primary);
    }

    &__pulse {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--el-color-primary);
      animation: imawx-mcp-pulse 1.2s ease-in-out infinite;
    }

    &__stream-list {
      padding: 8px 12px;
    }

    &__stream-item {
      display: flex;
      align-items: baseline;
      gap: 6px;
      padding: 3px 0;
      font-size: 12px;
      font-family: var(--el-font-family-monospace, 'SFMono-Regular', Consolas, monospace);
      color: var(--el-text-color-regular);
      line-height: 1.5;
    }

    &__stream-kind {
      flex-shrink: 0;
      padding: 1px 6px;
      border-radius: 3px;
      background: var(--el-color-info-light-9);
      color: var(--el-color-info);
      font-size: 10px;
      font-weight: 600;
      text-transform: uppercase;
    }

    &__stream-logger {
      flex-shrink: 0;
      color: var(--el-text-color-placeholder);
      font-size: 11px;
    }

    &__stream-text {
      flex: 1 1 auto;
      word-break: break-all;
    }

    /* 不同 level 颜色 */
    &__stream-item {
      &.is-debug &__stream-kind { background: var(--el-color-info-light-9); color: var(--el-color-info); }
      &.is-info  &__stream-kind { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
      &.is-warn  &__stream-kind { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
      &.is-error &__stream-kind { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
      &.is-progress &__stream-kind { background: var(--el-color-success-light-9); color: var(--el-color-success); }
    }

    @keyframes imawx-mcp-pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.85); }
    }

    &__form {
      margin-bottom: 16px;
    }

    &__form-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;
      font-size: 13px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }

    &__required-hint {
      font-size: 12px;
      font-weight: normal;
      color: var(--el-color-danger);
    }

    &__field-desc {
      margin-top: 2px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      line-height: 1.4;
    }

    &__stream {
      margin-top: 16px;
      padding: 12px;
      background: var(--el-fill-color-blank);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 4px;
    }

    &__stream-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
      font-size: 13px;
      font-weight: 600;
    }

    &__stream-list {
      max-height: 200px;
      overflow-y: auto;
      padding-right: 4px;
    }

    &__stream-item {
      display: flex;
      align-items: baseline;
      gap: 8px;
      padding: 4px 0;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      line-height: 1.5;

      &.is-progress {
        color: var(--el-color-primary);
      }
      &.is-log {
        color: var(--el-text-color-regular);
      }
      &.is-result {
        color: var(--el-color-success);
        font-weight: 600;
      }
      &.is-error {
        color: var(--el-color-danger);
      }
      &.is-done {
        color: var(--el-text-color-placeholder);
      }
    }

    &__stream-kind {
      flex-shrink: 0;
      width: 60px;
      text-align: right;
      font-weight: 600;
      opacity: 0.7;
    }

    &__stream-text {
      flex: 1;
      word-break: break-all;
    }

    &__result {
      margin-top: 16px;
      padding-top: 16px;
      border-top: 1px dashed var(--el-border-color-lighter);
    }

    &__result-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;
      font-size: 13px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }

    &__error-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 12px;
      margin-bottom: 12px;
      background: var(--el-color-danger-light-9);
      border: 1px solid var(--el-color-danger-light-7);
      border-radius: 4px;
      color: var(--el-color-danger);
      font-size: 13px;
      line-height: 1.5;
    }

    &__structured {
      margin-bottom: 12px;
      padding: 10px 12px;
      background: var(--el-color-success-light-9);
      border: 1px solid var(--el-color-success-light-7);
      border-radius: 4px;
    }

    &__structured-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    &__structured-hint {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &__contents {
      max-height: 40vh;
      overflow-y: auto;
      padding: 4px 2px;
    }

    &__content {
      padding: 12px;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 4px;
      margin-bottom: 8px;

      &:last-child {
        margin-bottom: 0;
      }

      &.is-error {
        border-color: var(--el-color-danger-light-7);
      }
    }

    &__content-head {
      margin-bottom: 8px;
    }

    &__media {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    &__image {
      max-width: 100%;
      max-height: 320px;
      object-fit: contain;
      border-radius: 4px;
      background: var(--el-fill-color-light);
    }

    &__audio {
      width: 100%;
    }

    &__iframe {
      width: 100%;
      min-height: 200px;
      max-height: 320px;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 4px;
      background: var(--el-fill-color-light);
    }

    &__resource-meta {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &__media-meta {
      padding: 8px 12px;
      background: var(--el-fill-color-light);
      border-radius: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
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
  }
</style>

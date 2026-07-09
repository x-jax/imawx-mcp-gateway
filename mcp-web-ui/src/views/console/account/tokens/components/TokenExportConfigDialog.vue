<!--
  imawx-mcp Token 导出配置 dialog(2026-07-02 加)

  目标:让用户拿到 token 明文后,一键导出 Claude Desktop / Cursor / Codex / Gemini /
  Continue / Windsurf 等多种客户端的 MCP server 配置,粘贴到对应配置文件就能用。

  <p>支持 7 种格式:
  <ul>
    <li>Claude Desktop —— {@code ~/Library/Application Support/Claude/claude_desktop_config.json}
        (macOS) / {@code %APPDATA%\Claude\claude_desktop_config.json} (Windows)</li>
    <li>Cursor —— {@code ~/.cursor/mcp.json}</li>
    <li>Codex (OpenAI) —— {@code ~/.codex/config.toml} (TOML 格式)</li>
    <li>Gemini CLI —— {@code ~/.gemini/settings.json}</li>
    <li>Continue.dev —— {@code ~/.continue/config.json} (mcpServers 数组)</li>
    <li>Windsurf —— {@code ~/.codeium/windsurf/mcp_config.json}</li>
    <li>通用 JSON —— 兜底格式,任意兼容客户端可手动改</li>
  </ul>

  <p>为什么不后端生成:token 明文只创建时展示一次,后端不持久化存明文。
  让用户**粘贴明文**到 dialog 即可(明文不进任何持久化存储,关 dialog 就清空)。
-->
<template>
  <ElDialog
    :model-value="visible"
    title="导出 MCP Token 配置"
    width="780px"
    top="6vh"
    class="imawx-dialog-fixed imawx-dialog-fixed--large"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
    @open="onOpen"
    @close="onClose"
  >
    <!-- 客户端类型选择 -->
    <ElForm label-width="100px" label-position="right" class="imawx-token-export__form">
      <ElFormItem label="客户端">
        <ElRadioGroup v-model="state.format" @change="regenerate">
          <ElRadioButton
            v-for="opt in formatOptions"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>

      <ElFormItem label="Token 明文">
        <ElInput
          v-model="state.tokenPlaintext"
          type="password"
          placeholder="imwx_xxx(从创建 Token 弹框复制,或粘贴已有 token)"
          show-password
          clearable
          @input="regenerate"
        />
      </ElFormItem>

      <ElFormItem label="Server 名">
        <ElInput
          v-model="state.serverName"
          placeholder="imawx-gateway"
          maxlength="64"
          show-word-limit
          @input="regenerate"
        />
      </ElFormItem>

      <ElFormItem label="Gateway URL">
        <ElInput
          v-model="state.gatewayUrl"
          placeholder="http://127.0.0.1:8080/api/mcp"
          @input="regenerate"
        />
      </ElFormItem>
    </ElForm>

    <!-- 配置文件路径提示 -->
    <ElAlert type="info" :closable="false" class="imawx-token-export__path-alert">
      <div class="flex items-center gap-2">
        <ArtSvgIcon icon="ri:folder-line" />
        <span><b>保存位置:</b>{{ currentFormat.pathHint }}</span>
      </div>
    </ElAlert>

    <!-- 配置预览 -->
    <div class="imawx-token-export__preview-wrap">
      <div class="imawx-token-export__preview-header">
        <span class="imawx-token-export__preview-title">
          <ArtSvgIcon icon="ri:code-box-line" class="mr-1" />
          预览 —— {{ currentFormat.label }} 格式
          <ElTag v-if="currentFormat.isToml" type="warning" size="small" effect="light" class="ml-2">TOML</ElTag>
        </span>
        <ElSpace>
          <ElButton :icon="CopyDocument" size="small" @click="copyConfig">复制</ElButton>
          <ElButton :icon="Download" size="small" type="primary" @click="downloadConfig">下载</ElButton>
        </ElSpace>
      </div>
      <pre class="imawx-token-export__preview"><code>{{ generatedConfig }}</code></pre>
    </div>

    <template #footer>
      <div class="flex-cc gap-2">
        <ElButton @click="closeDialog">关闭</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { CopyDocument, Download } from '@element-plus/icons-vue'
  import { ElMessage } from 'element-plus'
  import type { ImawxApiToken } from '@/api/sys/tokens'

  /**
   * 7 种客户端格式配置(2026-07-02 加)。
   *
   * <p>每种格式定义:
   * <ul>
   *   <li>{@code generator} —— 根据 token / serverName / gatewayUrl 生成对应格式的字符串</li>
   *   <li>{@code pathHint} —— 配置文件保存位置提示(给用户看)</li>
   *   <li>{@code isToml} —— 是否 TOML 格式(只有 Codex 用 TOML,下载时给 .toml 后缀)</li>
   *   <li>{@code filename} —— 下载时的默认文件名</li>
   * </ul>
   *
   * <p>所有格式都基于同样的 "{serverUrl, headers: {Authorization: Bearer imwx_xxx}}" 基础配置,
   * 只是包装 key 跟 mcpServers vs mcp_servers 命名差异。
   */
  type FormatKind = 'claude-desktop' | 'cursor' | 'codex' | 'gemini' | 'continue' | 'windsurf' | 'raw-json'

  interface FormatOption {
    value: FormatKind
    label: string
    pathHint: string
    isToml?: boolean
    filename: string
  }

  const formatOptions: FormatOption[] = [
    {
      value: 'claude-desktop',
      label: 'Claude Desktop',
      pathHint: 'macOS: ~/Library/Application Support/Claude/claude_desktop_config.json  |  Windows: %APPDATA%\\Claude\\claude_desktop_config.json',
      filename: 'claude_desktop_config.json'
    },
    {
      value: 'cursor',
      label: 'Cursor',
      pathHint: '~/.cursor/mcp.json',
      filename: 'cursor_mcp.json'
    },
    {
      value: 'codex',
      label: 'Codex',
      pathHint: '~/.codex/config.toml',
      isToml: true,
      filename: 'codex_config.toml'
    },
    {
      value: 'gemini',
      label: 'Gemini CLI',
      pathHint: '~/.gemini/settings.json',
      filename: 'gemini_settings.json'
    },
    {
      value: 'continue',
      label: 'Continue.dev',
      pathHint: '~/.continue/config.json',
      filename: 'continue_config.json'
    },
    {
      value: 'windsurf',
      label: 'Windsurf',
      pathHint: '~/.codeium/windsurf/mcp_config.json',
      filename: 'windsurf_mcp_config.json'
    },
    {
      value: 'raw-json',
      label: '通用 JSON',
      pathHint: '通用配置,任意兼容客户端可手动适配',
      filename: 'mcp_config.json'
    }
  ]

  interface Props {
    visible: boolean
    /** 当前 token(从列表行传入,展示 token 名称 / prefix) */
    token?: ImawxApiToken | null
  }
  const props = defineProps<Props>()
  const emit = defineEmits<{
    'update:visible': [boolean]
  }>()

  defineOptions({ name: 'ImawxTokenExportDialog' })

  // ===== 表单状态 =====
  const state = reactive({
    format: 'claude-desktop' as FormatKind,
    tokenPlaintext: '',
    serverName: 'imawx-gateway',
    // 2026-07-02 改:标准 MCP 端点路径是 /mcp(不是 /api/mcp)——
    // 跟 Claude Desktop / Cursor / Postman MCP 客户端默认路径一致,
    // 也是 MCP 协议官方 server 约定(/mcp)。
    // window.location.origin 用 vite proxy 转发到后端 8080 端口。
    gatewayUrl: 'http://127.0.0.1:8080/mcp'
  })

  const currentFormat = computed(
    () => formatOptions.find((o) => o.value === state.format) || formatOptions[0]
  )

  // ===== 配置生成器(7 种格式) =====
  /**
   * Claude Desktop —— 官方文档 {@code mcpServers.{name}.{url, headers}}。
   *
   * <p>2024 年起 Claude Desktop 支持 HTTP/SSE transport,headers 用于传 Authorization。
   */
  function genClaudeDesktop(): string {
    return JSON.stringify(
      {
        mcpServers: {
          [state.serverName]: {
            url: state.gatewayUrl,
            headers: { Authorization: `Bearer ${state.tokenPlaintext}` }
          }
        }
      },
      null,
      2
    )
  }

  /** Cursor —— 跟 Claude Desktop 一致,同款 schema。 */
  function genCursor(): string {
    return genClaudeDesktop()
  }

  /**
   * Codex (OpenAI CLI) —— 2025 年新增 MCP 集成,格式是 TOML。
   * {@code [mcp_servers.{name}]} section,headers 用 {@code http_headers} key。
   */
  function genCodex(): string {
    // 简单字符串拼接(避免引入 toml 库),格式跟 Codex 文档一致
    const lines = [
      `# imawx-mcp-gateway MCP server config for Codex`,
      `# ${state.serverName}  (token: imwx_***,保存到本机)`,
      '',
      `[mcp_servers.${state.serverName}]`,
      `url = "${state.gatewayUrl}"`,
      ``,
      `[mcp_servers.${state.serverName}.http_headers]`,
      `Authorization = "Bearer ${state.tokenPlaintext}"`
    ]
    return lines.join('\n')
  }

  /**
   * Gemini CLI —— schema 跟 Claude Desktop 类似,但 key 是 {@code httpUrl} 不是 {@code url}。
   * (Gemini CLI 0.x 的 mcpServers schema)
   */
  function genGemini(): string {
    return JSON.stringify(
      {
        mcpServers: {
          [state.serverName]: {
            httpUrl: state.gatewayUrl,
            headers: { Authorization: `Bearer ${state.tokenPlaintext}` }
          }
        }
      },
      null,
      2
    )
  }

  /**
   * Continue.dev —— schema 用 {@code mcpServers} **数组**(不是对象),每个元素一个 server。
   * 字段名: {@code name} / {@code url} / {@code headers}。
   */
  function genContinue(): string {
    return JSON.stringify(
      {
        mcpServers: [
          {
            name: state.serverName,
            url: state.gatewayUrl,
            headers: { Authorization: `Bearer ${state.tokenPlaintext}` }
          }
        ]
      },
      null,
      2
    )
  }

  /**
   * Windsurf (Codeium) —— schema 用 {@code mcpServers.{name}.serverUrl + headers}。
   * 字段名跟其他家略不同(serverUrl 替代 url)。
   */
  function genWindsurf(): string {
    return JSON.stringify(
      {
        mcpServers: {
          [state.serverName]: {
            serverUrl: state.gatewayUrl,
            headers: { Authorization: `Bearer ${state.tokenPlaintext}` }
          }
        }
      },
      null,
      2
    )
  }

  /** 通用 JSON —— 跟 Claude Desktop 一样,但加 schema 注释给用户参考。 */
  function genRawJson(): string {
    return JSON.stringify(
      {
        mcpServers: {
          [state.serverName]: {
            url: state.gatewayUrl,
            transport: 'http',
            headers: { Authorization: `Bearer ${state.tokenPlaintext}` }
          }
        }
      },
      null,
      2
    )
  }

  const generators: Record<FormatKind, () => string> = {
    'claude-desktop': genClaudeDesktop,
    cursor: genCursor,
    codex: genCodex,
    gemini: genGemini,
    continue: genContinue,
    windsurf: genWindsurf,
    'raw-json': genRawJson
  }

  const generatedConfig = computed(() => {
    const gen = generators[state.format]
    return gen()
  })

  function regenerate() {
    // 触发 computed 重新计算(响应式 state 改了会自动重算)
  }

  // ===== 复制 / 下载 =====
  async function copyConfig() {
    try {
      await navigator.clipboard.writeText(generatedConfig.value)
      ElMessage.success('配置已复制到剪贴板')
    } catch {
      ElMessage.warning('复制失败,请手动选中复制')
    }
  }

  /**
   * 下载配置文件 —— 用 Blob + a[download] 触发浏览器下载。
   * 文件名按 format 选对应后缀(.json / .toml),后端 MIME 用 text/plain 避免 BOM。
   */
  function downloadConfig() {
    const content = generatedConfig.value
    const mime = currentFormat.value.isToml ? 'text/plain;charset=utf-8' : 'application/json;charset=utf-8'
    const blob = new Blob([content], { type: mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = currentFormat.value.filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success(`已下载 ${currentFormat.value.filename}`)
  }

  // ===== 打开 / 关闭 =====
  function onOpen() {
    // gatewayUrl 默认用当前页面的 origin(避免写死)
    if (typeof window !== 'undefined' && window.location?.origin) {
      // 2026-07-02 改:标准 MCP 端点 /mcp(不是 /api/mcp)
      state.gatewayUrl = `${window.location.origin}/mcp`
    }
  }

  function onClose() {
    // 关 dialog 清空 plaintext(明文不进任何持久化存储,最小化暴露窗口)
    state.tokenPlaintext = ''
  }

  function closeDialog() {
    emit('update:visible', false)
  }
</script>

<style scoped lang="scss">
  .imawx-token-export {
    &__form {
      padding: 4px 0 0;
    }

    &__path-alert {
      margin: 0 0 12px;
      font-size: 12px;
    }

    &__preview-wrap {
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 6px;
      overflow: hidden;
      background: var(--el-fill-color-blank);
    }

    &__preview-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      background: var(--el-fill-color-light);
      border-bottom: 1px solid var(--el-border-color-lighter);
    }

    &__preview-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--el-text-color-regular);
      display: inline-flex;
      align-items: center;
    }

    &__preview {
      margin: 0;
      padding: 12px 14px;
      max-height: 360px;
      overflow: auto;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      line-height: 1.6;
      color: var(--el-text-color-primary);
      background: var(--el-fill-color-blank);
      white-space: pre;
      word-break: keep-all;
    }
  }
</style>

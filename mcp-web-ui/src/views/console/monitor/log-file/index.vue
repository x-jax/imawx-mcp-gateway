<!--
  imawx-mcp 实时日志文件页(2026-07-02 重写;2026-07-07 改 WebSocket 文件监听刷新)。

  <p>原设计:左 sidebar 文件列表 + 右 main 顶部工具栏(尾部行数 + 自动刷新) + textarea 实时查看。
  改(用户原话"日志文件这块删除掉,日志显示不全,没有最新的数据。右键文件弹出选择,
  1.下载, 2. 实时刷新/暂停刷新"):
  <ol>
    <li><b>删顶部工具栏</b> —— 取消"尾部行数" ElInputNumber 和"每 5 秒自动刷新" ElCheckbox;
        保留 meta info(行数/大小/状态) + 文件名 + 下载按钮。</li>
    <li><b>保留 textarea</b> —— 日志显示区不动,所有用户从文件列表切的文件都进这里。</li>
    <li><b>加文件项右键菜单</b>:
      <ol>
        <li>下载 —— 走原 downloadLogFileSys</li>
        <li>实时刷新 / 暂停刷新(toggle) —— 改用 WebSocket 订阅
            /ws/sys/log-files;暂停时关闭 WebSocket,resume 重开</li>
      </ol>
    </li>
  </ol>

  <p>实时刷新要点(WebSocket 模式,2026-07-07):
  <ul>
    <li>后端监听日志目录文件修改事件,有变化则推一条 {@code tail} 消息(lines + fileSize)</li>
    <li>文件被 rotate(fileSize 变小)→ 后端推 {@code rotate} 事件,前端重置 logText</li>
    <li><b>进入页面 → 默认开启 WebSocket 订阅</b></li>
    <li><b>离开页面 → 关闭 WebSocket</b>(onBeforeUnmount)</li>
    <li>用户主动"暂停"才调 close();"继续"重开新连接</li>
  </ul>
-->
<template>
  <div class="imawx-log-file art-full-height">
    <!-- 左侧:文件列表 sidebar -->
    <ElCard class="imawx-log-file__sidebar" shadow="never">
      <template #header>
        <div class="flex-cb">
          <h4 class="m-0">日志文件</h4>
          <ElTooltip v-if="filesError" :content="filesError" placement="top">
            <ElTag type="danger" size="small" effect="light">加载失败</ElTag>
          </ElTooltip>
          <ElTag v-else type="info" size="small" effect="light">{{ files.length }} 个</ElTag>
        </div>
      </template>
      <div v-if="filesLoading" class="imawx-log-file__sidebar-loading">
        <ElIcon class="is-loading"><Loading /></ElIcon>
        <span class="ml-2">加载中…</span>
      </div>
      <ElScrollbar v-else class="imawx-log-file__sidebar-scroll">
        <div v-for="f in files" :key="f.name" class="imawx-log-file__group">
          <ul class="imawx-log-file__file-list">
            <li
              :class="['imawx-log-file__file-item', { 'is-active': selectedFile === f.name, 'is-refreshing': refreshingFile === f.name }]"
              @click="handleSelectFile(f)"
              @contextmenu.prevent="openContextMenu($event, f)"
            >
              <div class="imawx-log-file__file-name" :title="f.name">
                <ArtSvgIcon
                  :icon="f.gzipped ? 'ri:file-zip-line' : 'ri:file-text-line'"
                />
                <span class="truncate">{{ f.shortName }}</span>
              </div>
              <div class="imawx-log-file__file-meta">
                <span>{{ formatSize(f.size) }}</span>
                <span>{{ formatTime(f.lastModified) }}</span>
              </div>
              <ElTag
                v-if="refreshingFile === f.name"
                type="warning"
                size="small"
                effect="light"
                class="imawx-log-file__file-refreshing-tag"
              >
                <ElIcon class="is-loading"><Loading /></ElIcon>
                实时刷新
              </ElTag>
            </li>
          </ul>
        </div>
        <ElEmpty v-if="files.length === 0" description="暂无日志文件" />
      </ElScrollbar>
    </ElCard>

    <!-- 右侧:meta + 日志内容 -->
    <div class="imawx-log-file__main">
      <!-- 极简 meta 行(删了"尾部行数"和"自动刷新"输入控件,只剩状态信息 + 下载按钮) -->
      <ElCard class="imawx-log-file__toolbar" shadow="never">
        <div class="flex-cb">
          <div class="flex-cc gap-2">
            <ElTag v-if="viewError" type="danger" effect="light" size="small">
              {{ viewError }}
            </ElTag>
            <ElTag v-else-if="lastView" type="success" effect="light" size="small">
              {{ lastView.lines.length }} 行 · {{ formatSize(lastView.fileSize) }}
            </ElTag>
            <ElTag
              v-if="refreshingFile"
              :type="paused ? 'info' : 'warning'"
              effect="dark"
              size="small"
            >
              <ElIcon v-if="!paused" class="is-loading"><Loading /></ElIcon>
              <ArtSvgIcon v-else icon="ri:pause-circle-line" />
              {{ paused ? '已暂停' : '实时刷新中' }} · {{ refreshingFile }}
            </ElTag>
            <span class="text-g-500 text-xs">{{ selectedFile || '（未选择文件）' }}</span>
          </div>
          <div class="flex-cc gap-2">
            <ElButton
              v-if="selectedFile"
              :type="refreshingFile ? 'warning' : 'success'"
              size="small"
              :icon="refreshingFile && !paused ? 'VideoPause' : 'VideoPlay'"
              @click="handleToggleRefreshFromToolbar"
            >
              {{ refreshingFile && !paused ? '暂停刷新' : (refreshingFile && paused ? '继续刷新' : '实时刷新') }}
            </ElButton>
            <ArtIconButton
              v-if="selectedFile"
              icon="ri:download-fill"
              @click="handleDownload"
            />
          </div>
        </div>
      </ElCard>

      <!-- 日志内容区(保留原 textarea,撑满 main 剩余高度) -->
      <ElCard class="imawx-log-file__content" shadow="never">
        <div ref="viewerRef" class="imawx-log-file__viewer" v-loading="viewLoading">
          <ElInput
            ref="logInputRef"
            v-model="logText"
            type="textarea"
            :rows="1"
            readonly
            resize="none"
            placeholder="点击左侧文件查看日志;右键文件可选 1.下载 / 2.实时刷新"
            class="imawx-log-file__textarea"
          />
        </div>
      </ElCard>
    </div>

    <!--
      右键菜单(2026-07-02 加)—— 用 teleport 到 body,避免被 ElCard overflow:hidden 裁掉。
      :show 控制显隐,失焦自动关。
    -->
    <Teleport to="body">
      <ul
        v-show="contextMenu.visible"
        class="imawx-log-file__ctx-menu"
        :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
      >
        <li
          class="imawx-log-file__ctx-item"
          @click="handleDownloadFromMenu"
        >
          <ArtSvgIcon icon="ri:download-2-line" />
          <span>下载</span>
        </li>
        <li
          class="imawx-log-file__ctx-item"
          :class="{ 'is-active': contextMenu.file && refreshingFile === contextMenu.file.name }"
          @click="handleToggleRefreshFromMenu"
        >
          <ArtSvgIcon
            :icon="
              contextMenu.file && refreshingFile === contextMenu.file.name
                ? 'ri:pause-circle-line'
                : 'ri:play-circle-line'
            "
          />
          <span>
            {{
              contextMenu.file && refreshingFile === contextMenu.file.name
                ? '暂停刷新'
                : '实时刷新'
            }}
          </span>
        </li>
      </ul>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { Loading, VideoPause, VideoPlay } from '@element-plus/icons-vue'
  import { nextTick } from 'vue'
  import {
    downloadLogFileSys,
    fetchLogFilesSys,
    viewLogTailSys,
    type ImawxLogFile
  } from '@/api/sys/log-files'

  defineOptions({ name: 'ImawxLogFile' })

  // ===== 文件列表 =====
  interface FileItem extends ImawxLogFile {
    shortName: string
  }
  const files = ref<FileItem[]>([])
  const filesLoading = ref(false)
  const filesError = ref<string | null>(null)

  const activeFiles = computed(() => files.value.filter((f) => f.category === 'active'))
  const archiveFiles = computed(() => files.value.filter((f) => f.category === 'archive'))

  function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
  }

  function formatTime(epoch: number): string {
    const d = new Date(epoch)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  }

  async function loadFiles() {
    filesLoading.value = true
    filesError.value = null
    try {
      const raw = (await fetchLogFilesSys()) ?? []
      files.value = raw.map((f) => ({
        ...f,
        shortName: f.name.replace(/^archive\//, '')
      }))
      if (!selectedFile.value) {
        const active = activeFiles.value.find((f) => !f.gzipped)
        if (active) selectedFile.value = active.name
      }
    } catch (e) {
      files.value = []
      filesError.value = (e as Error).message || '加载文件列表失败'
    } finally {
      filesLoading.value = false
    }
  }

  // ===== 实时查看 + WebSocket 订阅 =====
  const selectedFile = ref<string>('')
  const logText = ref('')
  const lastView = ref<{
    file: string
    lines: string[]
    fileSize: number
  } | null>(null)
  const logInputRef = ref<InstanceType<typeof import('element-plus').ElInput> | null>(null)
  const viewerRef = ref<HTMLElement | null>(null)
  const viewLoading = ref(false)
  const viewError = ref<string | null>(null)

  // 当前正在刷新的文件 name(全局唯一,同一时间只刷一个文件 —— 跟 IDE 行为一致)
  const refreshingFile = ref<string>('')
  const paused = ref(false)
  /** WebSocket 连接 —— 后端通过文件监听推送增量日志。 */
  let logWs: WebSocket | null = null
  /**
   * 当前订阅的文件 name —— 用于 onmessage 时校验:如果后端推送的是已切走的旧文件,丢弃。
   * 切文件时一定先 close 旧连接,理论上不会有冲突,这里再保险一次。
   */
  let subscribedFile = ''

  function snapshotScroll(): { wasAtBottom: boolean; prevScrollTop: number } {
    const el = logInputRef.value?.textarea as HTMLTextAreaElement | undefined
    if (!el) return { wasAtBottom: true, prevScrollTop: 0 }
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 4
    return { wasAtBottom: atBottom, prevScrollTop: el.scrollTop }
  }

  function restoreScroll(prev: { wasAtBottom: boolean; prevScrollTop: number }): void {
    const el = logInputRef.value?.textarea as HTMLTextAreaElement | undefined
    if (!el) return
    if (prev.wasAtBottom) {
      el.scrollTop = el.scrollHeight
    } else {
      el.scrollTop = Math.min(prev.prevScrollTop, el.scrollHeight - el.clientHeight)
    }
  }

  function scrollToBottom() {
    const el = logInputRef.value?.textarea as HTMLTextAreaElement | undefined
    if (el) el.scrollTop = el.scrollHeight
  }

  /**
   * 初次 / 切文件 —— 通过 WebSocket 拿到服务端首帧尾部日志后渲染。
   */
  async function reloadInitial() {
    closeLogWs()
    refreshingFile.value = ''
    paused.value = false
    logText.value = ''
    lastView.value = null
    if (selectedFile.value) {
      const file = files.value.find((f) => f.name === selectedFile.value)
      if (file) await startRefresh(file)
    }
  }

  function wsUrl(file: string): string {
    const rawBase = String(import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')
    const base =
      rawBase && rawBase !== '/'
        ? rawBase
        : `${window.location.protocol}//${window.location.host}`
    const wsBase = base.replace(/^https:/i, 'wss:').replace(/^http:/i, 'ws:')
    return `${wsBase}/ws/sys/log-files?file=${encodeURIComponent(file)}`
  }

  function applyTail(data: { file: string; fileSize: number; lines: string[] }, append: boolean) {
    const scrollSnap = snapshotScroll()
    if (append && logText.value) {
      logText.value = `${logText.value}\n${data.lines.join('\n')}`
    } else {
      logText.value = data.lines.join('\n')
    }
    lastView.value = {
      file: data.file,
      lines: data.lines,
      fileSize: data.fileSize
    }
    viewError.value = null
    nextTick(() => restoreScroll(scrollSnap))
  }

  function openLogWs(file: string) {
    closeLogWs()
    subscribedFile = file
    let initialFrame = true
    logWs = new WebSocket(wsUrl(file))
    logWs.onmessage = (ev) => {
      if (subscribedFile !== file) return
      try {
        const payload = JSON.parse(String(ev.data)) as {
          type: 'tail' | 'rotate' | 'error'
          data?: {
            message?: string
            file: string
            fileSize: number
            lines: string[]
          }
        }
        if (payload.type === 'error') {
          viewError.value = payload.data?.message || '日志订阅失败'
          return
        }
        if (!payload.data) return
        const data = payload.data as {
          file: string
          fileSize: number
          lines: string[]
        }
        if (payload.type === 'rotate') {
          initialFrame = false
          applyTail(data, false)
          nextTick(() => scrollToBottom())
        } else {
          applyTail(data, !initialFrame)
          initialFrame = false
        }
      } catch {
        // ignore malformed frame
      }
    }
    logWs.onerror = () => {
      viewError.value = 'WebSocket 连接异常,请检查反向代理是否支持 Upgrade'
    }
    logWs.onclose = () => {
      if (!paused.value && subscribedFile === file) {
        viewError.value = viewError.value || 'WebSocket 连接已断开,请刷新页面'
      }
    }
  }

  function closeLogWs() {
    if (logWs) {
      logWs.onclose = null
      logWs.close()
      logWs = null
    }
    subscribedFile = ''
  }

  /**
   * 启动实时刷新 —— 用户手动触发或初次加载。
   * <p>走 WebSocket 订阅,由后端文件监听推增量。
   */
  async function startRefresh(file: ImawxLogFile) {
    if (refreshingFile.value === file.name) return
    if (selectedFile.value !== file.name) {
      selectedFile.value = file.name
    }
    refreshingFile.value = file.name
    paused.value = false
    openLogWs(file.name)
  }

  function stopRefresh() {
    closeLogWs()
    refreshingFile.value = ''
    paused.value = false
  }

  function pauseRefresh() {
    paused.value = true
    closeLogWs()
  }

  function resumeRefresh() {
    if (!refreshingFile.value) return
    paused.value = false
    openLogWs(refreshingFile.value)
  }

  /**
   * 点击 sidebar 文件:切换 selectedFile + 重置 WebSocket 订阅新文件。
   */
  async function handleSelectFile(file: ImawxLogFile) {
    if (selectedFile.value === file.name) return
    selectedFile.value = file.name
    await reloadInitial()
  }

  // ===== 顶部 toolbar 按钮(实时刷新 toggle) =====
  function handleToggleRefreshFromToolbar() {
    if (!selectedFile.value) {
      ElMessage.warning('请先选择日志文件')
      return
    }
    const file = files.value.find((f) => f.name === selectedFile.value)
    if (!file) return

    if (refreshingFile.value === file.name) {
      // 当前文件已在刷新 —— 暂停 / 继续
      if (paused.value) {
        resumeRefresh()
      } else {
        pauseRefresh()
      }
    } else {
      // 启动刷新
      startRefresh(file)
    }
  }

  // ===== 下载 =====
  async function handleDownload() {
    if (!selectedFile.value) {
      ElMessage.warning('请先选择日志文件')
      return
    }
    try {
      await downloadLogFileSys(selectedFile.value)
      ElMessage.success('已生成下载,浏览器应自动开始下载')
    } catch (e) {
      ElMessage.error((e as Error).message || '下载失败')
    }
  }

  // ===== 右键菜单(2026-07-02 加) =====
  interface ContextMenuState {
    visible: boolean
    x: number
    y: number
    file: ImawxLogFile | null
  }
  const contextMenu = ref<ContextMenuState>({ visible: false, x: 0, y: 0, file: null })

  function openContextMenu(event: MouseEvent, file: ImawxLogFile) {
    contextMenu.value = {
      visible: true,
      x: event.clientX,
      y: event.clientY,
      file
    }
  }

  function closeContextMenu() {
    contextMenu.value.visible = false
  }

  function handleDownloadFromMenu() {
    const f = contextMenu.value.file
    closeContextMenu()
    if (!f) return
    downloadLogFileSys(f.name)
      .then(() => ElMessage.success('已开始下载'))
      .catch((e) => ElMessage.error((e as Error).message || '下载失败'))
  }

  function handleToggleRefreshFromMenu() {
    const f = contextMenu.value.file
    closeContextMenu()
    if (!f) return
    if (refreshingFile.value === f.name) {
      if (paused.value) {
        resumeRefresh()
      } else {
        pauseRefresh()
      }
    } else {
      startRefresh(f)
    }
  }

  // ===== 生命周期:离开页面关 WebSocket,进入页面自动开 =====
  onMounted(async () => {
    document.addEventListener('click', closeContextMenu)
    document.addEventListener('contextmenu', (e) => {
      const target = e.target as HTMLElement
      if (!target.closest('.imawx-log-file__file-item')) {
        closeContextMenu()
      }
    })
    await loadFiles()
    await reloadInitial()
  })
  onBeforeUnmount(() => {
    closeLogWs()
    document.removeEventListener('click', closeContextMenu)
  })
</script>

<style lang="scss" scoped>
  // ===== 整体左右分栏 =====
  .imawx-log-file {
    display: flex;
    flex-direction: row;
    gap: 12px;
    width: 100%;
    height: 100%;
    min-height: 0;
  }

  // ===== 左侧 sidebar(文件列表) =====
  .imawx-log-file__sidebar {
    width: 280px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    min-height: 0;

    :deep(.el-card__body) {
      flex: 1;
      min-height: 0;
      padding: 8px;
      display: flex;
      flex-direction: column;
    }
  }

  .imawx-log-file__sidebar-loading {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px 0;
    color: var(--el-text-color-secondary);
  }

  .imawx-log-file__sidebar-scroll {
    flex: 1;
    min-height: 0;
  }

  .imawx-log-file__group {
    margin-bottom: 8px;

    &:last-child {
      margin-bottom: 0;
    }
  }

  .imawx-log-file__file-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .imawx-log-file__file-item {
    position: relative;
    padding: 8px 10px;
    border-radius: 6px;
    cursor: pointer;
    transition: background-color 0.15s ease;
    margin-bottom: 2px;

    &:hover {
      background-color: var(--el-fill-color-light);
    }

    &.is-active {
      background-color: var(--el-color-primary-light-9);
      color: var(--el-color-primary);

      .imawx-log-file__file-meta {
        color: var(--el-color-primary-light-3);
      }
    }

    &.is-refreshing {
      // 正在实时刷中的文件,左边一条 2px 警告色竖条
      &::before {
        content: '';
        position: absolute;
        left: 0;
        top: 6px;
        bottom: 6px;
        width: 2px;
        background: var(--el-color-warning);
        border-radius: 2px;
      }
    }
  }

  .imawx-log-file__file-name {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    min-width: 0;
  }

  .imawx-log-file__file-meta {
    display: flex;
    justify-content: space-between;
    margin-top: 2px;
    margin-left: 22px;
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }

  .imawx-log-file__file-refreshing-tag {
    position: absolute;
    top: 6px;
    right: 6px;
  }

  // ===== 右侧 main(meta + 内容区) =====
  .imawx-log-file__main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  // 极简 meta 行(2026-07-02 改:删"尾部行数" input + "自动刷新" checkbox,只剩 meta + 状态 + 操作按钮)
  .imawx-log-file__toolbar {
    flex-shrink: 0;

    :deep(.el-card__body) {
      padding: 10px 16px;
    }
  }

  // ===== 日志内容区(保留原 textarea) =====
  .imawx-log-file__content {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;

    :deep(.el-card__body) {
      flex: 1;
      min-height: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
    }
  }

  .imawx-log-file__viewer {
    flex: 1;
    min-height: 0;
    padding: 12px;
  }

  // 暗色 textarea(保留原样)
  .imawx-log-file__textarea {
    height: 100%;
    display: flex;

    :deep(.el-textarea) {
      flex: 1;
      height: 100%;
      min-height: 100%;
      display: flex;
    }

    :deep(textarea.el-textarea__inner) {
      flex: 1;
      height: 100% !important;
      min-height: 100%;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
      font-size: 12px;
      line-height: 1.5;
      color: #d1d5db;
      background-color: #111827;
      white-space: pre;
      overflow-x: auto;
    }
  }
</style>

<style lang="scss">
  // 右键菜单全局样式(teleport 到 body,scoped 命中不到)
  .imawx-log-file__ctx-menu {
    position: fixed;
    z-index: 3000;
    margin: 0;
    padding: 4px 0;
    list-style: none;
    min-width: 160px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: 6px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    overflow: hidden;
  }

  .imawx-log-file__ctx-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 14px;
    font-size: 13px;
    color: var(--el-text-color-regular);
    cursor: pointer;
    transition: background-color 0.1s ease;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--el-color-primary);
    }

    &.is-active {
      color: var(--el-color-warning);
    }

    svg {
      font-size: 16px;
    }
  }
</style>

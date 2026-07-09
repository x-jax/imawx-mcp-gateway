<!--
  admin "/system/user" 用户列表行的"TOTP 重置"弹窗(2026-07-04 改名 / 简化)

  设计原则:
  - 用户 secret 在 create() 时自动生成,这里没有"首次启用"流程
  - 这个弹窗**只剩**一个场景:用户丢 Authenticator App,admin 帮用户重置密钥
  - 重置 = 覆盖旧 secret + 清 verified_at(下次 login 强制 verify,verify 通过即重新启用)

  步骤:
  1) 进入即调 resetUserTotp(id) → 拿到新 secret + otpauth URI
  2) admin 把 QR / Secret 转给用户(线下 IM / 邮件)
  3) 用户重新扫码 → 下次登录 verify 通过 → 重新启用 2FA

  无"关闭"按钮 —— TOTP 是系统级强制,配置表管总开关,UI 不能单独关闭某个用户的 TOTP
-->
<template>
  <ElDialog
    v-model="dialogVisible"
    title="重置 TOTP 密钥"
    width="520px"
    align-center
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <div v-if="loading" v-loading="true" class="totp-state-block" />

    <div v-else class="totp-state-block">
      <p class="state-desc">
        用户: <strong>{{ status?.username || `#${userId}` }}</strong>
        —— 当前 TOTP 状态:
        <ElTag :type="status?.enabled ? 'success' : 'info'" size="small">
          {{ status?.enabled ? `已启用 (自 ${formatTime(status?.verifiedAt)})` : '未启用' }}
        </ElTag>
      </p>

      <ElAlert
        v-if="resetData"
        type="warning"
        :closable="false"
        show-icon
        title="重置密钥 — 仅展示一次"
        description="旧的 secret 已失效,verified_at 已清空。用户需要用下方新密钥重新配 Authenticator,下次登录 verify 通过即重新启用。丢失请再次重置。"
      />

      <template v-if="resetData">
        <p class="state-desc">用 Google Authenticator / 1Password 等扫码下方二维码:</p>
        <div class="qr-wrap">
          <QrcodeVue
            :value="resetData.otpauthUri"
            :size="200"
            level="M"
          />
        </div>
        <p class="secret-text">
          或手动输入 Secret: <code>{{ resetData.secret }}</code>
          <ElButton link size="small" @click="copySecret">复制</ElButton>
        </p>
      </template>

      <ElSpace>
        <ElButton v-if="!resetData" type="primary" :loading="resetting" @click="handleReset">
          重置密钥
        </ElButton>
        <ElButton @click="closeAndRefresh">我已转给用户</ElButton>
      </ElSpace>
    </div>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage, type FormInstance } from 'element-plus'
  import QrcodeVue from 'qrcode.vue'
  import {
    getUserTotpStatus,
    resetUserTotp,
    type ImawxTotpSetup,
    type ImawxTotpStatus
  } from '@/api/sys/account'

  interface Props {
    visible: boolean
    userId: string | number
  }
  interface Emits {
    (e: 'update:visible', val: boolean): void
    (e: 'updated'): void
  }
  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (v) => emit('update:visible', v)
  })

  const loading = ref(false)
  const status = ref<ImawxTotpStatus & { username?: string } | null>(null)
  const resetData = ref<ImawxTotpSetup | null>(null)
  const resetting = ref(false)

  // 后端当前不返 username 给 toptStatus,这里只显示 userId;够用了(admin 已经知道是给谁重置)
  const _formRef = ref<FormInstance>()

  const loadStatus = async () => {
    loading.value = true
    try {
      status.value = await getUserTotpStatus(props.userId)
    } catch (e: any) {
      ElMessage.error(e?.message || '加载 TOTP 状态失败')
    } finally {
      loading.value = false
    }
  }

  watch(
    () => [props.visible, props.userId],
    ([vis]) => {
      if (vis) {
        resetData.value = null
        loadStatus()
      }
    },
    { immediate: true }
  )

  const handleReset = async () => {
    resetting.value = true
    try {
      resetData.value = await resetUserTotp(props.userId)
      ElMessage.success('已生成新密钥(仅展示一次)')
    } catch (e: any) {
      ElMessage.error(e?.message || '重置失败')
    } finally {
      resetting.value = false
    }
  }

  const copySecret = async () => {
    if (!resetData.value?.secret) return
    try {
      await navigator.clipboard.writeText(resetData.value.secret)
      ElMessage.success('Secret 已复制')
    } catch {
      ElMessage.error('复制失败,请手动选择')
    }
  }

  const closeAndRefresh = () => {
    dialogVisible.value = false
    emit('updated')
  }

  const handleClosed = () => {
    resetData.value = null
  }

  function formatTime(iso?: string): string {
    if (!iso) return '—'
    return iso.replace('T', ' ').slice(0, 19)
  }
</script>

<style scoped>
  .totp-state-block {
    min-height: 160px;
    padding: 8px 0;
  }
  .state-desc {
    margin-bottom: 14px;
    line-height: 1.6;
    color: var(--el-text-color-regular);
  }
  .qr-wrap {
    display: flex;
    justify-content: center;
    padding: 12px 0;
  }
  .secret-text {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 8px 0 14px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .secret-text code {
    padding: 2px 6px;
    background: var(--el-fill-color-light);
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, monospace;
  }
</style>

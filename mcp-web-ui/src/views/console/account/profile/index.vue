<!--
  imawx-mcp 个人信息页(2026-07-04 砍 TOTP 区)

  对应 prd.md 第 7 章 7.1 /account/profile 路由。

  只读展示当前账号的基础信息（来自 /api/sys/auth/me）。
  修改显示名 / 邮箱 / 头像等编辑功能超出 P1 范围，本期不做。

  2026-07-04 改:删除 TOTP 状态区 + "重置 TOTP" / "关闭 TOTP" 按钮。
  理由:TOTP 由配置表(mcp.auth.totp-enabled)管总开关,密钥在 admin create 用户时
  自动生成,个人无权干预。"个人中心"不再含任何 TOTP 操作入口。
-->
<template>
  <div class="imawx-account-profile">
    <header class="imawx-account-profile__header">
      <h2>个人信息</h2>
      <p class="imawx-account-profile__hint"
        >只读展示当前账号的基础信息。修改显示名 / 邮箱等功能本期未实现。</p
      >
    </header>

    <ElCard v-loading="loading" shadow="never" class="imawx-account-profile__card">
      <ElDescriptions v-if="info" :column="2" border size="default">
        <ElDescriptionsItem label="账号 ID">#{{ info.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="用户名">{{ info.username }}</ElDescriptionsItem>
        <ElDescriptionsItem label="显示名">{{ info.displayName || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="邮箱">{{ info.email || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="info.status === 1 ? 'success' : 'danger'" size="small">
            {{ info.status === 1 ? '启用' : '禁用' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ formatTime(info.createdAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最近登录" :span="2">{{
          formatTime(info.lastLoginAt)
        }}</ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard shadow="never" class="imawx-account-profile__card">
      <template #header><b>关联页面</b></template>
      <ElSpace wrap>
        <ElButton @click="$router.push({ name: 'ImawxAccountPassword' })">修改密码</ElButton>
        <ElButton type="primary" @click="$router.push({ name: 'ImawxAccountTokens' })">
          我的 MCP Token
        </ElButton>
      </ElSpace>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { fetchMeSys, type ImawxAccountInfo } from '@/api/sys/auth'

  defineOptions({ name: 'ImawxAccountProfile' })

  const info = ref<ImawxAccountInfo | null>(null)
  const loading = ref(false)

  async function reload() {
    loading.value = true
    try {
      info.value = await fetchMeSys()
    } catch {
      ElMessage.error('加载账号信息失败')
    } finally {
      loading.value = false
    }
  }

  function formatTime(iso?: string): string {
    if (!iso) return '—'
    return iso.replace('T', ' ').slice(0, 19)
  }

  onMounted(() => {
    reload()
  })
</script>

<style scoped lang="scss">
  .imawx-account-profile {
    &__header {
      margin-bottom: 16px;

      h2 {
        margin: 0 0 4px;
        font-size: 20px;
        font-weight: 600;
      }
    }

    &__hint {
      margin: 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }

    &__card {
      margin-bottom: 16px;
      max-width: 800px;
    }
  }
</style>

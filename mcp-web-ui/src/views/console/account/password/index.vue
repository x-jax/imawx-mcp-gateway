<!--
  imawx-mcp 修改密码页

  对应 prd.md 第 7 章 7.1 /account/password 路由 + 第 6 章 6.1
  PUT /api/sys/auth/me/password 接口。

  - 必填：旧密码 + 新密码 + 确认新密码
  - 前端校验：两次新密码一致
  - 后端校验：旧密码 BCrypt 比对
  - 成功 → 提示用户重新登录（清 Session）
-->
<template>
  <div class="imawx-account-password">
    <header class="imawx-account-password__header">
      <h2>修改密码</h2>
      <p class="imawx-account-password__hint">修改成功后会自动登出，请用新密码重新登录。</p>
    </header>

    <ElCard shadow="never" class="imawx-account-password__card">
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
        label-position="right"
        style="max-width: 480px"
        @submit.prevent
      >
        <ElFormItem label="当前密码" prop="oldPassword">
          <ElInput
            v-model.trim="form.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入当前密码"
          />
        </ElFormItem>

        <ElFormItem label="新密码" prop="newPassword">
          <ElInput
            v-model.trim="form.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </ElFormItem>

        <ElFormItem label="确认新密码" prop="confirmPassword">
          <ElInput
            v-model.trim="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再输入一次"
          />
        </ElFormItem>

        <ElFormItem>
          <ElButton type="primary" :loading="saving" @click="handleSubmit">修改密码</ElButton>
          <ElButton :disabled="saving" @click="resetForm">重置</ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { useRouter } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'
  import { changeOwnPassword } from '@/api/sys/account'

  defineOptions({ name: 'ImawxAccountPassword' })

  const router = useRouter()
  const userStore = useUserStore()

  const formRef = ref<FormInstance>()
  const saving = ref(false)

  const form = reactive({
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  })

  const rules: FormRules = {
    oldPassword: [{ required: true, message: '当前密码必填', trigger: 'blur' }],
    newPassword: [
      { required: true, message: '新密码必填', trigger: 'blur' },
      { min: 8, message: '至少 8 位', trigger: 'blur' },
      {
        validator: (_rule, value, callback) => {
          if (value && value === form.oldPassword) {
            return callback(new Error('新密码不能与旧密码相同'))
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    confirmPassword: [
      { required: true, message: '请确认新密码', trigger: 'blur' },
      {
        validator: (_rule, value, callback) => {
          if (value !== form.newPassword) {
            return callback(new Error('两次输入不一致'))
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
  }

  async function handleSubmit() {
    if (!formRef.value) return
    try {
      await formRef.value.validate()
    } catch {
      return
    }
    saving.value = true
    try {
      await changeOwnPassword(form.oldPassword, form.newPassword)
      ElMessage.success('密码修改成功，即将登出')
      // 等用户看到提示再清 Session + 跳登录页
      setTimeout(async () => {
        await userStore.logOut()
        router.push('/login')
      }, 1000)
    } catch {
      // 错误已弹
    } finally {
      saving.value = false
    }
  }

  function resetForm() {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    nextTick(() => formRef.value?.clearValidate())
  }
</script>

<style scoped lang="scss">
  .imawx-account-password {
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
      max-width: 560px;
    }
  }
</style>

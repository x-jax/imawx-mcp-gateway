<!--
  admin "/system/user" 行的"新增 / 编辑用户"弹窗(2026-07-04 改:创建成功展示 TOTP 密钥)

  2026-07-04 改:**新增用户成功 → 不立刻关弹窗**,而是切到"密钥展示"面板。
  原因:TOTP secret 在 create 时自动生成,本次响应一次性明文返给 admin。
  必须确保 admin 真的看到了密钥 / 二维码,才能关闭。
  关闭后密钥不再展示,丢了找 admin 重置(@see user-totp-dialog.vue)。
-->
<template>
  <ElDialog
    v-model="dialogVisible"
    :title="dialogTitle"
    width="560px"
    align-center
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <!-- 步骤 1:表单(add / edit 共用) -->
    <ElForm
      v-if="step === 'form'"
      ref="formRef"
      :model="formData"
      :rules="rules"
      label-width="90px"
    >
      <ElFormItem v-if="dialogType === 'add'" label="用户名" prop="username">
        <ElInput
          v-model="formData.username"
          placeholder="3-32 位,字母开头,允许字母/数字/下划线"
          maxlength="32"
        />
      </ElFormItem>
      <ElFormItem v-else label="用户名" prop="username">
        <ElInput v-model="formData.username" disabled />
        <span class="form-tip">用户名创建后不可修改</span>
      </ElFormItem>

      <ElFormItem v-if="dialogType === 'add'" label="邮箱" prop="email">
        <ElInput v-model="formData.email" placeholder="登录用邮箱(必填,唯一)" />
      </ElFormItem>
      <ElFormItem v-else label="邮箱" prop="email">
        <ElInput v-model="formData.email" />
      </ElFormItem>

      <ElFormItem label="显示名" prop="displayName">
        <ElInput v-model="formData.displayName" placeholder="展示用名称" />
      </ElFormItem>

      <ElFormItem v-if="dialogType === 'add'" label="初始密码" prop="initialPassword">
        <ElInput v-model="formData.initialPassword" type="password" show-password placeholder="至少 8 位" />
      </ElFormItem>

      <ElFormItem v-if="dialogType === 'edit' && !targetIsAdmin" label="状态" prop="status">
        <ElRadioGroup v-model="formData.status">
          <ElRadio :value="1">启用</ElRadio>
          <ElRadio :value="0">禁用</ElRadio>
        </ElRadioGroup>
        <span class="form-tip">禁用后该账号无法登录,关联 token 立即失效</span>
      </ElFormItem>
      <ElFormItem v-else-if="dialogType === 'edit' && targetIsAdmin" label="状态">
        <ElTag type="success">管理员账号不允许被禁用</ElTag>
      </ElFormItem>
    </ElForm>

    <!-- 步骤 2:创建成功 → 展示 TOTP 密钥(只展示一次) -->
    <div v-else-if="step === 'created' && createdData" class="created-block">
      <ElAlert
        type="success"
        :closable="false"
        show-icon
        title="用户创建成功"
        :description="`账号 #${createdData.id} (${createdData.username}) 已创建,TOTP 密钥已自动生成。`"
      />

      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        title="密钥仅展示一次"
        description="关闭弹窗后密钥不再展示。请立刻把二维码 / 密钥转给用户,让用户用 Authenticator 扫码。丢失可在 /system/user 用户行点 [重置 TOTP] 重发。"
      />

      <div class="created-qr">
        <QrcodeVue :value="createdData.totpOtpauthUri" :size="200" level="M" />
      </div>

      <div class="created-secret">
        <span class="created-secret-label">Secret:</span>
        <code class="created-secret-value">{{ createdData.totpSecret }}</code>
        <ElButton link size="small" @click="copySecret">复制</ElButton>
      </div>

      <p class="created-hint">
        用户首次登录需要输入 Authenticator 上的 6 位验证码,verify 通过后 2FA 正式启用。
        关掉本弹窗前请确认用户已经拿到密钥。
      </p>
    </div>

    <template #footer>
      <!-- 表单步骤:取消 / 提交 -->
      <template v-if="step === 'form'">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">提交</ElButton>
      </template>
      <!-- 创建成功:我已转给用户 -->
      <template v-else-if="step === 'created'">
        <ElButton type="primary" @click="closeAndRefresh">我已转给用户,关闭</ElButton>
      </template>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import QrcodeVue from 'qrcode.vue'
  import {
    createUser,
    updateUser,
    type ImawxUserCreatePayload,
    type ImawxUserCreated,
    type ImawxUserListItem,
    type ImawxUserUpdatePayload
  } from '@/api/sys/account'

  type DialogType = 'add' | 'edit'
  type Step = 'form' | 'created'

  interface Props {
    visible: boolean
    type: DialogType
    userData?: Partial<ImawxUserListItem>
  }
  interface Emits {
    (e: 'update:visible', val: boolean): void
    (e: 'submit'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    userData: () => ({})
  })
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (val) => emit('update:visible', val)
  })
  const dialogType = computed<DialogType>(() => props.type)
  const targetIsAdmin = computed(() => Boolean(props.userData?.isAdmin))

  const dialogTitle = computed(() => {
    if (step.value === 'created') return '用户已创建 — 转交 TOTP 密钥'
    return dialogType.value === 'add' ? '新增用户' : '编辑用户'
  })

  const submitting = ref(false)
  const formRef = ref<FormInstance>()

  // 步骤切换:表单 → 创建成功展示
  const step = ref<Step>('form')
  const createdData = ref<ImawxUserCreated | null>(null)

  const formData = reactive<{
    username: string
    email: string
    displayName: string
    initialPassword: string
    status: number
  }>({
    username: '',
    email: '',
    displayName: '',
    initialPassword: '',
    status: 1
  })

  const rules: FormRules = {
    username: [
      { required: true, message: '用户名不能为空', trigger: 'blur' },
      { min: 3, max: 32, message: '长度 3-32 位', trigger: 'blur' },
      {
        pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
        message: '只能包含字母、数字、下划线,且必须以字母开头',
        trigger: 'blur'
      }
    ],
    email: [
      { required: true, message: '邮箱不能为空', trigger: 'blur' },
      { type: 'email', message: '邮箱格式不合法', trigger: 'blur' }
    ],
    displayName: [{ required: true, message: '显示名不能为空', trigger: 'blur' }],
    initialPassword: [
      { required: true, message: '初始密码不能为空', trigger: 'blur' },
      { min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' }
    ]
  }

  watch(
    () => [props.visible, props.type, props.userData],
    ([vis, type, u]) => {
      if (!vis) return
      if (type === 'add') {
        step.value = 'form'
        createdData.value = null
        formData.username = ''
        formData.email = ''
        formData.displayName = ''
        formData.initialPassword = ''
        formData.status = 1
      } else {
        step.value = 'form'
        createdData.value = null
        formData.username = (u as ImawxUserListItem).username || ''
        formData.email = (u as ImawxUserListItem).email || ''
        formData.displayName = (u as ImawxUserListItem).displayName || ''
        formData.status = (u as ImawxUserListItem).status ?? 1
      }
      nextTick(() => formRef.value?.clearValidate())
    },
    { immediate: true, deep: true }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return
    await formRef.value.validate(async (valid) => {
      if (!valid) return
      submitting.value = true
      try {
        if (dialogType.value === 'add') {
          const payload: ImawxUserCreatePayload = {
            username: formData.username,
            email: formData.email,
            displayName: formData.displayName,
            initialPassword: formData.initialPassword,
            status: formData.status
          }
          // 2026-07-04 改:响应一次性返 totpSecret + otpauthUri → 切到 'created' 步骤
          createdData.value = await createUser(payload)
          step.value = 'created'
        } else {
          const target = props.userData as ImawxUserListItem
          const payload: ImawxUserUpdatePayload = {
            displayName: formData.displayName,
            email: formData.email,
            status: targetIsAdmin.value ? undefined : formData.status
          }
          await updateUser(target.id, payload)
          ElMessage.success('编辑成功')
          emit('submit')
          dialogVisible.value = false
        }
      } catch (e: any) {
        ElMessage.error(e?.message || '提交失败')
      } finally {
        submitting.value = false
      }
    })
  }

  const copySecret = async () => {
    if (!createdData.value?.totpSecret) return
    try {
      await navigator.clipboard.writeText(createdData.value.totpSecret)
      ElMessage.success('Secret 已复制')
    } catch {
      ElMessage.error('复制失败,请手动选择')
    }
  }

  const closeAndRefresh = () => {
    emit('submit')
    dialogVisible.value = false
  }

  const handleClosed = () => {
    // 关闭后重置 step,下次打开就回到 form
    step.value = 'form'
    createdData.value = null
  }
</script>

<style scoped>
  .form-tip {
    margin-left: 12px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .created-block {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .created-qr {
    display: flex;
    justify-content: center;
    padding: 12px 0;
  }
  .created-secret {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }
  .created-secret-label {
    flex-shrink: 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .created-secret-value {
    flex: 1;
    overflow-x: auto;
    padding: 2px 6px;
    background: var(--el-bg-color);
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, monospace;
    font-size: 13px;
    letter-spacing: 0.5px;
  }
  .created-hint {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
</style>

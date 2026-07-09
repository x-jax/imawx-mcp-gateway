<!--
  imawx-mcp 登录页(2026-07-04 简化)——
  只收账号+密码,TOTP / Gitee 流程都删了:
  - TOTP:绑过的用户后端报"请输入 6 位 TOTP 验证码"时,前端弹 ElPrompt 收 6 位码重试
  - Gitee OAuth:从模板继承的旧入口,没接入,删了
  - 删 login-methods 接口 + 前端 methods 块(冗余配置,后端按需自决)
-->
<template>
  <div class="login-page">
    <svg class="bubble-bg" viewBox="0 0 1440 900" aria-hidden="true">
      <defs>
        <linearGradient id="bubbleBlue" x1="0" x2="1" y1="0" y2="1">
          <stop stop-color="var(--main-color)" stop-opacity="0.28" />
          <stop offset="1" stop-color="#38c0fc" stop-opacity="0.08" />
        </linearGradient>
        <linearGradient id="bubblePink" x1="0" x2="1" y1="0" y2="1">
          <stop stop-color="#ff80c8" stop-opacity="0.2" />
          <stop offset="1" stop-color="#ffffff" stop-opacity="0.06" />
        </linearGradient>
      </defs>
      <circle class="bubble bubble-one" cx="124" cy="164" r="72" fill="url(#bubbleBlue)" />
      <circle class="bubble bubble-two" cx="1270" cy="128" r="116" fill="url(#bubblePink)" />
      <circle class="bubble bubble-three" cx="1128" cy="720" r="164" fill="url(#bubbleBlue)" />
      <circle class="bubble bubble-four" cx="278" cy="744" r="128" fill="url(#bubblePink)" />
      <circle class="bubble bubble-five" cx="718" cy="96" r="44" fill="url(#bubbleBlue)" />
      <circle class="bubble bubble-six" cx="610" cy="808" r="56" fill="url(#bubblePink)" />
    </svg>

    <div class="login-shell">
      <div class="theme-controls">
        <button class="btn palette-btn" type="button" title="切换主题色" @click.stop="toggleColorPicker">
          <ArtSvgIcon icon="ri:palette-line" />
        </button>
        <div v-show="showColorPicker" class="color-dots" @click.stop>
          <button
            v-for="(color, index) in mainColors"
            :key="color"
            type="button"
            class="color-dot"
            :class="{ active: color === systemThemeColor }"
            :style="{ background: color, '--index': index }"
            :title="`主题色 ${index + 1}`"
            @click.stop="changeThemeColor(color)"
          >
            <ArtSvgIcon v-if="color === systemThemeColor" icon="ri:check-fill" class="text-white" />
          </button>
        </div>
        <button class="btn theme-btn" type="button" :title="isDark ? '切换亮色模式' : '切换暗色模式'" @click="toggleDark">
          <ArtSvgIcon :icon="isDark ? 'ri:sun-fill' : 'ri:moon-line'" />
        </button>
      </div>

      <section class="visual-panel">
        <div class="brand">
          <ArtLogo size="44" />
          <span>{{ systemName }}</span>
        </div>
        <div class="visual-bg-wrapper">
          <ThemeSvg :src="loginIcon" class="visual-bg-inner" />
        </div>
      </section>

      <section class="form-panel">
        <div class="form-card">
          <div class="form-heading">
            <h1>{{ systemName }}</h1>
            <p>请输入账号信息进入管理后台</p>
          </div>

          <ElForm
            ref="formRef"
            :model="formData"
            :rules="rules"
            :validate-on-rule-change="false"
            class="login-form"
            @keyup.enter="handleSubmit"
          >
            <ElFormItem prop="account">
              <ElInput
                v-model.trim="formData.account"
                placeholder="请输入邮箱"
                :disabled="locked"
              >
                <template #prefix>
                  <ArtSvgIcon icon="ri:mail-line" />
                </template>
              </ElInput>
            </ElFormItem>

            <ElFormItem prop="password">
              <ElInput
                v-model.trim="formData.password"
                type="password"
                autocomplete="off"
                show-password
                placeholder="请输入密码"
                :disabled="locked"
              >
                <template #prefix>
                  <ArtSvgIcon icon="ri:lock-password-line" />
                </template>
              </ElInput>
            </ElFormItem>

            <!--
              2026-07-04 改:TOTP 输入框永远显示 —— 后端按需校验
                - 全局 TOTP 关:后端跳过校验,前端输入也不影响
                - 全局 TOTP 开 + 用户已配:必填 6 位 TOTP(或 backup code)
                - 全局 TOTP 开 + 用户没配 secret:返 40103 提示联系 admin
            -->
            <ElFormItem prop="totpCode">
              <ElInput
                v-model.trim="formData.totpCode"
                placeholder="二次验证码(全局 2FA 开启时必填)"
                maxlength="11"
                :disabled="locked"
                class="totp-input"
              >
                <template #prefix>
                  <ArtSvgIcon icon="ri:shield-check-line" />
                </template>
              </ElInput>
            </ElFormItem>

            <div class="form-extra">
              <ElCheckbox v-model="formData.rememberAccount">记住邮箱</ElCheckbox>
            </div>

            <ElButton
              class="login-button"
              type="primary"
              :loading="loading"
              :disabled="locked"
              @click="handleSubmit"
              v-ripple
            >
              {{ locked ? `${countdown}s 后重试` : '登录' }}
            </ElButton>

            <div v-if="errorMsg" class="form-error">{{ errorMsg }}</div>
          </ElForm>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
  import AppConfig from '@/config'
  import { useUserStore } from '@/store/modules/user'
  import { useSettingStore } from '@/store/modules/setting'
  import { useTheme } from '@/hooks/core/useTheme'
  import { storeToRefs } from 'pinia'
  import {
    fetchLoginSys,
    type ImawxAccountInfo
  } from '@/api/sys/auth'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { SystemThemeEnum } from '@/enums/appEnum'
  import loginIcon from '@imgs/svg/login_icon.svg'

  defineOptions({ name: 'ImawxLogin' })

  const router = useRouter()
  const route = useRoute()
  const userStore = useUserStore()
  const settingStore = useSettingStore()
  const { switchThemeStyles } = useTheme()
  const { isDark, systemThemeColor } = storeToRefs(settingStore)

  const systemName = computed(() => AppConfig.systemInfo.name)
  const mainColors = AppConfig.systemMainColor

  // 主题控件(2026-07-04 加回:用户既有设计,之前"简化"时不该删)
  const showColorPicker = ref(false)
  function toggleColorPicker() { showColorPicker.value = !showColorPicker.value }
  function closeColorPicker() { showColorPicker.value = false }
  function changeThemeColor(color: string) {
    showColorPicker.value = false
    if (systemThemeColor.value !== color) {
      settingStore.setElementTheme(color)
      settingStore.reload()
    }
  }
  function toggleDark() {
    const next = isDark.value ? SystemThemeEnum.LIGHT : SystemThemeEnum.DARK
    switchThemeStyles(next)
  }

  // 表单
  const formRef = ref<FormInstance>()
  const formData = reactive({
    account: localStorage.getItem('imawx:lastAccount') ?? '',
    password: '',
    totpCode: '',
    rememberAccount: !!localStorage.getItem('imawx:lastAccount')
  })
  const rules = computed<FormRules>(() => ({
    account: [{ required: true, message: '请输入邮箱', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
    // totpCode 不强校验 —— 后端按需(全局 TOTP 开关 + 用户 secret 状态)决定要不要
  }))

  // 5 秒防爆破
  const LOCK_DURATION_SEC = 5
  const locked = ref(false)
  const countdown = ref(0)
  const loading = ref(false)
  const errorMsg = ref('')

  function startLockCountdown() {
    locked.value = true
    countdown.value = LOCK_DURATION_SEC
    const timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) {
        clearInterval(timer)
        locked.value = false
        countdown.value = 0
      }
    }, 1000)
  }

  /**
   * 登录主流程(2026-07-04 重做)——
   * 表单三字段一起发(account + password + TOTP code),后端按需校验:
   *   - 全局 TOTP 关:跳过 TOTP 校验
   *   - 全局 TOTP 开 + 用户 secret 缺失 → 40103 "请联系管理员初始化两步验证"
   *   - 全局 TOTP 开 + 用户已配 secret → 必填 TOTP code
   *     - 首次 verify(verified_at==null)通过后置 verified_at
   *     - 已 verify 正常校验
   * 错误统一在 errorMsg 显示,密码错启动 5 秒防爆破。
   */
  async function tryLogin(account: string, password: string, totpCode: string) {
    return fetchLoginSys({ account, password, totpCode: totpCode || undefined })
  }

  function persistAccount(account: string) {
    if (formData.rememberAccount) {
      localStorage.setItem('imawx:lastAccount', account)
    } else {
      localStorage.removeItem('imawx:lastAccount')
    }
  }

  function resolveRedirectPath(): string {
    const redirect = route.query.redirect as string | undefined
    if (!redirect || redirect === '/login' || redirect.startsWith('/login?')) {
      return '/dashboard'
    }
    return redirect
  }

  async function enterApp(me: ImawxAccountInfo, options: { showWelcome: boolean }) {
    userStore.setUserInfo(me as any)
    userStore.setLoginStatus(true)
    if (options.showWelcome) {
      ElMessage.success(`欢迎回来,${me.displayName ?? me.username}`)
    }
    if (me.mustChangePassword) {
      await router.replace('/account/password')
      return
    }
    await router.replace(resolveRedirectPath())
  }

  async function onLoginSuccess(me: ImawxAccountInfo) {
    persistAccount(me.email ?? me.username ?? formData.account)
    await enterApp(me, { showWelcome: true })
  }

  async function restoreExistingSession() {
    if (userStore.isLogin) {
      await router.replace(resolveRedirectPath())
      return
    }
    const baseURL = import.meta.env.VITE_API_URL || ''
    const response = await fetch(`${baseURL}/api/sys/auth/me`, {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' }
    })
    if (!response.ok) return
    const body = await response.json()
    if (body?.code === 200 && body.data) {
      await enterApp(body.data, { showWelcome: false })
    }
  }

  function handleLoginError(e: any) {
    const code = e?.code
    const msg = e?.message ?? ''
    // 40101 / 40102 / 40103:后端业务错误,直接显示 msg
    if (code === 40101 || code === 40102 || code === 40103) {
      errorMsg.value = msg || '登录失败'
      // 密码错 启动 5 秒防爆破;其它错误也防爆破避免暴力试探 TOTP
      startLockCountdown()
      return
    }
    errorMsg.value = msg || '登录失败,请稍后重试'
  }

  async function handleSubmit() {
    if (!formRef.value) return
    if (locked.value || loading.value) return
    try {
      const valid = await formRef.value.validate()
      if (!valid) return
      errorMsg.value = ''
      loading.value = true
      const me = await tryLogin(formData.account, formData.password, formData.totpCode)
      await onLoginSuccess(me)
    } catch (e: any) {
      handleLoginError(e)
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    window.addEventListener('click', closeColorPicker)
    restoreExistingSession().catch(() => {
      // 登录页静默探测已有 session，失败时保持当前登录表单。
    })
  })
  onBeforeUnmount(() => window.removeEventListener('click', closeColorPicker))
</script>

<style scoped>
  @import './style.css';
</style>

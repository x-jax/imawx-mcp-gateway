/**
 * imawx-mcp 后台管理 API — 账号认证域(2026-07-02 简化,2026-07-04 删除个人中心 TOTP)
 *
 * <p>变化:
 * <ul>
 *   <li>登录用 {@code account}(邮箱/username 都行,@ 区分),不是 {@code username}</li>
 *   <li>登录 1 步: {@code account + password + totpCode?},后端按需校验 TOTP</li>
 *   <li><b>2026-07-04 砍</b>:个人中心 TOTP 端点全部删除 —— 个人不能"启用 / 重置 / 关闭" TOTP。
 *       配置表 {@code mcp.auth.totp-enabled} 管总开关,密钥在 admin create 用户时自动生成,
 *       个人无权干预。</li>
 * </ul>
 */
import request from '@/utils/http'

export interface ImawxAccountInfo {
  id: string
  username: string
  displayName?: string
  email?: string
  roles: string[]
  status: number
  mustChangePassword?: boolean
  lastLoginAt?: string
  createdAt?: string
}

/** 登录请求(2026-07-02 简化)—— 一次带 account + password + totpCode?。 */
export interface LoginSysParams {
  account: string
  password: string
  /** TOTP 6 位 code;启用了 2FA 必填,未启用留空 */
  totpCode?: string
}

export function fetchLoginSys(params: LoginSysParams) {
  return request.post<ImawxAccountInfo>({
    url: '/api/sys/auth/login',
    data: params,
    showSuccessMessage: true,
    showErrorMessage: false
  })
}

export function fetchLogoutSys() {
  return request.post<void>({
    url: '/api/sys/auth/logout'
  })
}

export function fetchMeSys() {
  return request.get<ImawxAccountInfo>({
    url: '/api/sys/auth/me'
  })
}

export function fetchChangePasswordSys(params: { oldPassword: string; newPassword: string }) {
  return request.put<void>({
    url: '/api/sys/auth/me/password',
    data: params,
    showSuccessMessage: true
  })
}

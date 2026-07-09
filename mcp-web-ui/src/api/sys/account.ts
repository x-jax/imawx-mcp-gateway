/**
 * 后端账号 / 用户管理 API 客户端(2026-07-03 加,2026-07-04 个人中心 TOTP 全砍)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@code /api/sys/users/**} —— admin 路径(列表 / 创建 / 编辑 / 重置密码 / TOTP 重置)</li>
 * </ul>
 *
 * <p>2026-07-04 后:
 * <ul>
 *   <li>{@code /api/sys/users/\{id\}/totp/setup} → {@code /totp/reset} 改名/改语义</li>
 *   <li>{@code /api/sys/users/\{id\}/totp/verify} 删除(userService 不再保留,login 自动 verify)</li>
 *   <li>{@code DELETE /api/sys/users/\{id\}/totp} 删除 —— 不允许 UI 关闭 TOTP</li>
 *   <li>创建用户返回扩展 VO:{@link ImawxUserCreated},含一次性明文 totpSecret + otpauth</li>
 *   <li>所有"个人" TOTP 端点删除</li>
 * </ul>
 */
import { request } from '@/utils/request'

// ===== 类型 =====

/** 用户列表 VO(后端 {@code McpUserListVO})。 */
export interface ImawxUserListItem {
  id: string
  username: string
  displayName: string
  email: string
  /** 1=启用 0=禁用 */
  status: number
  /** 是否系统管理员(写死 userId==1) */
  isAdmin: boolean
  /** 是否启用了 2FA(2026-07-04 = totp_verified_at != null) */
  totpEnabled: boolean
  lastLoginAt?: string
  createTime: string
  updateTime: string
}

/** 用户分页响应。 */
export interface ImawxUserPage {
  records: ImawxUserListItem[]
  total: number
  pageNum: number
  pageSize: number
}

/** 用户查询参数。 */
export interface ImawxUserQuery {
  keyword?: string
  status?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 创建用户 DTO。
 *
 * <p>2026-07-03 改:username 改为必填字段(原版本由后端自动生成)。
 * 约束:3-32 位 / 字母开头 / [A-Za-z0-9_]+,后端 DTO 上有 @Pattern 拦截。
 */
export interface ImawxUserCreatePayload {
  username: string
  email: string
  displayName: string
  initialPassword: string
  status?: number
}

/** 编辑用户 DTO。 */
export interface ImawxUserUpdatePayload {
  displayName?: string
  email?: string
  status?: number
}

/** TOTP 重置响应 —— 含 secret + otpauthUri(给前端渲染二维码)。 */
export interface ImawxTotpSetup {
  secret: string
  otpauthUri: string
}

/** TOTP 状态(2026-07-04 简化,无 backupCount)。 */
export interface ImawxTotpStatus {
  enabled: boolean
  verifiedAt?: string
}

/**
 * 创建用户响应(后端 {@code McpUserCreatedVO},2026-07-04 加)。
 *
 * <p><b>这是唯一一次能看到 totpSecret 的地方</b>,前端需要在成功提示里醒目的"复制"按钮 +
 * 二维码渲染,防止 admin 错过 / 截不到图就关掉弹窗。
 */
export interface ImawxUserCreated {
  id: string
  username: string
  displayName: string
  email: string
  status: number
  /** 一次性明文 TOTP secret(base32,32 字符)。 */
  totpSecret: string
  /** otpauth:// URI —— 用 qrcode 库渲染成二维码。 */
  totpOtpauthUri: string
}

// ===== /api/sys/users/**(admin 路径)=====

/** 用户列表(分页 + 搜索) */
export function fetchUserPage(params: ImawxUserQuery) {
  return request<ImawxUserPage>({
    url: '/api/sys/users',
    method: 'get',
    params
  })
}

/** 用户详情 */
export function fetchUserDetail(id: string | number) {
  return request<ImawxUserInfo>({
    url: `/api/sys/users/${id}`,
    method: 'get'
  })
}

/**
 * 创建用户 —— 2026-07-04 改:响应改用 {@link ImawxUserCreated} 包含 totpSecret + otpauth。
 *
 * <p>前端拿到响应后要<b>立即展示</b>密钥 + 二维码 + 复制按钮 → 关掉弹窗后不再展示。
 */
export function createUser(payload: ImawxUserCreatePayload) {
  return request<ImawxUserCreated>({
    url: '/api/sys/users',
    method: 'post',
    data: payload,
    showSuccessMessage: true
  })
}

/** 编辑用户 */
export function updateUser(id: string | number, payload: ImawxUserUpdatePayload) {
  return request<void>({
    url: `/api/sys/users/${id}`,
    method: 'put',
    data: payload
  })
}

/** 重置密码 */
export function resetUserPassword(id: string | number, newPassword: string) {
  return request<void>({
    url: `/api/sys/users/${id}/reset-password`,
    method: 'post',
    data: { newPassword }
  })
}

/**
 * 重置用户 TOTP 密钥(2026-07-04 改名)—— 丢 App 后运维恢复路径。
 *
 * <p>响应的 {@link ImawxTotpSetup.secret} 和 {@code otpauthUri} 只展示一次。
 */
export function resetUserTotp(id: string | number) {
  return request<ImawxTotpSetup>({
    url: `/api/sys/users/${id}/totp/reset`,
    method: 'post'
  })
}

/** 查 TOTP 状态 */
export function getUserTotpStatus(id: string | number) {
  return request<ImawxTotpStatus>({
    url: `/api/sys/users/${id}/totp`,
    method: 'get'
  })
}

// ===== /api/sys/auth/me/**(个人中心,个人无 TOTP)=====

/** 当前账号信息(后端 McpUserInfoVO) */
export interface ImawxUserInfo {
  id: string
  username: string
  displayName: string
  email: string
  status: number
  lastLoginAt?: string
  createdAt: string
  roles: string[]
  buttons: string[]
  mustChangePassword?: boolean
}

/** 改自己 displayName / email */
export function updateOwnProfile(payload: { displayName?: string; email?: string }) {
  return request<void>({
    url: '/api/sys/auth/me',
    method: 'put',
    data: payload
  })
}

/** 改自己密码 */
export function changeOwnPassword(oldPassword: string, newPassword: string) {
  return request<void>({
    url: '/api/sys/auth/me/password',
    method: 'put',
    data: { oldPassword, newPassword }
  })
}

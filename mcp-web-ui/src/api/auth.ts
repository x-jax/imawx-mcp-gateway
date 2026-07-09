import request from '@/utils/http'
import type { ImawxAccountInfo, LoginSysParams } from './sys/auth'

/**
 * 登录 — art-design-pro 模板登录页默认入口。
 *
 * <p>URL 已替换为本项目路径 {@code /api/sys/auth/login}（原模板为 {@code /api/auth/login}），
 * 响应形状对齐 {@link ImawxAccountInfo}（userId / username / roles / buttons / ...）。
 *
 * <p>实际调用同 {@link import('./sys/auth').fetchLoginSys}；本文件保留为
 * 模板默认导出名（{@code fetchLogin} / {@code fetchGetUserInfo}），避免改路由守卫 / 登录页。
 *
 * <p>imawx 后端只返 username（小写），art-design-pro 模板期望 userName / buttons
 * （UserInfo 形状），运行时由 {@code views/console/login/index.vue} 用 `as any`
 * 适配；这里用 {@code as any} 跳过类型校验（ambient namespace Api 不能 import）。
 */
export function fetchLogin(params: LoginSysParams): any {
  return request.post<ImawxAccountInfo>({
    url: '/api/sys/auth/login',
    data: params,
    showSuccessMessage: true,
    showErrorMessage: false
  })
}

/**
 * 获取当前账号信息 — 路由守卫 {@code router/guards/beforeEach.ts} 初始化调用。
 *
 * <p>URL {@code /api/sys/auth/me}（原模板为 {@code /api/user/info}），响应形状 {@link ImawxAccountInfo}。
 *
 * <p>兼容 UserInfo：路由守卫的 {@code userStore.setUserInfo(data)} 期望 UserInfo，
 * 实际后端只返 ImawxAccountInfo，用 {@code any} 抹平（ambient namespace 不能 import）。
 */
export function fetchGetUserInfo(): any {
  return request.get<ImawxAccountInfo>({
    url: '/api/sys/auth/me'
  })
}

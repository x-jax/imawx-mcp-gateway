/**
 * 公共路由别名
 * 存放系统级公共路由路径，如布局容器、登录页等
 *
 * imawx-mcp (2026-06-27): Login 别名 /auth/login → /login（对齐 prd 7.1）
 */
export enum RoutesAlias {
  Layout = '/index/index', // 布局容器
  Login = '/login' // 登录页
}

/**
 * imawx-mcp 业务路由模块（5 个 1 级菜单平铺版）
 *
 * <p>对应 prd.md 第 7 章 7.1 路由结构 + 第 6 章 6.1 后台管理 API。
 *
 * <p>侧边栏直接显示 5 个 1 级菜单项（仪表盘 / 外部 MCP 服务 / 关系数据库 /
 * 调用日志 / 系统配置），不再嵌套"控制台"父项。每个 1 级路由的 URL 路径就是菜单
 * 名直译（{@code /dashboard} / {@code /mcp-proxy} 等），点击进入。
 *
 * <p>菜单 vs 详情页关系：
 * <ul>
 *   <li>1-5 号菜单（仪表盘 / 外部 MCP 服务 / 关系数据库 / 调用日志 / 系统配置）
 *       都是叶子，RouteTransformer 会自动套 art-design-pro 的 Layout 容器</li>
 *   <li>mcp-proxy 详情页（{@code /:id}）通过 {@code isHide: true}
 *       隐藏在 sidebar，访问时通过 {@code activeMenu} 关联到父菜单高亮</li>
 *   <li>"账号"路由（{@code /account}）父级带 3 个子页面（个人信息 / 修改密码 /
 *       我的 MCP Token），2026-06-29 起 {@code isHide: true} 从侧边栏隐藏，
 *       改由右上角头像下拉里的"个人信息 / 修改密码 / 我的 MCP Token"三个入口进。
 *       路由本身保留，子页面仍可访问</li>
 * </ul>
 *
 * <p>鉴权：走 servlet HttpSession（Cookie 自动转发，axios withCredentials=true），
 * 路由守卫在 {@code src/router/guards/beforeEach.ts} 检查 userStore.isLogin。
 *
 * <p>所有菜单标题硬编码中文——本项目只面向国内运营，不需要 i18n。
 */
import type { AppRouteRecord } from '@/types/router'

/** imawx-mcp 后台角色集合：超管 / 管理员都能访问 */
const IMAWX_ROLES = ['R_SUPER', 'R_ADMIN']

export const imawxMcpRoutes: AppRouteRecord[] = [
  // 1. 仪表盘（叶子 1 级，RouteTransformer 自动套 layout）
  {
    path: '/dashboard',
    name: 'ImawxDashboard',
    component: '/console/dashboard/index',
    meta: {
      title: '仪表盘',
      icon: 'ri:pie-chart-2-line',
      fixedTab: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  // 2. MCP（外部 MCP 服务 / 叶子 1 级 + 隐藏详情）
  {
    path: '/mcp-proxy',
    name: 'ImawxMcpProxy',
    component: '/console/mcp-proxy/list/index',
    meta: {
      title: 'MCP',
      icon: 'ri:cloud-line',
      keepAlive: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },
  {
    path: '/mcp-proxy/:id',
    name: 'ImawxMcpProxyDetail',
    component: '/console/mcp-proxy/detail/index',
    meta: {
      title: 'MCP 服务详情',
      isHide: true,
      activeMenu: '/mcp-proxy',
      roles: IMAWX_ROLES
    }
  },

  // 4. 调用日志（叶子 1 级，prd §6.2.5）
  {
    path: '/monitor/logs',
    name: 'ImawxMonitorLogs',
    component: '/console/monitor/logs/index',
    meta: {
      title: '调用日志',
      icon: 'ri:line-chart-line',
      keepAlive: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  {
    path: '/monitor/access-logs',
    name: 'ImawxAccessLogs',
    component: '/console/monitor/access-logs/index',
    meta: {
      title: '访问日志',
      icon: 'ri:route-line',
      keepAlive: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  // 4b. 日志文件（叶子 1 级，2026-06-28 加：运维查日志文件 / 实时 tail）
  //     路径 /monitor/log-files 跟 /monitor/logs 平级，语义清晰区分为
  //     「调用日志」= 业务调用记录 vs 「日志文件」= 应用 log 文件。
  {
    path: '/monitor/log-files',
    name: 'ImawxLogFile',
    component: '/console/monitor/log-file/index',
    meta: {
      title: '日志文件',
      icon: 'ri:file-list-3-line',
      keepAlive: false,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  // 5. 系统配置（叶子 1 级，prd §3.2 P2 系统配置页面）
  {
    path: '/system/config',
    name: 'ImawxSystemConfig',
    component: '/console/system/config/index',
    meta: {
      title: '系统配置',
      icon: 'ri:settings-3-line',
      keepAlive: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  // 7. 用户管理（叶子 1 级，2026-07-03 加）—— admin 在 R_SUPER 角色下管理后台账号
  //    (CRUD / TOTP / 重置密码 / 启停)。f9db029 写了页面 + api 但漏挂路由，
  //    补在 /system/config 后面保持 5+1 顺序。
  {
    path: '/system/user',
    name: 'ImawxSystemUser',
    component: '/system/user/index',
    meta: {
      title: '用户管理',
      icon: 'ri:user-settings-line',
      keepAlive: true,
      roles: IMAWX_ROLES,
      isFirstLevel: true
    }
  },

  // 6. 账号（父级 1 级 + 3 个子页面：profile / password / tokens）
  //    必须显式 component: '/index/index'（layout 容器），否则子页面没容器
  //    2026-06-29 重构：从侧边栏菜单隐藏（isHide: true），统一在头像下拉里点进。
  //    路由本身保留,头像下拉的「个人信息 / 修改密码 / 我的 MCP Token」三个入口
  //    仍能正常跳转;只是侧边栏不再有这个菜单项,避免重复暴露。
  {
    path: '/account',
    name: 'ImawxAccount',
    component: '/index/index',
    redirect: '/account/profile',
    meta: {
      title: '账号',
      icon: 'ri:user-3-line',
      roles: IMAWX_ROLES,
      isFirstLevel: true,
      isHide: true
    },
    children: [
      {
        path: 'profile',
        name: 'ImawxAccountProfile',
        component: '/console/account/profile/index',
        meta: { title: '个人信息' }
      },
      {
        path: 'password',
        name: 'ImawxAccountPassword',
        component: '/console/account/password/index',
        meta: { title: '修改密码' }
      },
      {
        path: 'tokens',
        name: 'ImawxAccountTokens',
        component: '/console/account/tokens/index',
        meta: { title: '我的 MCP Token', keepAlive: true }
      }
    ]
  }
]

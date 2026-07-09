import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { fileURLToPath } from 'url'
import vueDevTools from 'vite-plugin-vue-devtools'
import viteCompression from 'vite-plugin-compression'
import Components from 'unplugin-vue-components/vite'
import AutoImport from 'unplugin-auto-import/vite'
import ElementPlus from 'unplugin-element-plus/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import tailwindcss from '@tailwindcss/vite'
// import { visualizer } from 'rollup-plugin-visualizer'
import type { Connect, Plugin } from 'vite'

/**
 * dev-only mock plugin:拦截 /api/sys/* 返 {code:200,message:"OK",data:...}
 * 让前端无后端启动,playwright 能跑所有路由找运行时 bug。
 *
 * <p>开关:启动 dev server 时设 {@code VITE_MODE=mock} 才注册本插件(用于无后端的
 * playwright E2E / 静态走查);不设则返回 noop 插件,vite 走 proxy 把 /api/** 转发到
 * {@code VITE_API_PROXY_URL} 指向的真后端。production build 也不注册
 * ({@code apply: 'serve'} 天然排除 build)。默认行为是真后端优先,避免开发时
 * vite 拦截了所有 /api/sys/** 导致看不到数据库里的真实数据。
 *
 * <p>用法:
 * <pre>
 *   pnpm dev              # 默认:不 mock,proxy 到 VITE_API_PROXY_URL
 *   VITE_MODE=mock pnpm dev  # 显式 mock(playwright 跑路由用)
 * </pre>
 */
function imawxDevMock(enabled: boolean): Plugin {
  if (!enabled) {
    return { name: 'imawx-dev-mock-disabled' }
  }
  return {
    name: 'imawx-dev-mock',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/api/sys', (req: Connect.IncomingMessage, res, next) => {
        // 排除 vite 自己的 /api 资源
        if (!req.url || req.url.startsWith('/@')) return next()
        const url = req.url.split('?')[0]

        // 默认空成功
        const ok = (data: any) => {
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ code: 200, message: 'OK', data }))
        }
        const fail = (code: number, message: string) => {
          res.setHeader('Content-Type', 'application/json')
          res.statusCode = code
          res.end(JSON.stringify({ code, message, data: null }))
        }

        // === /api/sys/auth/me === 当前用户信息
        if (url === '/auth/me' || url === '/auth/me/') {
          return ok({
            userId: 1,
            userName: 'dev',
            nickName: '开发账号',
            avatar: '',
            roles: ['R_SUPER', 'R_ADMIN'],
            permissions: ['*']
          })
        }

        // === /api/sys/auth/login === dev 自动登录
        if (url === '/auth/login' || url === '/auth/login/') {
          res.setHeader('Set-Cookie', 'JSESSIONID=dev-mock; Path=/')
          return ok({
            userId: 1,
            userName: 'dev',
            roles: ['R_SUPER', 'R_ADMIN']
          })
        }

        // === /api/sys/auth/login-methods === 登录方式清单(登录页用)
        if (url === '/auth/login-methods' || url === '/auth/login-methods/') {
          return ok({ password: true, gitee: true })
        }

        // === /api/sys/icons/svgl === brand icon 列表
        if (url === '/icons/svgl' || url === '/icons/svgl/') {
          return ok([])
        }

        // === /api/sys/monitor/logs === 调用日志
        if (url === '/monitor/logs' || url === '/monitor/logs/') {
          return ok([])
        }
        // /api/sys/monitor/logs/{id} 详情
        if (url.match(/^\/monitor\/logs\/\d+$/)) {
          return ok({
            id: 1,
            invokedAt: '2026-06-27T10:00:00',
            serverType: 'EXTERNAL',
            serverName: 'GitHub MCP',
            toolName: 'list_repos',
            status: 'SUCCESS',
            costMs: 120,
            useAgent: 'claude',
            clientIp: '127.0.0.1',
            tokenId: null,
            requestJson: '{}',
            responseJson: '{}',
            errorMessage: null
          })
        }
        // /api/sys/monitor/logs/export CSV(空 CSV)
        if (url === '/monitor/logs/export' || url === '/monitor/logs/export/') {
          res.setHeader('Content-Type', 'text/csv')
          res.end('id,invokedAt,serverName,toolName,status\n')
          return
        }

        // === /api/sys/monitor/summary === 监控汇总
        if (url === '/monitor/summary' || url === '/monitor/summary/') {
          return ok({
            total: 0,
            success: 0,
            failed: 0,
            timeout: 0,
            avgDurationMs: 0,
            p95DurationMs: 0
          })
        }

        // === /api/sys/constants === 枚举常量下拉框（对应后端 SysConstantsController）
        // App.vue 启动时调一次,前端 useConstants 缓存到 module-level ref,
        // 后续所有 <el-select :options="constants.xxx" /> 都读这份缓存。
        if (url === '/constants' || url === '/constants/') {
          return ok({
            dbType: [
              { value: 'MYSQL', desc: 'MySQL', defaultPort: 3306 },
              { value: 'POSTGRESQL', desc: 'PostgreSQL', defaultPort: 5432 },
              { value: 'ORACLE', desc: 'Oracle', defaultPort: 1521 },
              { value: 'SQLSERVER', desc: 'SQL Server', defaultPort: 1433 }
            ],
            transportType: [
              { value: 'STDIO', desc: 'STDIO（本地进程）' },
              { value: 'SSE', desc: 'SSE' },
              { value: 'HTTP', desc: 'Streamable HTTP' }
            ],
            serverType: [
              { value: 'EXTERNAL', desc: '外部 MCP' },
              { value: 'DB', desc: '数据库' }
            ],
            invokeStatus: [
              { value: 'SUCCESS', desc: '成功' },
              { value: 'FAILED', desc: '失败' },
              { value: 'TIMEOUT', desc: '超时' }
            ],
            connectionStatus: [
              { value: 'DISCONNECTED', desc: '未连接' },
              { value: 'CONNECTED', desc: '已连接' },
              { value: 'FAILED', desc: '失败' }
            ]
          })
        }

        // === /api/sys/mcp-proxy === 外部 MCP 服务
        if (url === '/mcp-proxy' || url === '/mcp-proxy/') {
          return ok({
            records: [],
            total: 0,
            current: 1,
            size: 20
          })
        }
        if (url.match(/^\/mcp-proxy\/\d+$/)) {
          return ok({
            id: 1,
            serverName: 'GitHub MCP',
            transportType: 'SSE',
            endpoint: 'https://example.com/sse',
            extraConfig: '',
            remark: '',
            status: 1,
            lastCheckAt: '2026-06-27T10:00:00',
            lastError: null,
            lastSyncError: null,
            enabled: 1,
            lastSyncAt: null,
            toolCount: 0,
            createdAt: '2026-06-20T10:00:00',
            updatedAt: '2026-06-27T10:00:00',
            tools: []
          })
        }
        if (url.match(/^\/mcp-proxy\/\d+\/test$/)) {
          return ok([])
        }
        if (url.match(/^\/mcp-proxy\/\d+\/sync$/)) {
          return ok({ synced: 0, count: 0 })
        }
        if (url.match(/^\/mcp-proxy\/\d+\/enable$/)) {
          return ok(null)
        }
        if (url.match(/^\/mcp-proxy\/\d+\/disable$/)) {
          return ok(null)
        }

        // === /api/sys/system/config === 系统配置(GET 返 List<McpSystemConfigVo>)
        if (url === '/system/config' || url === '/system/config/') {
          return ok([
            {
              configKey: 'mcp.global.enabled',
              configValue: '1',
              description: '全局 MCP Server 开关（0=关闭 1=开启）',
              updatedAt: '2026-06-27T10:23:11'
            },
            {
              configKey: 'mcp.session.timeout-hours',
              configValue: '8',
              description: 'MCP Session 空闲超时小时数',
              updatedAt: '2026-06-26T18:02:44'
            },
            {
              configKey: 'mcp.tool.log.retention-days',
              configValue: '90',
              description: '调用日志保留天数（默认 90）',
              updatedAt: '2026-06-25T09:15:00'
            }
          ])
        }

        // === /api/sys/account/profile === 个人信息
        if (url === '/account/profile' || url === '/account/profile/') {
          return ok({
            userId: 1,
            userName: 'dev',
            nickName: '开发账号',
            email: 'dev@example.com',
            phone: '13800000000',
            avatar: '',
            roles: ['R_SUPER', 'R_ADMIN']
          })
        }
        // === /api/sys/account/password === 改密
        if (url === '/account/password' || url === '/account/password/') {
          return ok(null)
        }
        // === /api/sys/account/tokens === 我的 MCP Token(GET 返 List 直接数组)
        if (url === '/account/tokens' || url === '/account/tokens/') {
          return ok([])
        }
        if (url.match(/^\/account\/tokens\/\d+$/)) {
          return ok(null)
        }
        if (url === '/account/tokens' && req.method === 'POST') {
          return ok({ id: 100, accessToken: 'mcp_dev_mock_token_xxxxxxxxxxxx' })
        }

        // === /api/sys/menu/routes === 动态路由
        if (url === '/menu/routes' || url === '/menu/routes/') {
          return ok([])
        }

        // 其它所有 GET 返空数据,POST/PUT/DELETE 返 ok
        if (req.method === 'GET') return ok([])
        return ok(null)
      })
    }
  }
}

export default ({ mode }: { mode: string }) => {
  const root = process.cwd()
  const env = loadEnv(mode, root)
  const { VITE_VERSION, VITE_PORT, VITE_BASE_URL, VITE_API_URL, VITE_API_PROXY_URL } = env

  console.log(`🚀 API_URL = ${VITE_API_URL}`)
  console.log(`🚀 VERSION = ${VITE_VERSION}`)

  return defineConfig({
    define: {
      __APP_VERSION__: JSON.stringify(VITE_VERSION)
    },
    base: VITE_BASE_URL,
    server: {
      port: Number(VITE_PORT),
      proxy: {
        '/api': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        }
      },
      host: true
    },
    // 路径别名
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@views': resolvePath('src/views'),
        '@imgs': resolvePath('src/assets/images'),
        '@icons': resolvePath('src/assets/icons'),
        '@utils': resolvePath('src/utils'),
        '@stores': resolvePath('src/store'),
        '@styles': resolvePath('src/assets/styles')
      }
    },
    build: {
      target: 'es2015',
      outDir: 'dist',
      chunkSizeWarningLimit: 2000,
      minify: 'terser',
      terserOptions: {
        compress: {
          // 生产环境去除 console
          drop_console: true,
          // 生产环境去除 debugger
          drop_debugger: true
        }
      },
      dynamicImportVarsOptions: {
        warnOnError: true,
        exclude: [],
        include: ['src/views/**/*.vue']
      }
    },
    plugins: [
      vue(),
      tailwindcss(),
      imawxDevMock(env.VITE_MODE === 'mock'),
      // 自动按需导入 API
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia', '@vueuse/core'],
        dts: 'src/types/import/auto-imports.d.ts',
        resolvers: [ElementPlusResolver()],
        eslintrc: {
          enabled: true,
          filepath: './.auto-import.json',
          globalsPropValue: true
        }
      }),
      // 自动按需导入组件
      Components({
        dts: 'src/types/import/components.d.ts',
        resolvers: [ElementPlusResolver()]
      }),
      // 按需定制主题配置
      ElementPlus({
        useSource: true
      }),
      // 压缩
      viteCompression({
        verbose: false, // 是否在控制台输出压缩结果
        disable: false, // 是否禁用
        algorithm: 'gzip', // 压缩算法
        ext: '.gz', // 压缩后的文件名后缀
        threshold: 10240, // 只有大小大于该值的资源会被处理 10240B = 10KB
        deleteOriginFile: false // 压缩后是否删除原文件
      }),
      vueDevTools()
      // 打包分析
      // visualizer({
      //   open: true,
      //   gzipSize: true,
      //   brotliSize: true,
      //   filename: 'dist/stats.html' // 分析图生成的文件名及路径
      // }),
    ],
    // 依赖预构建：避免运行时重复请求与转换，提升首次加载速度
    optimizeDeps: {
      include: [
        'echarts/core',
        'echarts/charts',
        'echarts/components',
        'echarts/renderers',
        'element-plus/es',
        'element-plus/es/components/*/style/css',
        'element-plus/es/components/*/style/index'
      ]
    },
    css: {
      preprocessorOptions: {
        // sass variable and mixin
        scss: {
          additionalData: `
            @use "@styles/core/el-light.scss" as *; 
            @use "@styles/core/mixin.scss" as *;
          `
        }
      },
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  })
}

function resolvePath(paths: string) {
  return path.resolve(__dirname, paths)
}

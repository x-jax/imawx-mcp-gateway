import { defineAsyncComponent } from 'vue'

export const globalComponentsConfig: GlobalComponentConfig[] = [
  {
    name: '设置面板',
    key: 'settings-panel',
    component: defineAsyncComponent(
      () => import('@/components/core/layouts/art-settings-panel/index.vue')
    ),
    enabled: true
  },
  {
    name: '全局搜索',
    key: 'global-search',
    component: defineAsyncComponent(
      () => import('@/components/core/layouts/art-global-search/index.vue')
    ),
    enabled: true
  },
  {
    name: '水印效果',
    key: 'watermark',
    component: defineAsyncComponent(
      () => import('@/components/core/others/art-watermark/index.vue')
    ),
    enabled: true
  }
]

export interface GlobalComponentConfig {
  name: string
  key: string
  component: any
  enabled?: boolean
  description?: string
}

export const getEnabledGlobalComponents = () => {
  return globalComponentsConfig.filter((config) => config.enabled !== false)
}

export const getGlobalComponentByKey = (key: string) => {
  return globalComponentsConfig.find((config) => config.key === key)
}

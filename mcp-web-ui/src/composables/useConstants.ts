/**
 * useConstants — 系统常量下拉框缓存 composable。
 *
 * <p>应用启动时拉一次 {@code /api/sys/constants}，缓存到模块级 ref。
 * 后续所有 {@code <el-select :options="getOptions('protocol')" />} 直接读缓存，
 * 不用每个组件单独 fetch。
 *
 * <p>使用方式：
 * <pre>
 * const { getOptions, loadConstants } = useConstants()
 * onMounted(loadConstants)
 *
 * &lt;el-select :options="getOptions('protocol')" /&gt;
 * </pre>
 *
 * <p>如果常量数据为空（首次加载未完成 / 加载失败），{@code getOptions} 返回 {@code []}，
 * 组件会显示空下拉框，但不会崩。
 *
 * <p>2026-07-01 重构:对齐后端 DictOption 结构,value 类型 string → unknown,
 * desc 字段保留(兼容老代码)+ 新增 label 字段(新代码用)。
 */
import {
  fetchConstantsSys,
  type ImawxConstantsMap,
  type ImawxOptionVo
} from '@/api/sys/constants'

const constants = ref<ImawxConstantsMap>({})
const loaded = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)

/**
 * 暴露的 composable：每个调用方共享同一份缓存（模块级 ref）。
 */
export function useConstants() {
  /**
   * 加载常量（幂等：已加载过直接返回）。
   */
  async function loadConstants() {
    if (loaded.value || loading.value) {
      return
    }
    loading.value = true
    error.value = null
    try {
      const data = await fetchConstantsSys()
      constants.value = data ?? {}
      loaded.value = true
    } catch (e) {
      error.value = (e as Error).message || '加载常量失败'
      constants.value = {}
    } finally {
      loading.value = false
    }
  }

  /**
   * 取指定 key 的下拉框选项,找不到返回空数组。
   *
   * <p>渲染 ElOption 时,label 优先读 {@code label} 字段(2026-07-01 之后),
   * fallback 到 {@code desc} 字段(2026-07-01 之前的兼容)。
   */
  function getOptions(key: string): ImawxOptionVo[] {
    return constants.value[key] ?? []
  }

  /**
   * 工具方法:把 DictOption 转成 ElOption 期望的 {@code {label, value}} 形状。
   *
   * <p>使用示例:
   * <pre>
   * const { getOptionItems } = useConstants()
   * const protocolOptions = computed(() => getOptionItems('protocol'))
   * </pre>
   *
   * <p>label 字段优先级 {@code label > desc} —— 新前端用 label,老代码 fallback。
   */
  function getOptionItems(key: string): Array<{ label: string; value: unknown }> {
    return getOptions(key).map((o) => ({ label: o.label || o.desc || '', value: o.value }))
  }

  /**
   * 工具方法:按 value 反查某个 key 下的 label。
   *
   * <p>例:userId 是数字 1,在 userStatus 字典里查 → "启用"。
   */
  function lookupLabel(key: string, value: unknown): string | undefined {
    const opt = getOptions(key).find((o) => o.value === value)
    return opt?.label || opt?.desc
  }

  return {
    constants,
    loaded,
    loading,
    error,
    loadConstants,
    getOptions,
    getOptionItems,
    lookupLabel
  }
}

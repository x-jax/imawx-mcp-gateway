package com.imawx.mcp.gateway.common.dict;

/**
 * 前端字典条目统一格式(2026-07-01 重构:替代之前的 {@code Map.of("value", ?, "desc", ?)})。
 *
 * <p>前后端契约:
 * <ul>
 *   <li>{@code value}:业务值,可能是数字(用户状态)、字符串(协议、状态);JSON 序列化后保持原类型</li>
 *   <li>{@code label}:中文展示名,前端直接拿来当 ElOption label / chip 文本</li>
 *   <li>{@code key}:业务分组 key(可选,某些列表场景需要回显 key 做前端关联)</li>
 *   <li>{@code ext}:扩展字段(可选,例如颜色 hex / icon 名 / tooltip 文本)</li>
 * </ul>
 *
 * <p>设计要点:
 * <ul>
 *   <li>统一字段名 —— 之前是 {@code desc},前端 useConstants 也是 {@code desc};
 *       现在改成 {@code label}(更通用,跟 Element Plus ElOption label 对齐)。
 *       前端 useConstants 同时兼容 {@code desc} 老 key(读不到 label 才 fallback),
 *       渐进式迁移,不需要一次性改完所有页面。</li>
 *   <li>value 类型由源枚举决定 —— Integer / String,JSON 序列化后类型保持;
 *       前端按 value 类型用,不要做强制类型转换</li>
 *   <li>用 record 而不是 Map —— Java 端类型安全,Swagger 输出清晰,
 *       前端用 {@code Record<string, unknown>} 接收,运行时按字段访问</li>
 * </ul>
 *
 * @param value 业务值(JSON 原生类型:Integer/Long/String)
 * @param label 中文展示名
 * @param key   业务分组 key(可选,用于跨页面对齐的固定标识)
 * @param ext   扩展字段(可选,例如 {@code {"color":"#ff0000","icon":"ri:..."}})
 *
 * @author Mavis
 * @since 2026-07-01
 */
public record DictOption(
        Object value,
        String label,
        String key,
        Object ext
) {

    /** 简化构造 —— 不带 key / ext,99% 场景用这个。 */
    public static DictOption of(Object value, String label) {
        return new DictOption(value, label, null, null);
    }

    /** 带 key —— 用于跨页面跨字段引用,key 是稳定的英文标识符。 */
    public static DictOption of(Object value, String label, String key) {
        return new DictOption(value, label, key, null);
    }

    /** 带 ext —— 用于前端 chip 颜色 / icon。 */
    public static DictOption of(Object value, String label, String key, Object ext) {
        return new DictOption(value, label, key, ext);
    }
}
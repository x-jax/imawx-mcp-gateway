package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.dict.DictKeys;
import com.imawx.mcp.gateway.common.dict.DictOption;
import com.imawx.mcp.gateway.common.enums.DbTypeEnum;
import com.imawx.mcp.gateway.common.enums.TransportType;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.enums.ConnectionStatusEnum;
import com.imawx.mcp.gateway.entity.enums.InvokeStatusEnum;
import com.imawx.mcp.gateway.entity.enums.LoginMethodEnum;
import com.imawx.mcp.gateway.entity.enums.ServerTypeEnum;
import com.imawx.mcp.gateway.entity.enums.UserStatusEnum;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端常量字典端点(2026-07-01 重构为枚举驱动)。
 *
 * <p>前后端契约:
 * <ul>
 *   <li>返回值结构 {@code Map<String, List<DictOption>>} —— 每个 key 都是 {@link DictOption} 列表</li>
 *   <li>{@link DictOption} 字段:{@code value, label, key, ext} —— 详情见 DictOption.java</li>
 *   <li>前端 {@code useConstants().getOptions(key)} 直接读 list,渲染 ElOption / chip / icon</li>
 * </ul>
 *
 * <p>实现要点:
 * <ul>
 *   <li>用 LinkedHashMap 保持 key 插入顺序 —— 前端首屏读取的稳定性 + 文档易读性</li>
 *   <li>每个字典都从对应枚举的 {@code asDictOptions()} 拿 —— 新增枚举值自动进前端,零散写 SQL 改前端</li>
 *   <li>旧的 {@code desc} 字段保留(老前端兼容),但新代码用 {@code label}</li>
 * </ul>
 *
 * @author Mavis
 * @since 2026-07-01
 */
@RestController
@RequestMapping("/api/sys/constants")
public class SysConstantsController {

    /**
     * 返回全部字典,前端按 key 缓存。
     *
     * @return key → {@link DictOption} 列表的有序映射
     */
    @GetMapping
    public R<Map<String, List<DictOption>>> list() {
        Map<String, List<DictOption>> dict = new LinkedHashMap<>();
        dict.put(DictKeys.PROTOCOL, TransportType.asDictOptions());
        dict.put(DictKeys.DB_TYPE, DbTypeEnum.asDictOptions());
        dict.put(DictKeys.CONNECTION_STATUS, ConnectionStatusEnum.asDictOptions());
        dict.put(DictKeys.INVOKE_STATUS, InvokeStatusEnum.asDictOptions());
        dict.put(DictKeys.LOGIN_METHODS, LoginMethodEnum.asDictOptions());
        dict.put(DictKeys.SERVER_TYPE, ServerTypeEnum.asDictOptions());
        dict.put(DictKeys.USER_STATUS, UserStatusEnum.asDictOptions());
        return R.ok(dict);
    }
}

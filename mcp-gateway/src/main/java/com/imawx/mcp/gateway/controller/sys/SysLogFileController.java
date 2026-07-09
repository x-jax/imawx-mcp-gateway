package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.vo.McpLogFileVO;
import com.imawx.mcp.gateway.entity.vo.McpLogViewVO;
import com.imawx.mcp.gateway.service.monitor.McpLogFileService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 日志文件 Controller。
 *
 * <p>对应前端 {@code /api/sys/log-files/**}:
 * <ul>
 *   <li>{@code GET /files} —— 列出日志目录下所有文件</li>
 *   <li>{@code GET /view} —— 读尾部 N 行(支持 level 过滤 + since 字节 offset 增量读)</li>
 *   <li>{@code GET /download} —— 文件流下载</li>
 *   <li>{@code GET /tail-stream} —— SSE 实时订阅(替代前端 2s 轮询)</li>
 * </ul>
 *
 * <p>鉴权:session 必须登录。当前阶段所有登录用户都是 admin。
 *
 * <p>设计要点:
 * <ul>
 *   <li>filename 在 Service 层做白名单 + 路径穿越校验,Controller 不重复</li>
 *   <li>view 接口响应行数受 {@code mcp-gateway.log.max-tail-lines} 兜底限制</li>
 *   <li>download 流式写,不一次性读进内存(支持大文件下载)</li>
 *   <li>SSE 端点用 {@link ScheduledExecutorService} 单线程池共享调度,避免每连接一线程</li>
 * </ul>
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/log-files")
@RequiredArgsConstructor
public class SysLogFileController {

    private final McpLogFileService logFileService;

    /**
     * 共享调度器 —— 所有 SSE 连接共用,定时拉文件增量。
     * <p>单线程池避免每连接一线程;tail-check 间隔 500ms 比轮询 2s 实时性高 4 倍且后端负载更低。
     */
    private static final ScheduledExecutorService SSE_TAIL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-sse-tail");
                t.setDaemon(true);
                return t;
            });

    /**
     * 列出日志目录下所有文件(活跃 + 历史归档),按修改时间倒序。
     *
     * @param session HTTP session(用于鉴权)
     * @return 文件列表
     */
    @GetMapping("/files")
    public R<List<McpLogFileVO>> listFiles(HttpSession session) {
        requireAdmin(session);
        return R.ok(logFileService.listFiles());
    }

    /**
     * 读取日志尾部 N 行(支持 level 过滤 + since 字节 offset 增量读)。
     *
     * @param file  文件名(可空 = 活跃日志)
     * @param level 日志级别精确过滤(可空)
     * @param lines 行数(默认 200,上限 {@code mcp-gateway.log.max-tail-lines})
     * @param since 字节偏移量(可空,>0 启用增量读模式)—— 前端"实时刷新"模式拿
     *              上次响应的 {@code fileSize} 当 {@code since},服务端从该位置读到末尾
     * @param session HTTP session
     * @return 视图 VO(lines + fileSize)
     */
    @GetMapping("/view")
    public R<McpLogViewVO> view(
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer lines,
            @RequestParam(required = false) Long since,
            HttpSession session
    ) {
        requireAdmin(session);
        return R.ok(logFileService.viewTail(file, level, lines, since));
    }

    /**
     * SSE 实时订阅日志末尾增量 —— 替代前端 2s setInterval 轮询。
     *
     * <p>协议:
     * <ul>
     *   <li>{@code Content-Type: text/event-stream} + {@code Cache-Control: no-cache}</li>
     *   <li>每 500ms 一次检查文件 size,若有增量则推一条 {@code tail} 事件</li>
     *   <li>文件被 rotate(size &lt; lastOffset)→ 推一条 {@code rotate} 事件,offset 重置</li>
     *   <li>客户端断开 → onCompletion/onError 取消调度,避免泄漏</li>
     * </ul>
     *
     * <p>单线程调度器共享 —— 多个 SSE 连接交错检查,峰值并发量低。
     *
     * @param file    订阅的文件名(可空 = 活跃日志)
     * @param session HTTP session
     * @return SseEmitter 长连接
     */
    @GetMapping(path = "/tail-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter tailStream(
            @RequestParam(required = false) String file,
            HttpSession session) {
        requireAdmin(session);
        String resolvedFile = (file == null || file.isBlank())
                ? McpLogFileService.DEFAULT_ACTIVE_LOG : file;

        SseEmitter emitter = new SseEmitter(0L);
        final long[] offset = {0L};
        final ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];

        Runnable checkTail = () -> {
            try {
                McpLogViewVO view = logFileService.viewTail(resolvedFile, null, 5000, offset[0]);
                if (offset[0] > 0 && view.getFileSize() < offset[0]) {
                    offset[0] = view.getFileSize();
                    SseEmitter.SseEventBuilder ev = SseEmitter.event()
                            .name("rotate")
                            .data(view, MediaType.APPLICATION_JSON);
                    emitter.send(ev);
                    return;
                }
                if (view.getLines() != null && !view.getLines().isEmpty()) {
                    offset[0] = view.getFileSize();
                    SseEmitter.SseEventBuilder ev = SseEmitter.event()
                            .name("tail")
                            .data(view, MediaType.APPLICATION_JSON);
                    emitter.send(ev);
                }
            } catch (IllegalArgumentException e) {
                log.warn("[sse] tail-stream 文件名非法: {}", resolvedFile);
                cancelHandle(handle);
                emitter.completeWithError(e);
            } catch (Exception e) {
                // 2026-07-03 修复:catch 块必须同时终止 schedule + emitter,
                // 否则 schedule 永生刷屏,DEBUG 日志污染活跃日志文件,
                // 下次 schedule 又把 DEBUG 当"业务日志"读出来循环推送。
                log.error("[sse] tail-stream 检查失败(file={}): {},终止该 SSE 连接",
                        resolvedFile, e.getMessage());
                cancelHandle(handle);
                emitter.completeWithError(e);
            }
        };

        handle[0] = SSE_TAIL_EXECUTOR.scheduleAtFixedRate(checkTail, 0, 500, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> cancelHandle(handle));
        emitter.onTimeout(() -> cancelHandle(handle));
        emitter.onError(t -> cancelHandle(handle));

        return emitter;
    }

    private static void cancelHandle(ScheduledFuture<?>[] handle) {
        ScheduledFuture<?> h = handle[0];
        if (h != null) h.cancel(false);
    }

    /**
     * 下载日志文件 —— 流式写入。
     *
     * <p>不走统一 {@link R} 包装(响应是二进制流),用 {@code HttpServletResponse.getOutputStream()}
     * 直接写。文件名放 {@code Content-Disposition},前端 axios 走 blob 模式自动触发下载。
     *
     * @param file     文件名(可空 = 活跃日志)
     * @param response HTTP response
     * @param session  HTTP session
     * @throws IOException 写响应失败
     */
    @GetMapping("/download")
    public void download(
            @RequestParam(required = false) String file,
            HttpServletResponse response,
            HttpSession session
    ) throws IOException {
        requireAdmin(session);
        String name = (file == null || file.isBlank()) ? McpLogFileService.DEFAULT_ACTIVE_LOG : file;
        String downloadName = name;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) downloadName = name.substring(slash + 1);

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");

        try (InputStream in = logFileService.openDownloadStream(file);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    /**
     * 仅登录用户可访问日志文件。
     *
     * <p>当前阶段所有登录用户都是 admin(role=R_SUPER);
     * 后续加 RBAC 时在这里补充 role 校验。
     */
    private void requireAdmin(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
    }
}
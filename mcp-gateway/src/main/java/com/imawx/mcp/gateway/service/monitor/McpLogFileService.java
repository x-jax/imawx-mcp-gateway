package com.imawx.mcp.gateway.service.monitor;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.security.SensitiveDataMasker;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.vo.McpLogFileVO;
import com.imawx.mcp.gateway.entity.vo.McpLogViewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 日志文件查看 Service(2026-07-01 加)。
 *
 * <p>读 {@code logs/} 目录下的所有日志文件(活跃 + 归档),不做修改,只读。
 *
 * <h3>防越权</h3>
 * <ul>
 *   <li>filename 参数严格限定 — 只允许 {@code .log} / {@code .log.*} / {@code .gz} 后缀</li>
 *   <li>路径必须解析后落在 {@code imawx.log.dir} 根目录内(防 {@code ../} 路径穿越)</li>
 *   <li>尾部行数限制 — {@code imawx.log.max-tail-lines} 兜底,防止 admin 误传 {@code lines=999999999} 把内存打爆</li>
 *   <li>gzip 自动解压 — 归档文件以 {@code .gz} 结尾,Stream 透明解</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpLogFileService {

    /** 允许的文件名正则 — 防止 admin 乱传路径。前端约定俗成 logback rolling 出来的命名: */
    /** {@code appname.log} / {@code appname.log.YYYY-MM-DD.N} / {@code appname.log.YYYY-MM-DD.N.gz} */
    private static final Pattern ALLOWED_FILENAME = Pattern.compile(
            "^[A-Za-z0-9_\\-\\.\\+\\(\\)\\[\\] ]+\\.log(\\.[A-Za-z0-9_\\-\\.\\(\\)\\[\\] ]+)?(\\.gz)?$"
    );

    /** 默认活跃日志文件名(跟 application.yml 的 logging.file.name 对齐)。 */
    public static final String DEFAULT_ACTIVE_LOG = "mcp-gateway.log";

    private final McpGatewayProperties properties;

    /**
     * 列出所有日志文件(活跃 + 历史归档),按修改时间倒序。
     *
     * <p>归档文件查找:Spring Boot 默认的 {@code DefaultLogbackConfiguration} 把归档放在
     * {@code logs/archive/} 子目录(具体行为取决于 logback rolling policy)。我们两个目录都扫。
     */
    public List<McpLogFileVO> listFiles() {
        Path logDir = resolveLogDir();
        if (!Files.isDirectory(logDir)) {
            log.warn("[log-files] log dir not found: {}", logDir.toAbsolutePath());
            return Collections.emptyList();
        }
        List<McpLogFileVO> files = new ArrayList<>();
        // 1. 根目录扫描 .log 文件
        collectFromDir(logDir, "", files);
        // 2. archive 子目录扫描(如果有)
        Path archiveDir = logDir.resolve("archive");
        if (Files.isDirectory(archiveDir)) {
            collectFromDir(archiveDir, "archive/", files);
        }
        // 按 lastModified 倒序
        files.sort(Comparator.comparingLong(McpLogFileVO::getLastModified).reversed());
        return files;
    }

    private void collectFromDir(Path dir, String namePrefix, List<McpLogFileVO> out) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String n = p.getFileName().toString();
                      return ALLOWED_FILENAME.matcher(n).matches();
                  })
                  .forEach(p -> out.add(toVO(p, namePrefix)));
        } catch (IOException e) {
            log.warn("[log-files] list dir {} failed: {}", dir, e.getMessage());
        }
    }

    private McpLogFileVO toVO(Path file, String namePrefix) {
        String fullName = namePrefix + file.getFileName().toString();
        long size;
        long mtime;
        try {
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            size = 0;
            mtime = 0L;
        }
        String fileName = file.getFileName().toString();
        boolean gzipped = fileName.endsWith(".gz");
        // 归档文件(在 archive/ 子目录里或带 .gz 后缀)归为 archive
        boolean isArchive = !namePrefix.isEmpty() || gzipped;
        return McpLogFileVO.builder()
                .name(fullName)
                .absolutePath(file.toAbsolutePath().toString())
                .size(size)
                .lastModified(mtime)
                .gzipped(gzipped)
                .category(isArchive ? "archive" : "active")
                .build();
    }

    /**
     * 读取日志末尾 N 行(支持 level 过滤 + 2026-07-02 加 since 字节 offset 增量读)。
     *
     * <p>2026-07-02 改:支持 {@code since} 字节偏移量增量读 —— 前端"实时刷新"模式
     * 拿上次响应里的 {@code fileSize} 当下次 {@code since},服务端从该位置读到末尾,
     * 避免每次全量读文件 → 全量过滤 → 取尾部 N 行(2MB 文件每次扫 50000 行,
     * 实时刷新延迟肉眼可见)。
     *
     * <p>{@code since} 语义:
     * <ul>
     *   <li>{@code null/0} —— 走"取尾部 N 行"模式(老逻辑,默认)</li>
     *   <li>{@code >0} —— 从该字节位置读到文件末尾,过滤 level,返回全部行 + 新 fileSize</li>
     * </ul>
     *
     * <p>文件被 rotate(归档)或 truncate 后,since 字节位置可能已失效 —— 后端会自动
     * fallback:如果 {@code since >= 当前 size},返回空 lines + 新 size,前端拿到新 size
     * 重置本地状态即可。
     *
     * @param file 文件名(可空,空=活跃日志);不允许路径穿越
     * @param level 日志级别(可空),精确匹配日志里的 {@code INFO/WARN/ERROR/DEBUG/TRACE} 字段
     * @param lines 请求行数(默认 200,上限 {@code imawx.log.max-tail-lines})
     * @param since 字节偏移量(可空);{@code >0} 启用增量读模式
     */
    public McpLogViewVO viewTail(String file, String level, Integer lines, Long since) {
        Path resolved = resolveFile(file);
        if (!Files.exists(resolved)) {
            throw new BizException(BizErrorCode.NOT_FOUND, "日志文件不存在: " + file);
        }
        long currentSize;
        try {
            currentSize = Files.size(resolved);
        } catch (IOException e) {
            currentSize = 0;
        }

        // since 增量模式:从 since 字节读到末尾,过滤 level,返全部行
        if (since != null && since > 0) {
            if (since >= currentSize) {
                // 文件被 rotate/truncate,since 失效 → 返空 lines 让前端重置
                return McpLogViewVO.builder()
                        .file(resolved.getFileName().toString())
                        .lines(List.of())
                        .fileSize(currentSize)
                        .build();
            }
            List<String> out = new ArrayList<>();
            try (RandomAccessFile raf = new RandomAccessFile(resolved.toFile(), "r")) {
                raf.seek(since);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(raf.getFD()), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (level != null && !level.isBlank() && !line.contains(" " + level + " ")) {
                            continue;
                        }
                        out.add(SensitiveDataMasker.redactText(line));
                    }
                }
            } catch (IOException e) {
                throw new BizException(BizErrorCode.INTERNAL_ERROR, "读日志失败: " + e.getMessage());
            }
            return McpLogViewVO.builder()
                    .file(resolved.getFileName().toString())
                    .lines(out)
                    .fileSize(currentSize)
                    .build();
        }

        // 老的"取尾部 N 行"模式(无 since)
        int requested = lines == null || lines <= 0 ? 200 : lines;
        int cap = Math.min(requested, properties.getLog().getMaxTailLines());

        Deque<String> tail = new LinkedList<>();
        try (InputStream in = openStream(resolved);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (level != null && !level.isBlank() && !line.contains(" " + level + " ")) {
                    continue;
                }
                if (tail.size() == cap) {
                    tail.pollFirst();
                }
                tail.offerLast(SensitiveDataMasker.redactText(line));
            }
        } catch (IOException e) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "读日志失败: " + e.getMessage());
        }
        List<String> out = new ArrayList<>(tail);
        Collections.reverse(out); // 翻回来 → 时间正序
        return McpLogViewVO.builder()
                .file(resolved.getFileName().toString())
                .lines(out)
                .fileSize(currentSize)
                .build();
    }

    /**
     * 取文件 InputStream 供 Controller 写回响应。
     *
     * <p>注意:Stream 由调用方关闭(Controller 用 try-with-resources 写入 response)。
     * gzip 文件透明解压。
     */
    public InputStream openDownloadStream(String file) {
        Path resolved = resolveFile(file);
        if (!Files.exists(resolved)) {
            throw new BizException(BizErrorCode.NOT_FOUND, "日志文件不存在: " + file);
        }
        try {
            return openStream(resolved);
        } catch (IOException e) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "打开日志文件失败: " + e.getMessage());
        }
    }

    /**
     * 打开文件输入流 —— 透明处理 .gz 压缩。
     *
     * <p>实现:同步链路全部用 {@link InputStream},gzip 时套一层
     * {@link GZIPInputStream} 解压,调用方拿到的是一致的"明文流"。
     */
    private InputStream openStream(Path file) throws IOException {
        InputStream raw = Files.newInputStream(file);
        return file.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(raw)
                : raw;
    }

    /**
     * 解析文件名 → 绝对路径,带防越权。
     *
     * <ul>
     *   <li>空 / null → 默认活跃日志</li>
     *   <li>文件名必须命中 {@link #ALLOWED_FILENAME}</li>
     *   <li>解析后必须落在 {@code imawx.log.dir} 根目录内(防 {@code ../} 路径穿越)</li>
     * </ul>
     */
    public Path resolveReadableLogFile(String fileName) {
        return resolveFile(fileName);
    }

    private Path resolveFile(String fileName) {
        Path logDir = resolveLogDir();
        String name = (fileName == null || fileName.isBlank()) ? DEFAULT_ACTIVE_LOG : fileName;
        String validateName = name;
        if (name.startsWith("archive/")) {
            validateName = name.substring("archive/".length());
        } else if (name.contains("/") || name.contains("\\")) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "非法文件名: " + name);
        }
        if (!ALLOWED_FILENAME.matcher(validateName).matches()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "非法文件名: " + name);
        }
        Path resolved = logDir.resolve(name).normalize();
        if (!resolved.startsWith(logDir)) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "禁止访问日志目录外的文件: " + fileName);
        }
        return resolved;
    }

    /**
     * 把 {@code imawx.log.dir} 解析成绝对路径。
     *
     * <p>相对路径(默认 {@code logs})按 {@code System.getProperty("user.dir")} 解析成项目根;
     * 绝对路径直接用。
     */
    private Path resolveLogDir() {
        String dir = properties.getLog().getDir();
        if (!StringUtils.hasText(dir)) {
            dir = "logs";
        }
        Path p = Paths.get(dir);
        return p.isAbsolute() ? p : Paths.get(System.getProperty("user.dir"), dir);
    }

    /** 兼容字段 — 前端拿不到 name(可能含 archive/ 前缀),短名只显示文件名。 */
    public String getDefaultActiveLog() {
        return DEFAULT_ACTIVE_LOG;
    }

    /** 调试用 — 列出当前 log.dir 解析结果。 */
    public String currentLogDir() {
        return resolveLogDir().toAbsolutePath().toString();
    }

    /** 内部类 — 让 Controller 用更易读的 error code。 */
    static List<String> wrapLines(List<String> lines) {
        return Arrays.asList(lines.toArray(new String[0]));
    }
}

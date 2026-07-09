package com.imawx.mcp.gateway.service.monitor;

import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.vo.McpLogViewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogFileWebSocketHandler extends TextWebSocketHandler {

    private static final int INITIAL_LINES = 5000;
    private static final int INCREMENT_LINES = 5000;

    private final McpLogFileService logFileService;
    private final Map<String, TailSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private int index = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "log-ws-tail-" + (++index));
            t.setDaemon(true);
            return t;
        }
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String file = queryParam(session, "file");
        if (file == null || file.isBlank()) {
            file = McpLogFileService.DEFAULT_ACTIVE_LOG;
        }
        TailSubscription subscription = new TailSubscription(session, file);
        subscriptions.put(session.getId(), subscription);
        subscription.start();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeSubscription(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeSubscription(session.getId());
    }

    private void closeSubscription(String sessionId) {
        TailSubscription subscription = subscriptions.remove(sessionId);
        if (subscription != null) {
            subscription.close();
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst(name);
    }

    private final class TailSubscription implements AutoCloseable {
        private final WebSocketSession session;
        private final String file;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private WatchService watchService;
        private Future<?> future;
        private long offset;

        private TailSubscription(WebSocketSession session, String file) {
            this.session = session;
            this.file = file;
        }

        private void start() {
            future = executor.submit(this::run);
        }

        private void run() {
            try {
                Path logFile = logFileService.resolveReadableLogFile(file);
                Path watchDir = logFile.getParent();
                Path watchName = logFile.getFileName();
                if (watchDir == null || watchName == null) {
                    sendError("日志文件路径异常: " + file);
                    closeSession(CloseStatus.BAD_DATA);
                    return;
                }

                McpLogViewVO initial = logFileService.viewTail(file, null, INITIAL_LINES, null);
                offset = initial.getFileSize();
                send("tail", initial);

                watchService = watchDir.getFileSystem().newWatchService();
                watchDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                while (running.get() && session.isOpen()) {
                    WatchKey key = watchService.take();
                    boolean matched = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            matched = true;
                            continue;
                        }
                        Object context = event.context();
                        if (context instanceof Path changed
                                && Objects.equals(changed.getFileName(), watchName)) {
                            matched = true;
                        }
                    }
                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                    if (matched) {
                        pushIncrement();
                    }
                }
            } catch (ClosedWatchServiceException ignored) {
                // normal close
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("[log-ws] tail failed file={} session={} error={}",
                            file, session.getId(), e.getMessage());
                    sendError(e.getMessage());
                    closeSession(CloseStatus.SERVER_ERROR);
                }
            } finally {
                closeSubscription(session.getId());
            }
        }

        private void pushIncrement() throws IOException {
            long currentSize = Files.size(logFileService.resolveReadableLogFile(file));
            if (offset > 0 && currentSize < offset) {
                McpLogViewVO view = logFileService.viewTail(file, null, INCREMENT_LINES, null);
                offset = view.getFileSize();
                send("rotate", view);
                return;
            }

            McpLogViewVO view = logFileService.viewTail(file, null, INCREMENT_LINES, offset);
            offset = view.getFileSize();
            if (view.getLines() != null && !view.getLines().isEmpty()) {
                send("tail", view);
            }
        }

        private void send(String type, Object data) {
            if (!session.isOpen()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("data", data);
            try {
                session.sendMessage(new TextMessage(JsonUtil.toJson(payload)));
            } catch (IOException e) {
                close();
            }
        }

        private void sendError(String message) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", message == null ? "日志订阅失败" : message);
            send("error", data);
        }

        private void closeSession(CloseStatus status) {
            try {
                if (session.isOpen()) {
                    session.close(status);
                }
            } catch (IOException ignored) {
                // ignore
            }
        }

        @Override
        public void close() {
            running.set(false);
            if (future != null) {
                future.cancel(true);
            }
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}

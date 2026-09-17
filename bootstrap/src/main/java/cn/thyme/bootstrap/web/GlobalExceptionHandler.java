package cn.thyme.bootstrap.web;

import cn.thyme.agent.EmptyModelResponseException;
import cn.thyme.bootstrap.workspace.WorkspaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局异常处理：统一记录日志并返回结构化错误响应，取代散落在各 Controller 里的
 * {@code @ExceptionHandler}。
 *
 * <ul>
 *   <li>调用方可修正的问题（参数、沙箱约束）→ 400，记 WARN（不必打堆栈）</li>
 *   <li>其它未预期异常 → 500，记 ERROR（带完整堆栈，便于排查）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WorkspaceException.class)
    public ResponseEntity<Map<String, String>> onWorkspaceError(WorkspaceException e) {
        log.warn("沙箱操作被拒绝: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    /**
     * 目标资源不存在（如 PATCH 一个从未对话过的会话）。
     *
     * <p>必须显式处理：{@code NoSuchElementException} 继承自 {@code RuntimeException}，
     * 落到下面的兜底分支会变成 500，而这是调用方可以修正的问题。前端的乐观 UI
     * 也靠 404 决定回滚。</p>
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> onNotFound(NoSuchElementException e) {
        log.warn("目标不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /** 模型返回空响应：可重试，不是服务端 bug，记 WARN 并回 502。 */
    @ExceptionHandler(EmptyModelResponseException.class)
    public ResponseEntity<Map<String, String>> onEmptyModelResponse(EmptyModelResponseException e) {
        log.warn("模型返回空响应: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", "模型未返回任何内容，请重试"));
    }

    /**
     * 客户端在异步响应（SSE 流）写回之前就断开了。
     *
     * <p>这是容器对断连的通知，不是服务端故障：响应已经写不出去了。必须在这里
     * 悄悄收掉，否则会落到下面的兜底分支——它会尝试往一个
     * {@code text/event-stream} 响应里写 JSON 错误体，写不进去再抛一层
     * {@code HttpMessageNotWritableException}。结果是用户每刷新一次页面，日志里
     * 就多一坨 ERROR 堆栈，真正的故障反而被淹掉。</p>
     *
     * <p>返回 {@code void}：不写响应体，只把异常标记为已处理。</p>
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void onClientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，响应无法写出: {}", e.getMessage());
    }

    /** 兜底：未预期的异常一律记 ERROR（含堆栈）后返回 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnexpected(Exception e) {
        log.error("请求处理发生未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误，请稍后重试"));
    }
}

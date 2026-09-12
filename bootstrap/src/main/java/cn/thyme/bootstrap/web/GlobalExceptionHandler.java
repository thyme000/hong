package cn.thyme.bootstrap.web;

import cn.thyme.bootstrap.workspace.WorkspaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

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

    /** 兜底：未预期的异常一律记 ERROR（含堆栈）后返回 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnexpected(Exception e) {
        log.error("请求处理发生未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误，请稍后重试"));
    }
}

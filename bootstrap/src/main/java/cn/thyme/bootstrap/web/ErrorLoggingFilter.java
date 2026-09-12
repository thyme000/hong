package cn.thyme.bootstrap.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 异常兜底过滤器：捕获请求处理链中漏过 {@link GlobalExceptionHandler} 的异常
 * ——例如发生在 Filter 层的异常、响应已提交后抛出的异常等，这些不会进入
 * Controller 的异常处理流程，若不在此记录就会静默丢失。
 *
 * <p>仅记录日志，不吞异常：响应未提交时交还容器按其默认策略处理，
 * 已提交时无法再改响应，只能留痕。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ErrorLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error("请求处理链未捕获异常: {} {}", request.getMethod(), request.getRequestURI(), e);
            throw e;
        }
    }
}

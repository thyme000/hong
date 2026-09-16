package cn.thyme.bootstrap.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断连异常必须与「服务端真出错」分开处理。
 *
 * <p>不分开的后果不是用户看到 500（那时连接早断了，没人在看），而是日志：断连会落到
 * 兜底分支，而兜底分支要往一个 {@code text/event-stream} 响应里写 JSON 错误体，写不进去
 * 再抛一层，于是每刷新一次页面日志里就多一坨 ERROR 堆栈，把真正的故障淹掉。</p>
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {

        @GetMapping("/disconnected")
        public String disconnected() throws IOException {
            throw new AsyncRequestNotUsableException("客户端已断开");
        }

        @GetMapping("/broken")
        public String broken() {
            throw new IllegalStateException("真出错了");
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 断连异常应就地收掉而不是报500() throws Exception {
        // 处理器返回 void：标记为已处理，不再尝试写响应体
        mockMvc().perform(get("/disconnected"))
                .andExpect(status().isOk());
    }

    @Test
    void 其它未预期异常仍应报500() throws Exception {
        mockMvc().perform(get("/broken"))
                .andExpect(status().isInternalServerError());
    }
}

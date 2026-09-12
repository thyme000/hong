package cn.thyme.bootstrap;

import cn.thyme.agent.Agent;
import cn.thyme.agent.AgentContext;
import cn.thyme.agent.AgentResult;
import cn.thyme.bootstrap.workspace.SessionWorkspace;
import cn.thyme.tool.ToolContext;
import cn.thyme.tool.ToolManager;
import cn.thyme.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：验证 Spring 启动时装配的主 agent（hong）与文件工具可用。
 * 依赖所配模型提供商可用（启动时会做健康检查）。
 *
 * <p>数据源与沙箱都指向 target 下的独立位置，避免把测试数据写进开发用的
 * {@code data/memory.db} 与 {@code data/workspaces}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-agent.db",
        "tool.workspace.dir=./target/test-workspaces"
})
class MainAgentIntegrationTest {

    @Autowired
    private Agent mainAgent;

    @Autowired
    private ToolManager toolManager;

    @Autowired
    private SessionWorkspace workspace;

    @Test
    @DisplayName("主 agent 能响应任务，并回传会话 id")
    void mainAgentResponds() {
        AgentResult result = mainAgent.run(
                AgentContext.builder().task("用一句话回答：1+1 等于几？").build());

        System.out.println(">> 主 agent 响应: " + result.content());
        System.out.println(">> 轮次=" + result.iterations() + ", token=" + result.totalTokens());
        System.out.println(">> sessionId=" + result.sessionId());

        assertNotNull(result.content());
        assertFalse(result.content().isBlank());
        assertNotNull(result.sessionId(), "结果应回传会话 id，供前端接续同一会话");
    }

    @Test
    @DisplayName("文件工具能读取该会话沙箱内已上传的文件")
    void fileToolsWorkInSessionSandbox() {
        String sessionId = "test-session-" + System.nanoTime();
        workspace.save(sessionId, "a.txt", "十万个冷笑话".getBytes(StandardCharsets.UTF_8));

        ToolContext context = new ToolContext(sessionId);

        ToolResult listed = toolManager.execute("list_files", Map.of(), context);
        System.out.println(">> list_files 结果: " + listed.text());
        assertTrue(listed.ok());
        assertTrue(listed.text().contains("a.txt"));

        ToolResult read = toolManager.execute("read_file", Map.of("path", "a.txt"), context);
        System.out.println(">> read_file 结果: " + read.text());
        assertTrue(read.ok());
        assertTrue(read.text().contains("十万个冷笑话"));

        workspace.deleteSession(sessionId);
    }

    @Test
    @DisplayName("缺少会话上下文时读文件被拒绝")
    void readFileRequiresSessionContext() {
        ToolResult result = toolManager.execute("read_file", Map.of("path", "a.txt"));

        System.out.println(">> 无会话上下文读取: " + result.text());

        assertFalse(result.ok());
    }

    @Test
    @DisplayName("会话之间文件互相隔离")
    void sessionsAreIsolated() {
        String a = "iso-a-" + System.nanoTime();
        String b = "iso-b-" + System.nanoTime();
        workspace.save(a, "only-in-a.txt", "秘密".getBytes(StandardCharsets.UTF_8));

        ToolResult result = toolManager.execute("list_files", Map.of(), new ToolContext(b));

        System.out.println(">> 会话 b 的清单: " + result.text());
        assertTrue(result.ok());
        assertEquals("[]", result.text());

        workspace.deleteSession(a);
    }
}

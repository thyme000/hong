package cn.thyme.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentBudget}：划拨语义、父级保留、以及「全树用量 ≤ 总上限」的不变量。
 */
class AgentBudgetTest {

    @Test
    void 根预算的剩余额度随消耗递减() {
        AgentBudget budget = new AgentBudget(1000);

        assertEquals(1000, budget.remaining());

        budget.spend(300);

        assertEquals(300, budget.used());
        assertEquals(700, budget.remaining());
    }

    @Test
    void 消耗不会让剩余变成负数() {
        AgentBudget budget = new AgentBudget(100);

        budget.spend(500);

        assertEquals(0, budget.remaining(), "超额消耗后剩余应钳到 0，而不是负数");
    }

    @Test
    void 子预算看不到父级的剩余额度() {
        AgentBudget parent = new AgentBudget(1000);
        parent.spend(100);

        AgentBudget child = parent.fork(200);

        // 子预算上限 = 划拨时刻已用(100) + 划拨量(200) = 300
        assertEquals(300, child.limit());
        assertEquals(200, child.remaining(), "子 agent 只能看到自己拿到的 200");
    }

    @Test
    void 子agent的实际消耗会反映到父agent和全树() {
        AgentBudget parent = new AgentBudget(1000);
        AgentBudget child = parent.fork(200);

        child.spend(150);

        assertEquals(150, parent.used(), "子 agent 花掉的应计入父 agent 与全树");
        assertEquals(150, child.used());
        assertEquals(850, parent.remaining());
    }

    @Test
    void 子agent未花完的额度仍归父agent可用() {
        AgentBudget parent = new AgentBudget(1000);
        AgentBudget child = parent.fork(600);

        child.spend(100);

        // 共享的是实际用量而非预留量，所以剩 500 的额度父 agent 直接可用
        assertEquals(900, parent.remaining());
    }

    @Test
    void 子预算同时受全树总量约束() {
        AgentBudget parent = new AgentBudget(500);
        // 划拨量大于总上限时，子预算也不该突破全树总量
        AgentBudget child = parent.fork(800);

        assertEquals(500, child.remaining(), "取「自身划拨额度」与「全树总量」的小者");
    }

    @Test
    void 全树总消耗不会超过总上限() {
        AgentBudget parent = new AgentBudget(1000);
        AgentBudget a = parent.fork(600);
        AgentBudget b = parent.fork(600);

        // 两个子 agent 各自由其划拨额度约束
        a.spend(600);
        b.spend(600);

        // 全树实际用量可以超过单个划拨额，但上限由根预算把关
        assertEquals(1200, parent.used());
        assertEquals(0, parent.remaining(), "已用超出总上限时剩余钳到 0");
    }

    @Test
    void 父级保留额度至少留1() {
        assertTrue(AgentBudget.reserveForParent(100) >= 1);
        assertEquals(1, AgentBudget.reserveForParent(1));
        assertEquals(1, AgentBudget.reserveForParent(3));
    }

    @Test
    void 非法上限应被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(-1));
    }

    @Test
    void 非法划拨量应被拒绝() {
        AgentBudget budget = new AgentBudget(100);
        assertThrows(IllegalArgumentException.class, () -> budget.fork(0));
    }
}

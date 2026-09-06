package cn.thyme.tool.selector;

import cn.thyme.tool.TaskContext;
import cn.thyme.tool.ToolDescriptor;

import java.util.List;

/**
 * 确定性筛选：按域、作用、必需标签过滤，再截断到数量上限。
 */
public class DefaultToolSelector implements ToolSelector {

    @Override
    public List<ToolDescriptor> select(TaskContext task, List<ToolDescriptor> candidates) {
        return candidates.stream()
                .filter(d -> matchesCategory(task, d))
                .filter(d -> matchesAction(task, d))
                .filter(d -> matchesTags(task, d))
                .limit(Math.max(0, task.maxTools()))
                .toList();
    }

    private static boolean matchesCategory(TaskContext task, ToolDescriptor d) {
        return task.category() == null || task.category().equals(d.category());
    }

    private static boolean matchesAction(TaskContext task, ToolDescriptor d) {
        return task.action() == null || task.action().equals(d.action());
    }

    private static boolean matchesTags(TaskContext task, ToolDescriptor d) {
        return task.requiredTags().isEmpty() || d.tags().containsAll(task.requiredTags());
    }
}

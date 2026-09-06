package cn.thyme.tool.selector;

import cn.thyme.tool.TaskContext;
import cn.thyme.tool.ToolDescriptor;

import java.util.List;

/**
 * 工具选择策略：根据任务需求从候选工具中选出应使用的工具。
 * 默认实现做确定性筛选；未来可替换为 LLM 驱动的语义匹配。
 */
public interface ToolSelector {

    List<ToolDescriptor> select(TaskContext task, List<ToolDescriptor> candidates);
}

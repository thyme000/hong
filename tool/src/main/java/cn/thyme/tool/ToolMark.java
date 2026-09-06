package cn.thyme.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的标记注解，声明其所属域（category）、作用（action）与自由标签（tags）。
 * 运行时会被归一成 {@link ToolDescriptor} 的元数据，用于按任务筛选工具。
 * 内部工具可选地标注；MCP 工具的标记按服务器名默认归类、可在配置中覆盖。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolMark {

    /** 所属域，如 file、web、db。 */
    String category() default "general";

    /** 作用，如 read、write、execute、search。 */
    String action() default "invoke";

    /** 自由标签，如 retry、slow、idempotent。 */
    String[] tags() default {};
}

package cn.thyme.tool.security;

/**
 * 工具的能力：客观声明工具能做什么。工具作者据此声明，
 * 由安全策略根据能力 + 参数 + 环境动态评估风险并决策。
 */
public enum Capability {
    /** 读取文件。 */
    FILE_READ,
    /** 写入文件。 */
    FILE_WRITE,
    /** 删除文件。 */
    FILE_DELETE,
    /** 访问网络。 */
    NETWORK,
    /** 执行外部进程或命令。 */
    PROCESS_EXEC,
    /** 修改外部状态（数据库、配置、外部系统等）。 */
    STATE_MODIFY
}

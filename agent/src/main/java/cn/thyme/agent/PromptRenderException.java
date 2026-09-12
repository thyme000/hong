package cn.thyme.agent;

/**
 * 提示词模板渲染失败：文件读取失败或缺少必须的占位符变量。
 */
public class PromptRenderException extends RuntimeException {

    public PromptRenderException(String message) {
        super(message);
    }

    public PromptRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}

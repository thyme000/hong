package cn.thyme.agent;

/**
 * 模型返回了空响应：既没有工具调用，也没有任何文本内容。
 * 这通常是模型自身的问题（思考型模型偶尔会只输出思考过程），不是任务失败，
 * 但也不能当成正常结果——否则调用方会把空字符串误认为模型给的答案。
 */
public class EmptyModelResponseException extends RuntimeException {

    public EmptyModelResponseException(String message) {
        super(message);
    }
}

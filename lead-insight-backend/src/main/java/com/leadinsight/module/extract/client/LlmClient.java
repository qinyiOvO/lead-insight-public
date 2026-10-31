package com.leadinsight.module.extract.client;

/**
 * 大模型客户端抽象（T2-01，ADR-004 / ARCHITECTURE §2.8）。
 *
 * <p>接口在前，DeepSeek 只是实现之一；未来替换/新增厂商时不改业务代码。
 * 实现类负责：超时、错误码映射（429→限流、超时、HTTP 错误）与 key 的保密
 * （key 只出现在请求头，绝不允许进入异常消息与日志）。</p>
 */
public interface LlmClient {

    /**
     * 发起一次对话补全调用（同步阻塞）。
     *
     * @return 模型返回内容与 token 用量
     * @throws LlmCallException 调用失败（限流/超时/HTTP 错误），异常不含任何 key 信息
     */
    LlmChatResponse chat(LlmChatRequest request);
}

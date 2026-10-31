package com.leadinsight.module.extract.client;

import com.leadinsight.module.extract.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 客户端（T2-01，ADR-004 方案 A）。
 *
 * <p>OpenAI 兼容 {@code POST /chat/completions} + {@code response_format=json_object}；
 * HTTP 客户端用 Spring {@link RestClient}（Boot 3.5 内置，零额外依赖，ARCHITECTURE §2.8 锁定）。
 * 超时：连接 5s / 读取 60s（官方提示长连接可能 10 分钟才断，客户端必须自行限时）。</p>
 *
 * <p><b>key 零泄露：</b>key 只写入请求头；异常消息、日志一律不携带 key
 * （HTTP 错误体仅截取前 200 字符，且官方错误响应本身不含调用方密钥）。</p>
 */
@Slf4j
@Component
public class DeepSeekLlmClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int ERROR_SNIPPET_MAX = 200;

    private final LlmProperties properties;
    private final RestClient restClient;

    public DeepSeekLlmClient(LlmProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (properties.hasKey()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        this.restClient = builder.build();
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request) {
        Map<String, Object> payload = buildPayload(request);
        long start = System.currentTimeMillis();
        try {
            JsonNode body = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            LlmChatResponse response = toResponse(body);
            log.info("模型调用成功: model={} latencyMs={}", response.modelName(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            LlmCallException.FailureKind kind =
                    status == 429 ? LlmCallException.FailureKind.RATE_LIMITED
                                  : LlmCallException.FailureKind.ERROR;
            throw new LlmCallException(kind, status,
                    "模型调用失败 httpStatus=" + status + " body=" + snippet(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            LlmCallException.FailureKind kind = isTimeout(e)
                    ? LlmCallException.FailureKind.TIMEOUT
                    : LlmCallException.FailureKind.ERROR;
            throw new LlmCallException(kind, null,
                    (isTimeout(e) ? "模型调用超时" : "模型调用网络错误") + ": " + rootMessage(e));
        } catch (RestClientException e) {
            // N-5 补口：读体/消息转换阶段的超时不会包成 ResourceAccessException，而是裸
            // RestClientException（实测链：RestClientException → SocketTimeoutException "Read
            // timed out"）。不下钻原因链会漏接 TIMEOUT 分类，裸异常穿透编排层（重试/熔断/降级
            // 分类全部失效）。RestClientResponseException 是其子类，已被上方 catch 优先接住。
            LlmCallException.FailureKind kind = isTimeout(e)
                    ? LlmCallException.FailureKind.TIMEOUT
                    : LlmCallException.FailureKind.ERROR;
            throw new LlmCallException(kind, null,
                    (isTimeout(e) ? "模型调用超时" : "模型调用网络错误") + ": " + rootMessage(e));
        }
    }

    /** 下钻完整原因链判断是否超时（连接/读取）；直接 cause 与深层 cause 均能识别 */
    private static boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * OpenAI 兼容请求体。JSON Output 硬性约束：提示词含 "json" 字样与格式示例（由外置模板保证）、
     * max_tokens 足够大防截断；temperature 取低值提升抽取确定性；非思考模式（不传任何思考开关参数，
     * 避免依赖未文档化字段）。
     */
    private Map<String, Object> buildPayload(LlmChatRequest request) {
        return Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", request.prompt())),
                "max_tokens", request.maxTokens(),
                "temperature", 0.1,
                "stream", false,
                "response_format", Map.of("type", "json_object"));
    }

    private LlmChatResponse toResponse(JsonNode body) {
        if (body == null || body.path("choices").isEmpty()) {
            // 官方已知缺陷：JSON Output 偶发返回空内容 —— 返回空 content，由解析器按失败处理并重试
            log.warn("模型返回内容为空（官方已知偶发缺陷），交由解析器按失败处理");
            return new LlmChatResponse("", null, null, null, properties.getModel());
        }
        JsonNode choice = body.path("choices").get(0);
        String content = choice.path("message").path("content").asText("");
        JsonNode usage = body.path("usage");
        return new LlmChatResponse(content,
                intValueOrNull(usage, "prompt_tokens"),
                intValueOrNull(usage, "completion_tokens"),
                intValueOrNull(usage, "total_tokens"),
                body.path("model").asText(properties.getModel()));
    }

    private Integer intValueOrNull(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }

    private String snippet(String raw) {
        if (raw == null) {
            return "";
        }
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_SNIPPET_MAX ? flat : flat.substring(0, ERROR_SNIPPET_MAX);
    }

    private String rootMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg;
    }
}

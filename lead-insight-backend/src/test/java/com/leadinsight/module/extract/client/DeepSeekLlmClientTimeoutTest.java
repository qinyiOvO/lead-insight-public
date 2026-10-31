package com.leadinsight.module.extract.client;

import com.leadinsight.module.extract.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * N-5 超时防护单测（走查发现：LLM 调用若不限时，病态输入会长期占死 llmExtractExecutor 线程）。
 *
 * <p>用 JDK 内置 HttpServer 起本地假端点（每个用例自注册处理器）：先打一通正常调用证明
 * 假端点可达（防止超时用例因"连接被拒"误绿——ConnectException 也被归为 TIMEOUT），
 * 再打慢端点验证读取超时按 {@link LlmCallException.FailureKind#TIMEOUT} 分类抛出，
 * 驱动既有失败路径（重试 → 熔断 → DEGRADED/FAILED），不新增旁路、不改生产代码。</p>
 */
class DeepSeekLlmClientTimeoutTest {

    private static final String CHAT_BODY =
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"model\":\"deepseek-flash\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    private static final String CHAT_PATH = "/chat/completions";

    private HttpServer server;
    private int port;

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newSingleThreadExecutor());
    }

    @AfterEach
    void stopFakeServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void registerHandler(long sleepMs) {
        server.createContext(CHAT_PATH, exchange -> {
            respond(exchange, sleepMs);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, long sleepMs) throws IOException {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        byte[] body = CHAT_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private DeepSeekLlmClient clientWithReadTimeout(int readTimeoutMs) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(readTimeoutMs);
        return new DeepSeekLlmClient(properties);
    }

    @Test
    void fastEndpoint_returnsResponse_provesFakeServerReachable() {
        // 防误绿锚点：假端点可达且能正常返回（否则下方超时用例可能因连接失败而假通过）
        registerHandler(0);
        LlmChatResponse response = clientWithReadTimeout(5000)
                .chat(new LlmChatRequest("test prompt with json", 64));
        assertNotNull(response);
        assertEquals("deepseek-flash", response.modelName());
    }

    @Test
    void slowEndpoint_readTimeout_mapsToTimeoutFailureKind() {
        // 挂起 2s >> 读超时 300ms：必须在读取超时处中断并按 TIMEOUT 分类，
        // 绝不允许线程无限期挂起（N-5：病态输入曾挂起 >19min 无终态、0 调用日志）
        registerHandler(2000);
        LlmCallException ex = assertThrows(LlmCallException.class,
                () -> clientWithReadTimeout(300).chat(new LlmChatRequest("test prompt with json", 64)));
        assertEquals(LlmCallException.FailureKind.TIMEOUT, ex.getKind(),
                "读超时必须分类为 TIMEOUT（驱动既有重试→熔断→DEGRADED/FAILED 路径）");
        assertNotNull(ex.getMessage());
    }
}

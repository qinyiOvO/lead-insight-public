package com.leadinsight.module.extract.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 智能层配置（前缀 llm，ADR-004 / ADR-005）。
 *
 * <p><b>无 key 铁律：</b>{@code api-key} 默认取 {@code ${DEEPSEEK_API_KEY:}}（可为空）。
 * key 为空时应用必须完整启动，粘贴抽取走 DEGRADED 降级路径，
 * 绝不阻塞启动、绝不抛堆栈。key 只存在于环境变量或 application-local.yml（已 gitignore），
 * 禁止写入任何提交文件、日志与测试。</p>
 *
 * <p><b>模型白名单（成本红线）：</b>{@code model} 只允许 {@link #ALLOWED_MODELS}，
 * 启动阶段即校验，配成其他模型（如高价 pro）直接启动失败，避免误配导致成本失控。
 * 该校验只针对 model，与 api-key 是否为空完全无关，不破坏上述无 key 铁律。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 允许的模型白名单：用户硬规则——只准 flash，禁用 pro 等高价模型（成本红线） */
    private static final Set<String> ALLOWED_MODELS = Set.of("deepseek-flash");

    /** OpenAI 兼容 Base URL（DeepSeek 官方：https://api.deepseek.com） */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key；为空 = 无 key 模式（降级） */
    private String apiKey = "";

    /** 模型名可配置，但受白名单约束（见 ALLOWED_MODELS，成本红线只准 flash） */
    private String model = "deepseek-flash";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 读超时（毫秒）；模型生成较慢，官方提示长连接可能 10 分钟才断，客户端须自行限时 */
    private int readTimeoutMs = 60000;

    private Extract extract = new Extract();
    private RateLimit rateLimit = new RateLimit();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Quota quota = new Quota();

    /** 抽取参数 */
    @Data
    public static class Extract {
        /** 总尝试上限（含首次），R-1 裁定 3 = 首次 1 + 重试 2；retry_count 取值域 0..2 */
        private int maxAttempts = 3;
        /** max_tokens 需足够大，避免 JSON Output 被截断 */
        private int maxTokens = 2048;
    }

    /** 令牌桶限流参数（进程内，M3 才接 Redis） */
    @Data
    public static class RateLimit {
        /** 桶容量（突发上限） */
        private long capacity = 10;
        /** 每秒补充令牌数（持续速率） */
        private double refillPerSecond = 1.0;
    }

    /** 熔断参数（进程内状态机） */
    @Data
    public static class CircuitBreaker {
        /** 连续失败达到该次数即熔断 */
        private int failureThreshold = 5;
        /** 熔断持续秒数，期内不调用模型 */
        private long openSeconds = 300;
    }

    /** 每日调用总量上限参数（成本红线；DeepSeek 是预付费余额制，余额即最终上限，
     *  本配额是应用层早停，与只封速率的令牌桶互补） */
    @Data
    public static class Quota {
        /** 每个账户每个自然日允许的抽取次数（1 次 = 1 条线索，最多再带 2 次重试）。
         *  默认 10（2026-10-31 用户口径：原 500 次/日过高，收紧为 10 次/日/账户）。
         *  按<b>账户</b>计数，不是全站计数——不同账户互不挤占。
         *  设为 0 或负数 = 不限制。 */
        private int dailyLimit = 10;

        /** 用满 {@link #dailyLimit} 后，该账户<b>及其来源 IP</b> 被禁止消耗模型额度的天数（默认 7 天）。
         *  封禁记录落库（表 llm_quota_ban），重启不清零；到期自动解除。
         *  设为 0 或负数 = 用满只当天拒绝、不封禁。 */
        private int banDays = 7;

        /** 是否对本机/内网来源 IP 也落封禁。默认 false：开发演示与反代转发都是 127.0.0.1，
         *  若封它会把整台机器（含其他账户）锁死一周。账户封禁不受该开关影响。 */
        private boolean banLocalIp = false;

        /** 封该 IP 前，要求同一 IP 上「已被封禁的不同账户数」达到的阈值（默认 3）。
         *  1 = 用满即封该 IP（旧行为）；0 或负数 = 永不封 IP，只封账户。
         *  设为 3 的用意：区分「一个人开多个号刷」（会被封）与「一栋楼共用一个出口 NAT」
         *  （一人失控不牵连其他人）。 */
        private int ipBanMinAccounts = 3;
    }

    /** 是否已配置 API Key */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 启动阶段校验模型白名单，确保配错时应用直接启动失败而非运行期才发现。
     * 注意：只校验 model，与 apiKey 是否为空无关——无 key 模式下依然照常启动。
     */
    @PostConstruct
    void validateOnStartup() {
        validateModel();
    }

    /**
     * 模型白名单校验（用户硬规则：只准 deepseek-flash，禁用 pro）。
     * 启动即校验，配成其他模型直接启动失败，避免误配导致成本失控。
     * 异常信息不含任何密钥内容。
     */
    public void validateModel() {
        String m = model == null ? "" : model.trim();
        if (!ALLOWED_MODELS.contains(m)) {
            throw new IllegalStateException(
                    "【模型白名单】llm.model=\"" + m + "\" 不被允许。本项目只准使用 " + ALLOWED_MODELS
                            + "（禁用 deepseek-v4-pro 等高价模型，属成本红线）。"
                            + "请把 application.yml 的 llm.model 改回 deepseek-flash；"
                            + "若官方确实改名了，需同步修改 LlmProperties.ALLOWED_MODELS 后再启动。");
        }
    }
}

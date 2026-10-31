package com.leadinsight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 优先级评分权重配置（ARCHITECTURE-v1.1 §5.2）。
 * 前缀 lead.scoring，改 YAML 即生效，无需改代码。
 *
 * <p>每项规则的 weight 与 max_score 同值（文档中「默认权重」与「上限」两列取值一致），
 * item_score 恒被 max_score 封顶。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "lead.scoring")
public class ScoringProperties {

    private Intent intent = new Intent();
    private Budget budget = new Budget();
    private Urgency urgency = new Urgency();
    private Company company = new Company();
    private Keyword keyword = new Keyword();
    private Level level = new Level();

    /** 规则 1：意图类别，上限 40 */
    @Data
    public static class Intent {
        private int maxScore = 40;
        private int cooperation = 40;
        private int quote = 35;
        private int consult = 20;
        private int complaint = 15;
        private int irrelevant = 0;
    }

    /** 规则 2：预算区间，上限 20 */
    @Data
    public static class Budget {
        private int maxScore = 20;
        private BigDecimal amountHigh = new BigDecimal("500000");
        private int scoreHigh = 20;
        private BigDecimal amountMiddle = new BigDecimal("100000");
        private int scoreMiddle = 15;
        private BigDecimal amountLow = new BigDecimal("10000");
        private int scoreLow = 8;
        private int scoreNone = 0;
    }

    /** 规则 3：期望时间紧迫度，上限 20 */
    @Data
    public static class Urgency {
        private int maxScore = 20;
        private int daysUrgent = 7;
        private int scoreUrgent = 20;
        private int daysSoon = 30;
        private int scoreSoon = 12;
        private int daysNormal = 90;
        private int scoreNormal = 5;
        private int scoreNone = 0;
    }

    /** 规则 4：公司规模，上限 10 */
    @Data
    public static class Company {
        private int maxScore = 10;
        private int large = 10;
        private int medium = 6;
        private int none = 0;
    }

    /** 规则 5：业务关键词命中，上限 10 */
    @Data
    public static class Keyword {
        private int maxScore = 10;
        private int perHit = 2;
        private List<String> words = new ArrayList<>();
    }

    /** 优先级等级阈值：>=high 为 HIGH；>=medium 为 MEDIUM；其余 LOW */
    @Data
    public static class Level {
        private int high = 70;
        private int medium = 40;
    }
}

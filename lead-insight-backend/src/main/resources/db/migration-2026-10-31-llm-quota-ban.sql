-- =============================================================================
-- 增量脚本（2026-10-31）：新增 llm_quota_ban —— 模型额度封禁表（账户 + 来源 IP）
--
-- 背景：抽取额度从「全站每日 500 次」改为「每账户每日 10 次；用满即封禁该账户
--       与其来源 IP 一周」，封禁记录必须跨重启存活，故新增本表。
--
-- 适用场景：库里已有数据、**不能**重跑 schema-v1.1.sql（那个脚本含 8 条 DROP TABLE）。
-- 本脚本只做一件事：CREATE TABLE IF NOT EXISTS，不改不删任何既有表与数据。
--
-- 用法（必须用 -D 指定库，脚本内刻意不写 USE：写死库名曾造成「清错库」）：
--   mysql -u root -p --default-character-set=utf8mb4 -D lead_insight \
--         < src/main/resources/db/migration-2026-10-31-llm-quota-ban.sql
--
-- 幂等：重复执行安全（IF NOT EXISTS）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `llm_quota_ban` (
    `ban_key`       VARCHAR(64)     NOT NULL                COMMENT '封禁键：USER:<账户ID> 或 IP:<地址>',
    `subject_type`  VARCHAR(10)     NOT NULL                COMMENT '对象类型：USER=账户, IP=来源地址',
    `subject_value` VARCHAR(48)     NOT NULL                COMMENT '对象值(账户ID或IP字面量)，便于人工排查',
    `banned_until`  DATETIME        NOT NULL                COMMENT '封禁截止时间，超过该时刻自动解除',
    `daily_used`    INT             NOT NULL DEFAULT 0      COMMENT '触发封禁当日已用抽取次数（留痕核对用）',
    `reason`        VARCHAR(200)    NOT NULL DEFAULT ''     COMMENT '触发原因摘要（不含密钥与邮件正文）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`ban_key`),
    KEY `idx_lqb_banned_until` (`banned_until`),
    KEY `idx_lqb_subject_type` (`subject_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模型额度封禁表（账户/IP）';

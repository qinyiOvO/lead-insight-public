-- =============================================================================
-- 增量迁移：llm_quota_ban 增加 trigger_ip（2026-10-31）
--
-- 背景：原本「账户用满 7 天」会连带把来源 IP 一起封。共享出口（公司/校园 NAT、
-- 同一 Wi-Fi）下，一个人的失控会让整片出口的所有账户失联。
-- 改为：账户照旧封 7 天；IP 只有在其上「已被封禁的不同账户数」达到
-- llm.quota.ip-ban-min-accounts（默认 3）时才封。
-- 判定依赖本列 —— USER 行记录触发时的来源 IP，用于按 IP 聚合计数。
--
-- 幂等：列已存在则跳过（可重复执行）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 lead_insight < 本文件
-- =============================================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'llm_quota_ban'
      AND column_name = 'trigger_ip'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `llm_quota_ban`
        ADD COLUMN `trigger_ip` VARCHAR(45) DEFAULT NULL COMMENT ''触发该账户封禁的来源 IP（IP 行留空）'',
        ADD KEY `idx_lqb_trigger_ip` (`trigger_ip`)',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT IF(@col_exists = 0, 'trigger_ip 已添加', 'trigger_ip 已存在，跳过') AS result;

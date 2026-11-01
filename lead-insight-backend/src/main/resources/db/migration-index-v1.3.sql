-- =============================================================================
-- 索引优化迁移脚本 v1.3
-- 依据：docs/02-架构/SQL优化方案-v1.0.md（§7 批次 B2）与 docs/decisions/ADR-009-sql-index-tuning.md
-- 目标库：lead_insight（脚本不含 USE / CREATE DATABASE，执行时显式指定库）
-- 全部语句为 Online DDL（ALGORITHM=INPLACE, LOCK=NONE），不阻塞读写
--
-- 变更规模：3 新增 / 3 重建 / 7 删除 = 13 条 ALTER
-- 执行顺序：先建 → 确认生效 → 后删，任何时刻不出现"查询无索引可用"的窗口
--
-- 【实测注意】idx_lead_created_at 的列序与 SQL优化方案附录 B 原稿不同：
--   原稿为 (deleted_at, created_at, id)，实测在 1 万行压测库上会使看板聚合从 6.8ms 退化到 58ms（8.6 倍）。
--   原因：deleted_at 打头会让优化器把 WHERE deleted_at IS NULL 当作 ref=const 高选择性条件
--         （估算 4864 行 / 实际 10000 行，因基数仅 1），诱导其选本索引并大量回表。
--   本脚本改为 (created_at, id, deleted_at)：前两列仍可供给 ORDER BY created_at DESC, id DESC，
--   deleted_at 退居末位仅供索引条件下推。详见 ADR-009。
-- =============================================================================

-- ---------- 第一步：新增（先加后删） ----------

-- lead：工作台"我的线索"按优先级分页；最左 owner_id 同时承担 fk_lead_owner 对子表索引的要求
ALTER TABLE `lead`
  ADD INDEX `idx_lead_owner_priority`
      (`owner_id`, `deleted_at`, `priority_score` DESC, `created_at` ASC, `id` ASC),
  ALGORITHM=INPLACE, LOCK=NONE;

-- lead_follow_up：成交趋势覆盖索引（等值 to_status → 分组列 lead_id → 取 MIN(created_at)）
ALTER TABLE `lead_follow_up`
  ADD INDEX `idx_follow_won` (`to_status`, `lead_id`, `created_at`),
  ALGORITHM=INPLACE, LOCK=NONE;

-- llm_quota_ban：以单条复合索引取代 index merge，三列对应对封禁计数的等值+范围
ALTER TABLE `llm_quota_ban`
  ADD INDEX `idx_lqb_type_ip_until` (`subject_type`, `trigger_ip`, `banned_until`),
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------- 第二步：重建失配索引（同一条 ALTER 内 DROP + ADD，原子切换） ----------

ALTER TABLE `lead`
  DROP INDEX `idx_lead_status_priority`,
  ADD INDEX `idx_lead_status_priority`
      (`status`, `deleted_at`, `priority_score` DESC, `created_at` ASC, `id` ASC),
  ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE `lead`
  DROP INDEX `idx_lead_created_at`,
  ADD INDEX `idx_lead_created_at` (`created_at`, `id`, `deleted_at`),
  ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE `lead`
  DROP INDEX `idx_lead_status_created`,
  ADD INDEX `idx_lead_status_created` (`status`, `deleted_at`, `created_at`, `id`),
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------- 第三步：确认上面全部生效后，再删冗余（7 条） ----------

ALTER TABLE `lead`              DROP INDEX `idx_lead_owner`,              ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `lead`              DROP INDEX `idx_lead_intent_created`,     ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `lead_score_detail` DROP INDEX `idx_score_lead`,              ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `import_task_item`  DROP INDEX `idx_import_task_item_status`, ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `import_task`       DROP INDEX `idx_import_task_status`,      ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `llm_quota_ban`     DROP INDEX `idx_lqb_trigger_ip`,          ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE `llm_quota_ban`     DROP INDEX `idx_lqb_subject_type`,        ALGORITHM=INPLACE, LOCK=NONE;

-- ---------- 第四步：刷新统计信息，让优化器立即用上新索引 ----------
ANALYZE TABLE `lead`, `lead_follow_up`, `llm_quota_ban`;


-- =============================================================================
-- 反向（回滚）：按顺序执行即可恢复到 v1.2 索引状态
--
-- 【注意】v1.2 实库中 idx_lead_status_priority 的真实定义是 (status, priority_score, id) 全升序，
--         SQL优化方案附录 B 原稿写的 (status, priority_score DESC, id DESC) 与实库不符，此处按实库还原。
-- =============================================================================
-- ALTER TABLE `llm_quota_ban` ADD INDEX `idx_lqb_subject_type` (`subject_type`), ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `llm_quota_ban` ADD INDEX `idx_lqb_trigger_ip` (`trigger_ip`),   ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `llm_quota_ban` DROP INDEX `idx_lqb_type_ip_until`,             ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `import_task`       ADD INDEX `idx_import_task_status` (`status`),                 ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `import_task_item`  ADD INDEX `idx_import_task_item_status` (`item_status`),       ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead_score_detail` ADD INDEX `idx_score_lead` (`lead_id`),                        ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` ADD INDEX `idx_lead_intent_created` (`intent_category`, `created_at`),       ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` ADD INDEX `idx_lead_owner` (`owner_id`),                                   ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead_follow_up` DROP INDEX `idx_follow_won`,                                     ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` DROP INDEX `idx_lead_status_created`,
--   ADD INDEX `idx_lead_status_created` (`status`, `created_at`),                               ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` DROP INDEX `idx_lead_created_at`,
--   ADD INDEX `idx_lead_created_at` (`created_at`),                                             ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` DROP INDEX `idx_lead_status_priority`,
--   ADD INDEX `idx_lead_status_priority` (`status`, `priority_score`, `id`),                     ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `lead` DROP INDEX `idx_lead_owner_priority`,                                      ALGORITHM=INPLACE, LOCK=NONE;
-- ANALYZE TABLE `lead`, `lead_follow_up`, `llm_quota_ban`;

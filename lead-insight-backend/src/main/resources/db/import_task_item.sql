-- =============================================================================
-- import_task_item  批量导入任务逐条明细表（T3-01 亮点2）
-- 状态：后端先行落地版——待架构师 schema/ER 升 v1.2 回填后对齐（批复① DDL 归属）。
-- 设计要点：
--   1. 刻意不存条目原文：lead.original_text 已有全文，明细只留结果，避免大文本冗余；
--   2. (task_id, seq) 唯一键保证每任务每提交序号仅一条明细，seq 从 1 起；
--   3. lead_id 随 lead 行删除置 NULL（SET NULL），任务行删除级联清明细（CASCADE）。
--   4. 脚本首行先 DROP 本表：本脚本定位为可独立执行的建表脚本，重跑必须幂等
--      （原先裸 CREATE 重复执行会报 ERROR 3730 table already exists）。
--      注意：本表对 import_task / lead 有外键，需先于二者创建（执行顺序在 schema 之后）。
-- =============================================================================

DROP TABLE IF EXISTS `import_task_item`;

CREATE TABLE `import_task_item` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属任务ID，关联 import_task.id',
    `seq`           INT             NOT NULL                COMMENT '提交顺序号，从 1 起',
    `item_status`   VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '条目状态：PENDING=待处理, RUNNING=处理中, SUCCESS=成功产线索, DUPLICATE=指纹命中跳过, FAILED=失败转人工(不产线索), DEGRADED=降级产线索(lead_id 有值, openapi 1.3.1)',
    `lead_id`       BIGINT UNSIGNED NULL                    COMMENT '产出的线索ID(SUCCESS/DEGRADED 有值)，关联 lead.id',
    `customer_name` VARCHAR(100)    NULL                    COMMENT '解析出的客户名，失败可能为空',
    `reason`        VARCHAR(500)    NULL                    COMMENT '说明文案：DUPLICATE=已存在线索提示(含线索id), FAILED=失败原因, DEGRADED=降级说明',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_task_item_seq` (`task_id`, `seq`),
    KEY `idx_import_task_item_status` (`item_status`),
    KEY `idx_import_task_item_lead` (`lead_id`),
    CONSTRAINT `fk_import_task_item_task`
        FOREIGN KEY (`task_id`) REFERENCES `import_task` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_import_task_item_lead`
        FOREIGN KEY (`lead_id`) REFERENCES `lead` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '批量导入任务逐条明细表';

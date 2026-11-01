-- =============================================================================
-- 企业销售线索智能识别与跟进优先级排序系统  数据库建表脚本
-- 文件: schema-v1.1.sql
-- 版本: v1.1
-- 数据库: MySQL 8.0.35
-- 字符集: utf8mb4 / utf8mb4_0900_ai_ci
-- 说明: 本脚本可直接在 MySQL 8.0+ 上执行，包含 7 张业务表与初始化演示账号。
--       所有字段均带中文注释；外键显式声明 ON DELETE 行为。
-- 编码: 本脚本为 UTF-8 编码。导入时须显式指定连接字符集为 utf8mb4。
-- 执行方式(命令行):
--   mysql -u root -p --default-character-set=utf8mb4 < schema-v1.1.sql
--   [重要] Windows 下 mysql 客户端默认字符集常为 gbk，若不加 --default-character-set=utf8mb4，
--   客户端会以 gbk 解析 UTF-8 中文，把中文后的单引号当作多字节字符的尾字节吞掉，字符串因此不闭合，
--   报 "ERROR 1406 (22001) Data too long for column 'real_name' at row 1" 或出现中文乱码（与列宽无关）。
-- 执行方式(DataGrip / IDEA): 打开本文件整体执行；在连接属性(Advanced)中把 charset 设为 utf8mb4
--   （DataGrip 默认多为 UTF-8，仍需确认）。
-- 通用提醒: 本项目所有 SQL 文件均为 UTF-8 编码，导入时一律显式指定连接字符集 utf8mb4。
-- =============================================================================

-- 若数据库已存在同名库请先评估，避免误删生产库。
CREATE DATABASE IF NOT EXISTS `lead_insight`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `lead_insight`;

-- 按外键依赖逆序删除，便于重复执行（开发期重建）。
-- import_task_item 由独立脚本 db/import_task_item.sql 创建，对 lead / import_task 均有外键，
-- 是两者的依赖方，必须排在整个 DROP 段最前（先删依赖方，否则删 lead / import_task 时被外键挡住）。
DROP TABLE IF EXISTS `import_task_item`;
DROP TABLE IF EXISTS `llm_quota_ban`;
DROP TABLE IF EXISTS `model_call_log`;
DROP TABLE IF EXISTS `lead_follow_up`;
DROP TABLE IF EXISTS `lead_score_detail`;
DROP TABLE IF EXISTS `lead_extract_result`;
DROP TABLE IF EXISTS `lead`;
DROP TABLE IF EXISTS `import_task`;
DROP TABLE IF EXISTS `sys_user`;


-- -----------------------------------------------------------------------------
-- 1. sys_user  系统用户表
--    角色仅两类：SPECIALIST(销售专员) / SUPERVISOR(销售主管)
-- -----------------------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`       VARCHAR(50)     NOT NULL                 COMMENT '登录账号，唯一',
    `password`       VARCHAR(100)    NOT NULL                 COMMENT 'BCrypt 密码哈希(60位)',
    `real_name`      VARCHAR(50)     NOT NULL DEFAULT ''      COMMENT '姓名/显示名',
    `role`           VARCHAR(20)     NOT NULL                 COMMENT '角色：SPECIALIST=销售专员, SUPERVISOR=销售主管',
    `status`         TINYINT         NOT NULL DEFAULT 1       COMMENT '账号状态：1=启用, 0=禁用',
    `last_login_at`  DATETIME        NULL                     COMMENT '最近一次登录时间',
    `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统用户表';


-- -----------------------------------------------------------------------------
-- 2. import_task  批量导入任务表
--    支撑亮点2：批量邮件异步任务与进度反馈。单条录入不建任务记录。
-- -----------------------------------------------------------------------------
CREATE TABLE `import_task` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_no`         VARCHAR(40)     NOT NULL                 COMMENT '任务编号(业务唯一,如 IMPT+时间戳)',
    `file_name`       VARCHAR(255)    NULL                     COMMENT '上传文件名(批量导入时有值)',
    `import_source`   VARCHAR(20)     NOT NULL DEFAULT 'BATCH' COMMENT '导入来源：BATCH=批量粘贴, FILE=文本文件导入(前端读取文本后复用同一接口，后端不接收文件本体)',
    `total_count`     INT             NOT NULL DEFAULT 0       COMMENT '待处理邮件总条数',
    `processed_count` INT             NOT NULL DEFAULT 0       COMMENT '已处理条数(成功+失败+去重)',
    `success_count`   INT             NOT NULL DEFAULT 0       COMMENT '成功生成线索条数',
    `duplicate_count` INT             NOT NULL DEFAULT 0       COMMENT '指纹去重命中条数',
    `fail_count`      INT             NOT NULL DEFAULT 0       COMMENT '处理失败条数',
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING=待执行, RUNNING=执行中, SUCCESS=全部完成, PARTIAL=部分失败, FAILED=整体失败',
    `created_by`      BIGINT UNSIGNED NULL                     COMMENT '创建人(销售专员)ID，关联 sys_user.id',
    `error_message`   VARCHAR(500)    NULL                     COMMENT '整体失败原因摘要',
    `started_at`      DATETIME        NULL                     COMMENT '开始处理时间',
    `finished_at`     DATETIME        NULL                     COMMENT '处理完成时间',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_task_no` (`task_no`),
    -- v1.3：原 idx_import_task_status 删除（status 仅作 WHERE id=? AND status=? 的附加条件，前导为主键）
    KEY `idx_import_task_created_by` (`created_by`),
    KEY `idx_import_task_created_at` (`created_at`),
    CONSTRAINT `fk_import_task_user`
        FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '批量导入任务表';


-- -----------------------------------------------------------------------------
-- 3. lead  线索表（核心聚合表）
--    存放"生效字段"（供列表展示与优先级排序；人工修正后以本表为准）+
--    内容指纹（亮点3：幂等去重，唯一索引）+ 状态机字段（状态机约束）。
-- -----------------------------------------------------------------------------
CREATE TABLE `lead` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `content_fingerprint` CHAR(64)        NOT NULL                 COMMENT '邮件正文归一化后的SHA-256十六进制指纹(去重唯一键)',
    `subject_title`      VARCHAR(255)    NULL                     COMMENT '邮件主题(可选)',
    `original_text`      MEDIUMTEXT      NOT NULL                 COMMENT '客户邮件原始正文(归一化前全文)',
    `customer_name`      VARCHAR(100)    NULL                     COMMENT '客户名称(联系人姓名)',
    `company_name`       VARCHAR(150)    NULL                     COMMENT '所属公司名称',
    `contact_info`       VARCHAR(150)    NULL                     COMMENT '联系方式(电话/邮箱，抽取自正文，可为空)',
    `intent_category`    VARCHAR(20)     NULL                     COMMENT '意图类别(内容属性)：CONSULT=咨询, QUOTE=报价, COOPERATION=合作, COMPLAINT=投诉, IRRELEVANT=无关(广告/垃圾/自动回复)',
    `extract_confidence` DECIMAL(5,4)    NULL                     COMMENT '抽取结果整体置信度(0-1)，由字段完整性与原文可回溯率自算(非模型输出)：HIGH=0.9000, MEDIUM=0.7000, LOW=0.4000',
    `demand_summary`     VARCHAR(500)    NULL                     COMMENT '需求要点(模型摘要)',
    `budget_amount`      DECIMAL(14,2)   NULL                     COMMENT '预算金额(元)，无法解析为数值时为空',
    `budget_text`        VARCHAR(100)    NULL                     COMMENT '预算原文表述(用于展示与人工核对)',
    `expected_time`      DATE            NULL                     COMMENT '期望时间(规范化日期，用于紧迫度评分)',
    `expected_time_text` VARCHAR(100)    NULL                     COMMENT '期望时间原文表述',
    `company_scale`      VARCHAR(20)     NULL                     COMMENT '公司规模：LARGE=大型, MEDIUM=中型, SMALL=小型, UNKNOWN=未知',
    `keywords_hit`       VARCHAR(500)    NULL                     COMMENT '命中的业务关键词(英文逗号分隔，用于评分依据展示)',
    `priority_score`     INT             NOT NULL DEFAULT 0        COMMENT '优先级总分(0-100，各规则得分求和)',
    `priority_level`     VARCHAR(10)     NOT NULL DEFAULT 'LOW'    COMMENT '优先级等级：HIGH=高, MEDIUM=中, LOW=低',
    `score_reason`       VARCHAR(1000)   NULL                     COMMENT '评分依据文本(可解释性，逐项拼接)',
    `status`             VARCHAR(20)     NOT NULL DEFAULT 'NEW'    COMMENT '线索状态(跟进结论)：NEW=新线索, FOLLOWING=跟进中, WON=已成交, INVALID=无效(终态,语义=不再跟进)',
    `extract_status`     VARCHAR(20)     NOT NULL DEFAULT 'WAITING' COMMENT '抽取状态：WAITING=待抽取, PROCESSING=抽取中, SUCCESS=解析成功, FAILED=解析失败转人工, DEGRADED=模型不可用降级',
    `confidence_level`   VARCHAR(10)     NULL                     COMMENT '抽取置信度档位：HIGH/MEDIUM/LOW，与 extract_confidence 一一对应(HIGH=0.9000,MEDIUM=0.7000,LOW=0.4000)；NULL=未抽取/降级/未评估，仅在 extract_status=SUCCESS 时写入',
    `owner_id`           BIGINT UNSIGNED NULL                     COMMENT '认领/负责的销售专员ID，关联 sys_user.id',
    `claimed_at`         DATETIME        NULL                     COMMENT '认领时间',
    `import_task_id`     BIGINT UNSIGNED NULL                     COMMENT '所属导入任务ID(批量导入时有值)，关联 import_task.id',
    `import_source`      VARCHAR(20)     NOT NULL DEFAULT 'SINGLE' COMMENT '录入来源：SINGLE=单条粘贴, BATCH=批量导入',
    `remark`             VARCHAR(500)    NULL                     COMMENT '备注(人工补充)',
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at`         DATETIME        NULL                     COMMENT '逻辑删除时间(NULL=未删除)，MyBatis-Plus @TableLogic',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lead_fingerprint` (`content_fingerprint`),
    -- v1.3 索引优化（ADR-009）：列序与实现口径 ORDER BY priority_score DESC, created_at ASC, id ASC 逐键对齐；
    -- 原 idx_lead_owner / idx_lead_intent_created 删除（由本组索引最左前缀承担）
    KEY `idx_lead_owner_priority`  (`owner_id`, `deleted_at`, `priority_score` DESC, `created_at` ASC, `id` ASC),
    KEY `idx_lead_status_priority` (`status`, `deleted_at`, `priority_score` DESC, `created_at` ASC, `id` ASC),
    KEY `idx_lead_status_created`  (`status`, `deleted_at`, `created_at`, `id`),
    KEY `idx_lead_intent_category` (`intent_category`),
    -- deleted_at 置于末位：置于首位会使优化器对 WHERE deleted_at IS NULL 产生 ref=const 假象，诱导大表上大量回表
    KEY `idx_lead_created_at` (`created_at`, `id`, `deleted_at`),
    KEY `idx_lead_import_task` (`import_task_id`),
    KEY `idx_lead_extract_status` (`extract_status`),
    KEY `idx_lead_confidence` (`confidence_level`),
    CONSTRAINT `fk_lead_owner`
        FOREIGN KEY (`owner_id`) REFERENCES `sys_user` (`id`) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_lead_import_task`
        FOREIGN KEY (`import_task_id`) REFERENCES `import_task` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '销售线索表(核心聚合)';


-- -----------------------------------------------------------------------------
-- 4. lead_extract_result  抽取结果表（与 lead 一对一）
--    支撑亮点1：提示模板 + 结构化输出校验。存放模型原始返回与解析元数据，
--    与 lead 的"生效字段"分离：lead 供查询排序，本表留痕原始输出与重试信息。
-- -----------------------------------------------------------------------------
CREATE TABLE `lead_extract_result` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `lead_id`          BIGINT UNSIGNED NOT NULL                 COMMENT '关联线索ID，关联 lead.id',
    `raw_response`     MEDIUMTEXT      NULL                     COMMENT '模型原始返回文本(未加工全文)',
    `parsed_json`      JSON            NULL                     COMMENT '解析并校验通过后的结构化JSON',
    `parse_status`     VARCHAR(20)     NOT NULL                 COMMENT '解析结果：SUCCESS=成功, FAILED=重试后仍失败转人工, DEGRADED=模型不可用未调用',
    `retry_count`      TINYINT         NOT NULL DEFAULT 0       COMMENT '解析重试次数(取值域 0..2；总尝试上限 3 次=首次1+重试2)',
    `error_message`    VARCHAR(500)    NULL                     COMMENT '失败原因摘要(如JSON截断/字段缺失/类型不符)',
    `parser_version`   VARCHAR(20)     NOT NULL DEFAULT 'v1'    COMMENT '结构化解析器版本',
    `prompt_version`   VARCHAR(20)     NOT NULL DEFAULT 'v1'    COMMENT '提示模板版本',
    `model_name`       VARCHAR(50)     NULL                     COMMENT '实际调用的模型名称',
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_extract_lead` (`lead_id`),
    CONSTRAINT `fk_extract_lead`
        FOREIGN KEY (`lead_id`) REFERENCES `lead` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '线索抽取结果表';


-- -----------------------------------------------------------------------------
-- 5. lead_score_detail  评分明细表（与 lead 一对多：一条线索每个评分项一行）
--    支撑优先级评分可解释性：优先展示、论文可论证每分来源。
-- -----------------------------------------------------------------------------
CREATE TABLE `lead_score_detail` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `lead_id`    BIGINT UNSIGNED NOT NULL                 COMMENT '关联线索ID，关联 lead.id',
    `rule_code`  VARCHAR(30)     NOT NULL                 COMMENT '评分项编码：INTENT=意图类别, BUDGET=预算区间, URGENCY=时间紧迫度, COMPANY=公司规模, KEYWORD=关键词命中',
    `rule_name`  VARCHAR(50)     NOT NULL                 COMMENT '评分项名称(中文)',
    `raw_value`  VARCHAR(200)    NULL                     COMMENT '该项原始取值(如"报价"/"50万以上")',
    `weight`     INT             NOT NULL DEFAULT 0       COMMENT '该项权重(可配置)',
    `item_score` INT             NOT NULL DEFAULT 0       COMMENT '该项实际得分',
    `max_score`  INT             NOT NULL DEFAULT 0       COMMENT '该项分值上限',
    `reason`     VARCHAR(300)    NULL                     COMMENT '该项评分依据说明',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_score_lead_rule` (`lead_id`, `rule_code`),
    -- v1.3：原 idx_score_lead (lead_id) 删除，由 uk_score_lead_rule 最左列承担
    CONSTRAINT `fk_score_lead`
        FOREIGN KEY (`lead_id`) REFERENCES `lead` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '线索评分明细表';


-- -----------------------------------------------------------------------------
-- 6. lead_follow_up  跟进记录表（与 lead 一对多）
--    记录跟进留痕与状态变更轨迹。
-- -----------------------------------------------------------------------------
CREATE TABLE `lead_follow_up` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `lead_id`     BIGINT UNSIGNED NOT NULL                 COMMENT '关联线索ID，关联 lead.id',
    `user_id`     BIGINT UNSIGNED NULL                     COMMENT '跟进人ID，关联 sys_user.id',
    `follow_type` VARCHAR(20)     NOT NULL DEFAULT 'NOTE'  COMMENT '跟进方式：NOTE=记录, CALL=电话, EMAIL=邮件, VISIT=拜访, OTHER=其他',
    `content`     VARCHAR(1000)   NOT NULL                 COMMENT '跟进内容',
    `from_status` VARCHAR(20)     NULL                     COMMENT '本次操作前的线索状态',
    `to_status`   VARCHAR(20)     NULL                     COMMENT '本次操作后的线索状态(仅状态变更时填写)',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_follow_lead_created` (`lead_id`, `created_at`),
    -- v1.3：成交趋势覆盖索引（等值 to_status → 分组列 lead_id → MIN(created_at)）
    KEY `idx_follow_won` (`to_status`, `lead_id`, `created_at`),
    KEY `idx_follow_user` (`user_id`),
    CONSTRAINT `fk_follow_lead`
        FOREIGN KEY (`lead_id`) REFERENCES `lead` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_follow_user`
        FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '线索跟进记录表';


-- -----------------------------------------------------------------------------
-- 7. model_call_log  模型调用日志表
--    支撑亮点4：限流与失败降级。记录每次大模型调用的耗时、状态、token 与降级标记，
--    为限流观测、降级判定与论文"有效性验证"提供数据支撑。
-- -----------------------------------------------------------------------------
CREATE TABLE `model_call_log` (
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `lead_id`             BIGINT UNSIGNED NULL                     COMMENT '关联线索ID(单条抽取场景)，关联 lead.id',
    `import_task_id`      BIGINT UNSIGNED NULL                     COMMENT '关联导入任务ID(批量场景)，关联 import_task.id',
    `scene`               VARCHAR(30)     NOT NULL                 COMMENT '调用场景：EXTRACT=线索抽取, CLASSIFY=意图判定',
    `model_name`          VARCHAR(50)     NOT NULL                 COMMENT '调用的模型名称',
    `request_fingerprint` CHAR(64)        NULL                     COMMENT '请求内容SHA-256指纹(便于统计重复请求/缓存命中)',
    `http_status`         INT             NULL                     COMMENT 'HTTP响应状态码(如200/429/503)',
    `call_result`         VARCHAR(20)     NOT NULL                 COMMENT '调用结果：SUCCESS=成功, RATE_LIMITED=被限流, TIMEOUT=超时, ERROR=接口错误, PARSE_FAIL=解析失败',
    `prompt_tokens`       INT             NULL                     COMMENT '输入token数',
    `completion_tokens`   INT             NULL                     COMMENT '输出token数',
    `total_tokens`        INT             NULL                     COMMENT '总token数',
    `latency_ms`          INT             NULL                     COMMENT '调用耗时(毫秒)',
    `retry_no`            TINYINT         NOT NULL DEFAULT 0       COMMENT '第几次重试(0=首次)',
    `degraded`            TINYINT         NOT NULL DEFAULT 0       COMMENT '是否触发降级：1=是, 0=否',
    `error_code`          VARCHAR(50)     NULL                     COMMENT '错误码(平台返回或自定义)',
    `error_message`       VARCHAR(500)    NULL                     COMMENT '错误信息摘要',
    `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_mcl_created_at` (`created_at`),
    KEY `idx_mcl_call_result` (`call_result`),
    KEY `idx_mcl_lead` (`lead_id`),
    KEY `idx_mcl_import_task` (`import_task_id`),
    CONSTRAINT `fk_mcl_lead`
        FOREIGN KEY (`lead_id`) REFERENCES `lead` (`id`) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_mcl_import_task`
        FOREIGN KEY (`import_task_id`) REFERENCES `import_task` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '大模型调用日志表';


-- -----------------------------------------------------------------------------
-- 7. llm_quota_ban  额度封禁表（成本红线，2026-10-31 新增）
--    用途：某账户当日抽取额度用满（llm.quota.daily-limit，默认 10 次）后，
--          禁止「该账户」与「触发时的来源 IP」继续消耗模型额度 llm.quota.ban-days 天（默认 7 天）。
--    为什么同时封 IP：账户可共用、可换，只封账户时换个号就能继续刷。
--    为什么落库：封禁必须跨进程重启存活，否则「重启即解封」使红线失效。
--    主键为前缀化键（USER:<账户ID> / IP:<地址>）：两种对象共存一张表，查询与清理只有一套逻辑。
--    生命周期：一对象一行；到期由守卫就地清理，不做历史留痕（每次封禁动作另有 WARN 日志可查）。
--    trigger_ip（2026-10-31 新增）：USER 行记录触发时的来源 IP，用于「同 IP 被封账户数达到阈值才封 IP」，
--    以区分「一个人开多号刷」与「一栋楼共用一个出口 NAT」。
-- -----------------------------------------------------------------------------
CREATE TABLE `llm_quota_ban` (
    `ban_key`       VARCHAR(64)     NOT NULL                COMMENT '封禁键：USER:<账户ID> 或 IP:<地址>',
    `subject_type`  VARCHAR(10)     NOT NULL                COMMENT '对象类型：USER=账户, IP=来源地址',
    `subject_value` VARCHAR(48)     NOT NULL                COMMENT '对象值(账户ID或IP字面量)，便于人工排查',
    `banned_until`  DATETIME        NOT NULL                COMMENT '封禁截止时间，超过该时刻自动解除',
    `daily_used`    INT             NOT NULL DEFAULT 0      COMMENT '触发封禁当日已用抽取次数（留痕核对用）',
    `reason`        VARCHAR(200)    NOT NULL DEFAULT ''     COMMENT '触发原因摘要（不含密钥与邮件正文）',
    `trigger_ip`    VARCHAR(45)              DEFAULT NULL   COMMENT '触发该账户封禁的来源 IP（IP 行留空）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`ban_key`),
    KEY `idx_lqb_banned_until` (`banned_until`),
    -- v1.3：原 idx_lqb_subject_type / idx_lqb_trigger_ip 合并为本三列索引，与封禁计数的等值+范围逐列对齐
    KEY `idx_lqb_type_ip_until` (`subject_type`, `trigger_ip`, `banned_until`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模型额度封禁表（账户/IP）';


-- =============================================================================
-- 演示账号不再由本脚本写入（2026-10-31 变更）
-- 原实现在此处 INSERT 两个账号的 BCrypt 哈希，等价于把演示口令事实上公开在仓库里
-- ——任何人读仓库即可登录演示站。现改为由后端首次启动时按配置创建：
--   com.leadinsight.config.DataInitializer（幂等，已存在则跳过，不覆盖既有口令）
-- 口令来源：application-local.yml 的 app.seed.* 或环境变量 SEED_*_PASSWORD（仓库不提供默认值）。
-- 这样建库脚本本身不含任何口令，公开仓库里也不存在可用账号。
-- =============================================================================

-- =============================================================================
-- 脚本结束
-- =============================================================================

-- =============================================================================
-- 脚本结束
-- =============================================================================

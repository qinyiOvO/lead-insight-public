package com.leadinsight.module.lead.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售线索（表 lead，核心聚合）。字段与 schema-v1.1.sql 一一对应。
 *
 * <p>注意：LEAD 是 MySQL 8.0 的保留字（窗口函数 LEAD()），
 * 故 @TableName 必须写反引号，否则 MyBatis-Plus 生成的
 * {@code INSERT INTO lead (...)} 会报 SQL 语法错误。</p>
 *
 * <p>逻辑删除用 DATETIME 型标记 deleted_at：@TableLogic 保证所有查询自动附加
 * deleted_at IS NULL 条件；写侧删除走 LeadMapper.softDeleteByIdAndOwner 的显式 SQL，
 * 不依赖 MyBatis-Plus 对 delval 的字符串拼装（避免把 now() 当成字符串字面量）。</p>
 */
@Data
@TableName("`lead`")
public class Lead {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮件正文归一化后的 SHA-256 十六进制指纹（唯一键 uk_lead_fingerprint） */
    private String contentFingerprint;

    private String subjectTitle;

    /** 客户邮件原始正文（归一化前全文） */
    private String originalText;

    private String customerName;

    private String companyName;

    private String contactInfo;

    /** 意图类别：CONSULT / QUOTE / COOPERATION / COMPLAINT / IRRELEVANT */
    private String intentCategory;

    /** 抽取整体置信度（0-1），模型不输出，由系统自算；R1 手工录入为 null */
    private BigDecimal extractConfidence;

    private String demandSummary;

    private BigDecimal budgetAmount;

    private String budgetText;

    private LocalDate expectedTime;

    private String expectedTimeText;

    /** 公司规模：LARGE / MEDIUM / SMALL / UNKNOWN */
    private String companyScale;

    /** 命中的业务关键词（英文逗号分隔） */
    private String keywordsHit;

    private Integer priorityScore;

    /** HIGH / MEDIUM / LOW */
    private String priorityLevel;

    private String scoreReason;

    /** 线索状态：NEW / FOLLOWING / WON / INVALID */
    private String status;

    /** WAITING / PROCESSING / SUCCESS / FAILED / DEGRADED */
    private String extractStatus;

    /** HIGH / MEDIUM / LOW；NULL=未抽取/降级/未评估，仅 SUCCESS 时写入 */
    private String confidenceLevel;

    private Long ownerId;

    /** 创建人（销售专员）ID；未认领线索仅创建本人可操作（DEF-01/02/03 访问守卫，PRD §3.3「自己创建或已认领」） */
    private Long createdBy;

    private LocalDateTime claimedAt;

    private Long importTaskId;

    /** SINGLE / BATCH */
    private String importSource;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除时间（NULL=未删除） */
    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}

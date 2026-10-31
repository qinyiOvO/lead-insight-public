package com.leadinsight.module.importtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量导入任务（表 import_task，schema-v1.1 §2）。字段与 DDL 一一对应。
 */
@Data
@TableName("import_task")
public class ImportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号（业务唯一，uk_import_task_no），如 IMPT20260215xxxx */
    private String taskNo;

    /** 上传文件名；openapi BatchImportRequest 无 fileName 字段，FILE 来源当前恒为 null（契约缺口，不擅自扩参） */
    private String fileName;

    /** BATCH=批量粘贴, FILE=文本文件导入（前端读文本后复用同一接口） */
    private String importSource;

    /** 待处理邮件总条数（空白段过滤后的条数） */
    private Integer totalCount;

    /** 已处理条数（成功+失败+去重） */
    private Integer processedCount;

    /** 成功生成线索条数 */
    private Integer successCount;

    /** 指纹去重命中条数 */
    private Integer duplicateCount;

    /** 转人工条数（DEGRADED：线索已入库但抽取 FAILED/降级，需人工处理；DEF-04 从 failCount 拆出） */
    private Integer manualCount;

    /** 处理失败条数（仅 FAILED 条目即未产线索的创建异常；不含转人工，DEF-04 口径收紧） */
    private Integer failCount;

    /** PENDING / RUNNING / SUCCESS / PARTIAL / FAILED */
    private String status;

    /** 创建人（销售专员）ID，关联 sys_user.id */
    private Long createdBy;

    /** 整体失败原因摘要 */
    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

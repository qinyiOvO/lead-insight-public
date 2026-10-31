package com.leadinsight.module.importtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量导入任务逐条明细（表 import_task_item，DDL 见 resources/db/import_task_item.sql，
 * 待架构师 schema v1.2 回填后对齐）。
 *
 * <p>刻意不存原文文本：条目正文由任务创建时在内存中传递给异步执行器（segments），
 * 线索表 lead.original_text 已有全文，明细表只留结果，避免大文本冗余。</p>
 */
@Data
@TableName("import_task_item")
public class ImportTaskItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 ID，关联 import_task.id（FK ON DELETE CASCADE） */
    private Long taskId;

    /** 提交顺序号，从 1 起（uk_import_task_item_seq: task_id + seq） */
    private Integer seq;

    /** PENDING / RUNNING / SUCCESS / DUPLICATE / FAILED / DEGRADED */
    private String itemStatus;

    /** 产出的线索 ID；SUCCESS/DEGRADED 有值（两态均产线索）；DUPLICATE/FAILED 为 null */
    private Long leadId;

    /** 解析出的客户名，失败可能为 null */
    private String customerName;

    /** DUPLICATE/FAILED/DEGRADED 时的说明文案 */
    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

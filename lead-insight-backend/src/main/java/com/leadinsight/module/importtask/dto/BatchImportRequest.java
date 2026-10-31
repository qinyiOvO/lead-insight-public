package com.leadinsight.module.importtask.dto;

import com.leadinsight.module.importtask.enums.ImportSource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建批量导入任务入参（openapi 1.3.1 BatchImportRequest）。
 *
 * <p>契约只有 importSource 与 textList 两个字段——FILE 来源由前端读文本后提交，
 * 后端不接收文件本体，故无 fileName 字段（契约缺口，import_task.file_name 保持 null）。</p>
 *
 * <p>业务上限 200 条按「空白段过滤后」的数量在 Service 层校验（批复②）：
 * DTO 层 {@code @Size(max = 1000)} 只是防滥用硬上限，不承担业务口径。</p>
 */
@Data
public class BatchImportRequest {

    /** 导入来源；BATCH=批量粘贴，FILE=前端读取的文本文件 */
    private ImportSource importSource;

    /** 待导入的邮件文本列表（每段一封邮件，空白段由后端过滤、不计入 total） */
    @NotEmpty(message = "textList 不能为空")
    @Size(max = 1000, message = "单次提交条数超过系统硬上限")
    private List<String> textList;

    /** 导入来源，null 时按契约默认 BATCH */
    public ImportSource sourceOrDefault() {
        return importSource == null ? ImportSource.BATCH : importSource;
    }
}

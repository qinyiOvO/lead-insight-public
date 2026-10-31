package com.leadinsight.module.importtask.enums;

/**
 * 导入来源（openapi BatchImportRequest.importSource）。
 *
 * <p>BATCH=批量粘贴；FILE=前端读取的文本文件（前端 FileReader 读成文本后复用同一接口，
 * 后端不接收文件本体，R-12 方案）。与 lead.import_source 的 SINGLE/BATCH 口径不同：
 * 本枚举只描述批量任务的来源，FILE 来源产出的线索落库时仍写 BATCH。</p>
 */
public enum ImportSource {

    BATCH,
    FILE
}

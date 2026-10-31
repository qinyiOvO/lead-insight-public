package com.leadinsight.module.importtask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.importtask.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 批量导入任务 Mapper。只做数据读写，不含业务逻辑。
 */
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {
}

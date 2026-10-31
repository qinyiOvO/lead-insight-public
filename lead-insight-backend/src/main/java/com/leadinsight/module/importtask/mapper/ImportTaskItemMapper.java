package com.leadinsight.module.importtask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.importtask.entity.ImportTaskItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 批量导入逐条明细 Mapper。只做数据读写，不含业务逻辑。
 */
@Mapper
public interface ImportTaskItemMapper extends BaseMapper<ImportTaskItem> {
}

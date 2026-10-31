package com.leadinsight.module.extract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.extract.entity.ModelCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型调用日志数据访问。只做数据存取，不含业务规则判断。
 */
@Mapper
public interface ModelCallLogMapper extends BaseMapper<ModelCallLog> {
}

package com.leadinsight.module.extract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.extract.entity.LeadExtractResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 抽取结果数据访问。只做数据存取，不含业务规则判断。
 */
@Mapper
public interface LeadExtractResultMapper extends BaseMapper<LeadExtractResult> {
}

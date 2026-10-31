package com.leadinsight.module.lead.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.lead.entity.LeadScoreDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评分明细数据访问。
 */
@Mapper
public interface LeadScoreDetailMapper extends BaseMapper<LeadScoreDetail> {

    /** 重算前先删后插，保证唯一键 (lead_id, rule_code) 不被冲突 */
    @Delete("DELETE FROM `lead_score_detail` WHERE `lead_id` = #{leadId}")
    int deleteByLeadId(@Param("leadId") Long leadId);
}

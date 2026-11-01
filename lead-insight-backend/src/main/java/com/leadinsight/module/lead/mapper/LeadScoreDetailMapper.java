package com.leadinsight.module.lead.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.lead.entity.LeadScoreDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评分明细数据访问。
 */
@Mapper
public interface LeadScoreDetailMapper extends BaseMapper<LeadScoreDetail> {

    /** 重算前先删后插，保证唯一键 (lead_id, rule_code) 不被冲突 */
    @Delete("DELETE FROM `lead_score_detail` WHERE `lead_id` = #{leadId}")
    int deleteByLeadId(@Param("leadId") Long leadId);

    /**
     * 批量写入评分明细（P-09 / §5.2-Q08b）：N 行一次往返，替代 N 次单行 {@code INSERT}。
     *
     * <p>仍保留"先 DELETE 再整批 INSERT"的口径：评分项由 YAML 配置驱动，配置项减少时
     * 纯 upsert 会残留旧规则的明细行，导致「明细之和 ≠ 总分」。</p>
     *
     * @param details 待写入明细，调用方保证非空（空集合会生成非法 SQL）
     * @return 影响行数
     */
    @Insert("<script>"
            + "INSERT INTO `lead_score_detail` "
            + "(`lead_id`, `rule_code`, `rule_name`, `raw_value`, `weight`, `item_score`, `max_score`, `reason`) VALUES "
            + "<foreach collection='list' item='d' separator=','>"
            + "(#{d.leadId}, #{d.ruleCode}, #{d.ruleName}, #{d.rawValue}, #{d.weight}, "
            + "#{d.itemScore}, #{d.maxScore}, #{d.reason})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<LeadScoreDetail> details);
}

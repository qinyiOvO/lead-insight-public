package com.leadinsight.module.lead.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.lead.entity.Lead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * 线索数据访问。只做数据存取，不含业务规则判断。
 */
@Mapper
public interface LeadMapper extends BaseMapper<Lead> {

    /**
     * 逻辑删除（带归属条件的并发防护）。显式 SQL：不依赖 MyBatis-Plus 对 delval="now()" 的字符串拼装，
     * 避免生成 deleted_at='now()' 这类会被 MySQL 视为非法日期的语句。
     * 条件 deleted_at IS NULL 保证幂等（重复删除影响行数为 0）；
     * 条件 {@code owner_id IS NULL OR owner_id = #{ownerId}} 与「可操作性」口径一致，
     * 与并发认领交错（线索已被他人认领）时影响行数为 0，由调用方判定为并发失败。
     */
    @Update("UPDATE `lead` SET `deleted_at` = now(), `updated_at` = now() "
            + "WHERE `id` = #{id} AND `deleted_at` IS NULL "
            + "AND (`owner_id` IS NULL OR `owner_id` = #{ownerId})")
    int softDeleteByIdAndOwner(@Param("id") Long id, @Param("ownerId") Long ownerId);

    /**
     * 按内容指纹查线索主键，<b>包含已逻辑删除的行</b>。
     * 唯一索引 uk_lead_fingerprint 不含 deleted_at，已删除线索依然占用指纹，
     * 故去重预查必须与索引口径一致，否则会漏报重复并让唯一键冲突暴露为 500。
     *
     * <p>只投影 {@code id}：调用方（{@code LeadDedupServiceImpl}）仅用主键拼提示文案，
     * 原 {@code SELECT *} 会为拿一个 id 读出整封邮件正文（MEDIUMTEXT），属无效 IO。
     * 返回 {@code null} 即表示指纹不存在。WHERE 口径不变。</p>
     */
    @Select("SELECT `id` FROM `lead` WHERE `content_fingerprint` = #{fingerprint} LIMIT 1")
    Long selectIdByFingerprintIncludingDeleted(@Param("fingerprint") String fingerprint);

    /**
     * 看板总览：6 条独立 {@code COUNT} 合并为 1 条条件聚合（P-05 / §5.2-Q13）。
     *
     * <p>口径与原实现逐项一致：{@code COUNT(*)} 对应 total；{@code status} 四个状态各一项；
     * 待人工= {@code extract_status IN ('FAILED','DEGRADED')}。{@code @TableLogic} 的
     * {@code deleted_at IS NULL} 在此显式写明（裸 {@code @Select} 不经过逻辑删除注入）。</p>
     *
     * <p>{@code SUM(cond)} 在空表上返回 NULL 而非 0，故用 {@code COALESCE} 归零；
     * 调用方仍按"取不到即 0"兜底，避免看板显示空白。</p>
     *
     * @return 单行 Map：total / invalid_count / won_count / new_count / following_count / pending_manual
     */
    @Select("SELECT COUNT(*) AS total, "
            + "COALESCE(SUM(`status` = 'INVALID'), 0) AS invalid_count, "
            + "COALESCE(SUM(`status` = 'WON'), 0) AS won_count, "
            + "COALESCE(SUM(`status` = 'NEW'), 0) AS new_count, "
            + "COALESCE(SUM(`status` = 'FOLLOWING'), 0) AS following_count, "
            + "COALESCE(SUM(`extract_status` IN ('FAILED','DEGRADED')), 0) AS pending_manual "
            + "FROM `lead` WHERE `deleted_at` IS NULL")
    Map<String, Object> selectOverviewCounts();

    /**
     * 抽取结果精确列更新（P-07 / §5.3-Q12）：只写抽取与状态相关的 17 列，
     * 取代 {@code updateById(lead)} 的全列回写。
     *
     * <p><b>永不写入</b>：{@code content_fingerprint}（由原文决定）、{@code original_text}（原文不可改）、
     * {@code created_by} / {@code import_task_id} / {@code import_source}（审计事实）、{@code created_at}。
     * 评分字段 {@code priority_score} / {@code priority_level} / {@code score_reason} 由
     * {@code ScoringServiceImpl.updateLeadScoreFields} 单独更新，<b>不并入本方法</b>，避免一次动作写两遍。</p>
     *
     * <p><b>收益（可观测性）</b>：列集合固定后，同一业务动作永远下发同一段 SQL 文本，
     * {@code performance_schema} 能合并为一个 digest，慢查询排行与 Top SQL 统计才看得懂；
     * 服务端可复用同一 prepared statement，执行计划选择也稳定。</p>
     *
     * <p><b>与 updateById 的两处差异（有意为之）</b>：</p>
     * <ol>
     *   <li>{@code updateById} 只更新非 null 字段，本方法无条件赋全部 17 列——重抽取场景下
     *       本次未抽出的字段会被置空（以本次抽取结果为准）。新建场景两者完全等价（插入时这些列本为空）。</li>
     *   <li>裸 {@code @Update} 不经过 {@code @TableLogic} 注入，故 {@code deleted_at IS NULL}
     *       必须显式书写，否则会更新已逻辑删除的行。</li>
     * </ol>
     */
    @Update("UPDATE `lead` SET "
            + "`subject_title` = #{e.subjectTitle}, `customer_name` = #{e.customerName}, "
            + "`company_name` = #{e.companyName}, `contact_info` = #{e.contactInfo}, "
            + "`intent_category` = #{e.intentCategory}, `extract_confidence` = #{e.extractConfidence}, "
            + "`demand_summary` = #{e.demandSummary}, `budget_amount` = #{e.budgetAmount}, "
            + "`budget_text` = #{e.budgetText}, `expected_time` = #{e.expectedTime}, "
            + "`expected_time_text` = #{e.expectedTimeText}, `company_scale` = #{e.companyScale}, "
            + "`keywords_hit` = #{e.keywordsHit}, `status` = #{e.status}, "
            + "`extract_status` = #{e.extractStatus}, `confidence_level` = #{e.confidenceLevel}, "
            + "`updated_at` = now() "
            + "WHERE `id` = #{e.id} AND `deleted_at` IS NULL")
    int updateExtractFields(@Param("e") Lead lead);

    /**
     * 抽取受理：只把 {@code extract_status} 置为 WAITING 并刷新 {@code updated_at}（§5.3）。
     *
     * <p>原实现走 {@code update(lead, wrapper)}，会把实体所有非 null 字段一并回写——
     * 重抽取时要为此多维护 6 棵索引，而实际只有 1 列变化。</p>
     *
     * <p>归属条件 {@code owner_id IS NULL OR owner_id = #{userId}} 与「可操作性」口径一致：
     * 与并发认领交错时影响行数为 0，由调用方判定为并发失败（返回值语义不变）。</p>
     */
    @Update("UPDATE `lead` SET `extract_status` = #{status}, `updated_at` = now() "
            + "WHERE `id` = #{id} AND `deleted_at` IS NULL "
            + "AND (`owner_id` IS NULL OR `owner_id` = #{userId})")
    int updateExtractStatusGuarded(@Param("id") Long id,
                                   @Param("status") String status,
                                   @Param("userId") Long userId);
}

package com.leadinsight.module.extract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.extract.entity.LlmQuotaBan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 账户额度封禁记录数据访问。只做数据存取，不含业务规则判断。
 */
@Mapper
public interface LlmQuotaBanMapper extends BaseMapper<LlmQuotaBan> {

    /**
     * 统计「当前仍处于封禁期、且由该 IP 触发」的<b>不同账户</b>数量。
     *
     * <p>用途：判定要不要封这个 IP。达到阈值（默认 3）才封——一个出口 NAT 后面
     * 可能坐着几十个同事，一个人的失控不该让所有人连坐；而「同一个人开 3 个号刷」必须被拦住，
     * 这两种情况在本查询下正好可区分。</p>
     *
     * <p>用 {@code DISTINCT subject_value} 而不是 {@code COUNT(*)}：同一账户反复触发只算一个人。</p>
     */
    @Select("SELECT COUNT(DISTINCT subject_value) FROM llm_quota_ban "
            + "WHERE subject_type = 'USER' AND trigger_ip = #{ip} AND banned_until > #{now}")
    int countBannedAccountsFromIp(@Param("ip") String ip, @Param("now") LocalDateTime now);
}

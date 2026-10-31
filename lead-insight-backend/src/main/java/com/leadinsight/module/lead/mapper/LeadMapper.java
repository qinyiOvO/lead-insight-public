package com.leadinsight.module.lead.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.lead.entity.Lead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
     * 按内容指纹查线索，<b>包含已逻辑删除的行</b>。
     * 唯一索引 uk_lead_fingerprint 不含 deleted_at，已删除线索依然占用指纹，
     * 故去重预查必须与索引口径一致，否则会漏报重复并让唯一键冲突暴露为 500。
     */
    @Select("SELECT * FROM `lead` WHERE `content_fingerprint` = #{fingerprint} LIMIT 1")
    Lead selectByFingerprintIncludingDeleted(@Param("fingerprint") String fingerprint);
}

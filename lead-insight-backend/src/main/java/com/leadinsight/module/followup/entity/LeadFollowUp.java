package com.leadinsight.module.followup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 线索跟进记录（表 lead_follow_up，与 lead 一对多）。
 */
@Data
@TableName("lead_follow_up")
public class LeadFollowUp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    /** 跟进人 ID（关联 sys_user.id，可为空） */
    private Long userId;

    /** 跟进方式：NOTE / CALL / EMAIL / VISIT / OTHER */
    private String followType;

    private String content;

    /** 本次操作前的线索状态（非状态变更时为空） */
    private String fromStatus;

    /** 本次操作后的线索状态（仅状态变更时填写） */
    private String toStatus;

    private LocalDateTime createdAt;
}

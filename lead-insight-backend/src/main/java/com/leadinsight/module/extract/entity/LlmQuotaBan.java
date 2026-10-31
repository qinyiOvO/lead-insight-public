package com.leadinsight.module.extract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 额度封禁记录（表 llm_quota_ban，成本红线）。
 *
 * <p>语义：某账户当日抽取额度用满后，<b>该账户与触发时的来源 IP</b>一并被禁止消耗模型额度
 * {@code llm.quota.ban-days} 天（默认 7 天）。同时封 IP 的原因：账户可共用/可换，
 * 只封账户时换个账号即可继续刷，挡不住真正的滥用。</p>
 *
 * <p><b>为什么落库而非纯内存：</b>进程重启必须不能让封禁失效，否则「重启即解封」使红线形同虚设。</p>
 *
 * <p>一行一个封禁对象，主键为前缀化键（{@code USER:<账户ID>} / {@code IP:<地址>}），
 * 到期由守卫就地清理；每次封禁动作另有 WARN 日志可查。</p>
 */
@Data
@TableName("llm_quota_ban")
public class LlmQuotaBan {

    /** 封禁键：USER:12 / IP:203.0.113.7 */
    @TableId(value = "ban_key", type = IdType.INPUT)
    private String banKey;

    /** 对象类型：USER=账户, IP=来源地址 */
    private String subjectType;

    /** 对象值：账户 ID 或 IP 字面量（便于人工排查，不存任何隐私数据） */
    private String subjectValue;

    /** 封禁截止时间；超过该时刻即自动解除 */
    private LocalDateTime bannedUntil;

    /** 触发封禁当日已用次数（便于事后核对是否确为用满） */
    private Integer dailyUsed;

    /** 触发原因摘要（不含任何密钥或邮件正文） */
    private String reason;

    /**
     * 触发本次封禁的来源 IP（仅 USER 行有值，IP 行自己就是主体、留空）。
     *
     * <p><b>为什么记这个：</b>封 IP 是"城门失火"式操作——同一出口下的别人会被牵连。
     * 改为「同一 IP 上有 ≥N 个账户被用满才封该 IP」后，就需要能按 IP 反查
     * "这个出口已经拖了几个账户下水"，否则分不清「一个人多号」和「一栋楼共用一个 NAT」。</p>
     */
    private String triggerIp;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

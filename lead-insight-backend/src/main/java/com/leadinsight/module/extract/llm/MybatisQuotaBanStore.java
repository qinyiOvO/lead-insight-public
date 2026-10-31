package com.leadinsight.module.extract.llm;

import com.leadinsight.module.extract.entity.LlmQuotaBan;
import com.leadinsight.module.extract.mapper.LlmQuotaBanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 额度封禁的库内实现（表 llm_quota_ban，主键为前缀化封禁键）。
 *
 * <p>只做存取，不做裁决——什么时候封、封谁、封多久由 {@link PerUserLlmQuotaGuard} 按配置决定。</p>
 *
 * <p>容错取向：本类处于「成本红线」路径上，任何异常都不允许穿透到业务（抽取编排承诺永不抛异常）。
 * 写封禁失败只记 error 日志，不阻断当次抽取——最坏情况是该对象少了一次 7 天封禁，
 * 而不会误封正常用户。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MybatisQuotaBanStore implements QuotaBanStore {

    private final LlmQuotaBanMapper mapper;

    @Override
    public Optional<LocalDateTime> activeBanUntil(String banKey, LocalDateTime now) {
        if (banKey == null || banKey.isBlank()) {
            return Optional.empty();
        }
        try {
            LlmQuotaBan row = mapper.selectById(banKey);
            if (row == null || row.getBannedUntil() == null) {
                return Optional.empty();
            }
            if (row.getBannedUntil().isAfter(now)) {
                return Optional.of(row.getBannedUntil());
            }
            // 已过期：就地清理，避免过期行长期占据主键导致下次封禁走到 update 分支
            clear(banKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("查询额度封禁失败（按未封禁处理，不阻断业务）: key={} 原因={}", banKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void ban(String banKey, String subjectType, String subjectValue,
                    LocalDateTime until, int dailyUsed, String reason, String triggerIp) {
        if (banKey == null || banKey.isBlank()) {
            return;
        }
        try {
            LlmQuotaBan row = new LlmQuotaBan();
            row.setBanKey(banKey);
            row.setSubjectType(subjectType);
            row.setSubjectValue(subjectValue);
            row.setBannedUntil(until);
            row.setDailyUsed(dailyUsed);
            row.setReason(reason);
            row.setTriggerIp(triggerIp);
            if (mapper.selectById(banKey) == null) {
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        } catch (Exception e) {
            log.error("写入额度封禁失败：本次仍放行，但该对象未获封禁: key={} 原因={}", banKey, e.getMessage());
        }
    }

    @Override
    public int countBannedAccountsFromIp(String ip, LocalDateTime now) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }
        try {
            return mapper.countBannedAccountsFromIp(ip, now);
        } catch (Exception e) {
            // 查不出来就按 0 处理 = 不封 IP。取向：宁可少封一个 IP，也不因统计失败误封整片出口
            log.warn("统计同 IP 被封账户数失败（按 0 处理，本次不封 IP）: ip={} 原因={}", ip, e.getMessage());
            return 0;
        }
    }

    @Override
    public void clear(String banKey) {
        if (banKey == null || banKey.isBlank()) {
            return;
        }
        try {
            mapper.deleteById(banKey);
        } catch (Exception e) {
            log.warn("清除过期额度封禁失败（不影响放行）: key={} 原因={}", banKey, e.getMessage());
        }
    }
}

package com.leadinsight.module.lead.service.impl;

import com.leadinsight.cache.CacheService;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.common.util.FingerprintUtil;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.LeadDedupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 内容指纹去重实现（亮点3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadDedupServiceImpl implements LeadDedupService {

    /** 快路径缓存键前缀与保留时长 */
    private static final String CACHE_PREFIX = "lead:fingerprint:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final CacheService cacheService;
    private final LeadMapper leadMapper;

    @Override
    public String fingerprint(String originalText) {
        return FingerprintUtil.fingerprint(originalText);
    }

    @Override
    public void assertNotDuplicate(String fingerprint) {
        Long cached = cacheService.get(cacheKey(fingerprint), Long.class);
        if (cached != null) {
            throw duplicate(cached);
        }
        Long existingId = findIdByFingerprint(fingerprint);
        if (existingId != null) {
            register(fingerprint, existingId);
            throw duplicate(existingId);
        }
    }

    @Override
    public void register(String fingerprint, Long leadId) {
        if (fingerprint == null || leadId == null) {
            return;
        }
        cacheService.put(cacheKey(fingerprint), leadId, CACHE_TTL);
    }

    @Override
    public void resolveConflict(String fingerprint) {
        Long existingId = findIdByFingerprint(fingerprint);
        if (existingId != null) {
            register(fingerprint, existingId);
        }
        log.info("内容指纹唯一键冲突兜底命中: fingerprint={} existingLeadId={}", fingerprint, existingId);
        throw new BizException(ResultCode.DUPLICATE_CONTENT, buildMessage(existingId));
    }

    private Long findIdByFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        // 用自定义 SQL 查询（含已逻辑删除行），与唯一索引 uk_lead_fingerprint 的口径一致；
        // 只取 id——调用方仅需主键拼提示文案，避免把整封邮件正文读出来
        return leadMapper.selectIdByFingerprintIncludingDeleted(fingerprint);
    }

    private BizException duplicate(Long existingLeadId) {
        return new BizException(ResultCode.DUPLICATE_CONTENT, buildMessage(existingLeadId));
    }

    private String buildMessage(Long existingLeadId) {
        if (existingLeadId == null) {
            return "该邮件已导入过（内容指纹重复）";
        }
        return "该邮件已导入过（线索 #" + existingLeadId + "）";
    }

    private String cacheKey(String fingerprint) {
        return CACHE_PREFIX + fingerprint;
    }
}

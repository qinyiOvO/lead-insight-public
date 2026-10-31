package com.leadinsight.statemachine;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.lead.enums.LeadStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 线索状态机（ARCHITECTURE-v1.1 §5.1）。
 *
 * <p>4 态：NEW / FOLLOWING / WON / INVALID。合法迁移：
 * NEW → FOLLOWING、NEW → INVALID、FOLLOWING → WON、FOLLOWING → INVALID。
 * WON 与 INVALID 为终态，不可再迁出；不存在同态自迁移。</p>
 *
 * <p>用静态迁移表而非散落的 if-else：单点维护、可穷举单元测试。</p>
 */
public final class LeadStatusMachine {

    private static final Map<LeadStatus, Set<LeadStatus>> TRANSITIONS;

    static {
        Map<LeadStatus, Set<LeadStatus>> table = new EnumMap<>(LeadStatus.class);
        table.put(LeadStatus.NEW, EnumSet.of(LeadStatus.FOLLOWING, LeadStatus.INVALID));
        table.put(LeadStatus.FOLLOWING, EnumSet.of(LeadStatus.WON, LeadStatus.INVALID));
        table.put(LeadStatus.WON, EnumSet.noneOf(LeadStatus.class));
        table.put(LeadStatus.INVALID, EnumSet.noneOf(LeadStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(table);
    }

    private LeadStatusMachine() {
    }

    /** 是否允许迁移。任一为 null 一律视为不允许。 */
    public static boolean canTransit(LeadStatus from, LeadStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 是否为终态（不可再迁出） */
    public static boolean isTerminal(LeadStatus status) {
        return status != null && TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }

    /** 当前状态的合法后继状态集合（前端可据此隐藏推进入口，AC-25） */
    public static Set<LeadStatus> nextStates(LeadStatus from) {
        if (from == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(from, Set.of()));
    }

    /**
     * 断言迁移合法，非法抛 42201（HTTP 422）。
     * 调用方必须在抛出后不写任何数据、不落跟进记录。
     */
    public static void assertTransit(LeadStatus from, LeadStatus to) {
        if (!canTransit(from, to)) {
            throw new BizException(ResultCode.ILLEGAL_STATUS_TRANSITION, buildMessage(from, to));
        }
    }

    private static String buildMessage(LeadStatus from, LeadStatus to) {
        String fromLabel = describe(from);
        String toLabel = describe(to);
        String prefix = "非法状态迁移：不允许从[" + fromLabel + "]变更为[" + toLabel + "]";

        if (from == to) {
            return prefix + "，当前状态与目标状态相同";
        }
        if (isTerminal(from)) {
            return prefix + "，[" + fromLabel + "]为终态，不可再变更";
        }
        if (from == LeadStatus.NEW && to == LeadStatus.WON) {
            return prefix + "，请先认领进入跟进中";
        }
        if (from == LeadStatus.FOLLOWING && to == LeadStatus.NEW) {
            return prefix + "，不支持状态回退";
        }
        return prefix + "，请先按合法路径推进";
    }

    private static String describe(LeadStatus status) {
        return status == null ? "未知" : status.getLabel();
    }
}

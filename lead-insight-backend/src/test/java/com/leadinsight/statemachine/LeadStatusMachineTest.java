package com.leadinsight.statemachine;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.lead.enums.LeadStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线索状态机纯单元测试（不依赖 Spring 容器、不依赖数据库）。
 * 穷举 4x4=16 种组合，逐一断言合法 / 非法，并校验非法时的错误码与文案。
 */
class LeadStatusMachineTest {

    /** 全部合法迁移（4 条） */
    private static final Set<String> LEGAL = Set.of(
            "NEW->FOLLOWING",
            "NEW->INVALID",
            "FOLLOWING->WON",
            "FOLLOWING->INVALID"
    );

    @Test
    @DisplayName("穷举 4x4 共 16 种状态组合，合法性与迁移表完全一致")
    void exhaustAllSixteenCombinations() {
        StringBuilder report = new StringBuilder("\n状态迁移矩阵穷举结果:\n");
        int legalCount = 0;
        int illegalCount = 0;

        for (LeadStatus from : LeadStatus.values()) {
            for (LeadStatus to : LeadStatus.values()) {
                String key = from.name() + "->" + to.name();
                boolean expected = LEGAL.contains(key);
                boolean actual = LeadStatusMachine.canTransit(from, to);
                report.append(String.format("  %-22s expected=%-5s actual=%-5s %s%n",
                        key, expected, actual, expected == actual ? "OK" : "MISMATCH"));
                assertEquals(expected, actual, "迁移判定不一致: " + key);
                if (expected) {
                    legalCount++;
                } else {
                    illegalCount++;
                }
            }
        }

        report.append("  小计: 合法 ").append(legalCount).append(" 条, 非法 ").append(illegalCount).append(" 条");
        System.out.println(report);

        assertEquals(12, illegalCount, "非法组合应为 16-4=12 条");
        assertEquals(4, legalCount, "合法组合应为 4 条");
    }

    @Test
    @DisplayName("assertTransit 对合法迁移不抛异常")
    void assertTransitPassesForLegal() {
        LeadStatusMachine.assertTransit(LeadStatus.NEW, LeadStatus.FOLLOWING);
        LeadStatusMachine.assertTransit(LeadStatus.NEW, LeadStatus.INVALID);
        LeadStatusMachine.assertTransit(LeadStatus.FOLLOWING, LeadStatus.WON);
        LeadStatusMachine.assertTransit(LeadStatus.FOLLOWING, LeadStatus.INVALID);
    }

    @Test
    @DisplayName("assertTransit 对非法迁移抛 42201，且文案含状态中文名")
    void assertTransitRaises42201() {
        BizException ex = assertThrows(BizException.class,
                () -> LeadStatusMachine.assertTransit(LeadStatus.NEW, LeadStatus.WON));
        assertEquals(ResultCode.ILLEGAL_STATUS_TRANSITION, ex.getResultCode());
        assertEquals(42201, ex.getResultCode().getCode());
        assertEquals(422, ex.getResultCode().getHttpStatus());
        assertTrue(ex.getMessage().contains("新线索"), "文案应含来源状态中文名: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("已成交"), "文案应含目标状态中文名: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("请先认领进入跟进中"), "文案应含引导语: " + ex.getMessage());
        System.out.println("NEW->WON 非法迁移文案: " + ex.getMessage());
    }

    @Test
    @DisplayName("终态不可迁出：WON / INVALID 的全部目标均非法")
    void terminalStatesCannotTransit() {
        assertTrue(LeadStatusMachine.isTerminal(LeadStatus.WON));
        assertTrue(LeadStatusMachine.isTerminal(LeadStatus.INVALID));
        assertFalse(LeadStatusMachine.isTerminal(LeadStatus.NEW));
        assertFalse(LeadStatusMachine.isTerminal(LeadStatus.FOLLOWING));

        for (LeadStatus to : LeadStatus.values()) {
            assertFalse(LeadStatusMachine.canTransit(LeadStatus.WON, to), "WON 不应能迁出到 " + to);
            assertFalse(LeadStatusMachine.canTransit(LeadStatus.INVALID, to), "INVALID 不应能迁出到 " + to);
        }

        BizException ex = assertThrows(BizException.class,
                () -> LeadStatusMachine.assertTransit(LeadStatus.WON, LeadStatus.NEW));
        assertTrue(ex.getMessage().contains("终态"), "终态提示缺失: " + ex.getMessage());
        System.out.println("WON->NEW 非法迁移文案: " + ex.getMessage());
    }

    @Test
    @DisplayName("无自迁移：同态迁移一律非法")
    void noSelfTransition() {
        for (LeadStatus status : LeadStatus.values()) {
            assertFalse(LeadStatusMachine.canTransit(status, status), "同态迁移应非法: " + status);
        }
    }

    @Test
    @DisplayName("nextStates 返回合法后继集合（供前端隐藏推进入口）")
    void nextStatesMatchesTable() {
        assertEquals(EnumSet.of(LeadStatus.FOLLOWING, LeadStatus.INVALID),
                LeadStatusMachine.nextStates(LeadStatus.NEW));
        assertEquals(EnumSet.of(LeadStatus.WON, LeadStatus.INVALID),
                LeadStatusMachine.nextStates(LeadStatus.FOLLOWING));
        assertTrue(LeadStatusMachine.nextStates(LeadStatus.WON).isEmpty());
        assertTrue(LeadStatusMachine.nextStates(LeadStatus.INVALID).isEmpty());
    }

    @Test
    @DisplayName("null 入参一律判定为非法，不抛 NPE")
    void nullSafe() {
        assertFalse(LeadStatusMachine.canTransit(null, LeadStatus.NEW));
        assertFalse(LeadStatusMachine.canTransit(LeadStatus.NEW, null));
        assertFalse(LeadStatusMachine.canTransit(null, null));
    }
}

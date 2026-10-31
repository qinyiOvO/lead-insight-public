package com.leadinsight.module.dashboard.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

/**
 * 趋势条目出参（openapi 1.3.1 TrendVO）。
 *
 * <p>createdCount=当日新建线索数（按 lead.created_at）；
 * wonCount=当日首次成交的线索数（K-4 裁定：per-lead 取首次 WON 迁移时间归日，
 * WON 是终态、线索只可能成交一次，保证趋势累计与 overview.wonLeads 可互相复算）。
 * 返回连续日期序列（含零值日），前端图表无需自行补轴。</p>
 */
@Data
public class TrendVO {

    /** 日期（yyyy-MM-dd 字符串序列化，规避 LocalDate 数组形式） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate date;

    private Long createdCount;

    private Long wonCount;

    public static TrendVO of(LocalDate date, long created, long won) {
        TrendVO vo = new TrendVO();
        vo.setDate(date);
        vo.setCreatedCount(created);
        vo.setWonCount(won);
        return vo;
    }
}

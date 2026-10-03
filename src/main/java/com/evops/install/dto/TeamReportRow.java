package com.evops.install.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 月度安装队报表行：完工量与准时率（结算认这个数）。
 * 完工归属月按完工时刻算；准时 = 完工日期不晚于最终计划施工日期。
 */
@Data
public class TeamReportRow {
    private String teamNo;
    private String teamName;
    /** 当期完工量（单） */
    private int doneCount;
    /** 其中准时完工（完工日期 ≤ 计划施工日） */
    private int ontimeCount;
    /** 其中超期完工 */
    private int lateCount;
    /** 当期取消（上刊单落架/对冲带动） */
    private int canceledCount;
    /** 准时率 %（完工量为 0 时为 null） */
    private BigDecimal ontimeRate;
}

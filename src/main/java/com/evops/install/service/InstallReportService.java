package com.evops.install.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.install.InstallEnums;
import com.evops.install.dto.TeamReportRow;
import com.evops.install.entity.InstallDispatch;
import com.evops.install.mapper.InstallDispatchMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度安装队报表：按完工时刻归属月出每队完工量与准时率，结算认这个数。
 * 准时口径：完工日期不晚于最终计划施工日期（改期后按新计划算）。
 */
@Service
public class InstallReportService {

    private final InstallDispatchMapper dispatchMapper;

    public InstallReportService(InstallDispatchMapper dispatchMapper) {
        this.dispatchMapper = dispatchMapper;
    }

    /** 按队出当月完工量、准时量、准时率；只出有活动的队（完工或取消），按队号排序。 */
    public List<TeamReportRow> teamReport(String period) {
        YearMonth ym = parsePeriod(period);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        List<InstallDispatch> done = dispatchMapper.selectList(new LambdaQueryWrapper<InstallDispatch>()
                .eq(InstallDispatch::getStatus, InstallEnums.DISPATCH_DONE)
                .isNotNull(InstallDispatch::getDoneTime)
                .ge(InstallDispatch::getDoneTime, from)
                .lt(InstallDispatch::getDoneTime, to));
        List<InstallDispatch> canceled = dispatchMapper.selectList(new LambdaQueryWrapper<InstallDispatch>()
                .eq(InstallDispatch::getStatus, InstallEnums.DISPATCH_CANCELED)
                .isNotNull(InstallDispatch::getCanceledTime)
                .ge(InstallDispatch::getCanceledTime, from)
                .lt(InstallDispatch::getCanceledTime, to));

        Map<Long, TeamReportRow> rows = new LinkedHashMap<>();
        for (InstallDispatch d : done) {
            TeamReportRow row = rows.computeIfAbsent(d.getTeamId(), id -> newRow(d));
            row.setDoneCount(row.getDoneCount() + 1);
            LocalDate doneDate = d.getDoneTime().toLocalDate();
            if (d.getPlannedDate() != null && !doneDate.isAfter(d.getPlannedDate())) {
                row.setOntimeCount(row.getOntimeCount() + 1);
            } else {
                row.setLateCount(row.getLateCount() + 1);
            }
        }
        for (InstallDispatch d : canceled) {
            // 从未派到队的取消（PENDING 直接取消）不归任何队，不进队报表
            if (d.getTeamId() == null) {
                continue;
            }
            TeamReportRow row = rows.computeIfAbsent(d.getTeamId(), id -> newRow(d));
            row.setCanceledCount(row.getCanceledCount() + 1);
        }
        List<TeamReportRow> result = new ArrayList<>(rows.values());
        for (TeamReportRow row : result) {
            if (row.getDoneCount() > 0) {
                row.setOntimeRate(BigDecimal.valueOf(row.getOntimeCount() * 100.0 / row.getDoneCount())
                        .setScale(1, RoundingMode.HALF_UP));
            }
        }
        result.sort((a, b) -> a.getTeamNo().compareTo(b.getTeamNo()));
        return result;
    }

    /** 整表导出 CSV（带 BOM，Excel 打开不乱码）。 */
    public String teamReportCsv(String period) {
        List<TeamReportRow> rows = teamReport(period);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("归属期间,队号,队名,完工量,准时完工,超期完工,取消量,准时率%\n");
        for (TeamReportRow r : rows) {
            sb.append(period).append(',')
                    .append(csv(r.getTeamNo())).append(',')
                    .append(csv(r.getTeamName())).append(',')
                    .append(r.getDoneCount()).append(',')
                    .append(r.getOntimeCount()).append(',')
                    .append(r.getLateCount()).append(',')
                    .append(r.getCanceledCount()).append(',')
                    .append(r.getOntimeRate() != null ? r.getOntimeRate().toPlainString() : "")
                    .append('\n');
        }
        return sb.toString();
    }

    private TeamReportRow newRow(InstallDispatch d) {
        TeamReportRow row = new TeamReportRow();
        row.setTeamNo(d.getTeamNo());
        row.setTeamName(d.getTeamName());
        return row;
    }

    private YearMonth parsePeriod(String period) {
        if (period == null || !period.trim().matches("\\d{4}-\\d{2}")) {
            throw new BusinessException("归属期间格式必须是 yyyy-MM：" + period);
        }
        return YearMonth.parse(period.trim());
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

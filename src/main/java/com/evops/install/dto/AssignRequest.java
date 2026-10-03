package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 手工派工/改派请求：与自动匹配同一套资质+档期校验，过期证接高空点位硬拦。 */
@Data
public class AssignRequest {
    /** 目标安装队 */
    @NotBlank(message = "安装队号不能为空")
    private String teamNo;
    /** 计划施工日 */
    @NotNull(message = "计划施工日不能为空")
    private LocalDate plannedDate;
    /** 时段：AM 上午 / PM 下午 */
    @NotBlank(message = "时段不能为空（AM 上午 / PM 下午）")
    private String timeSlot;
    private String operatorName;
    private String remark;
}

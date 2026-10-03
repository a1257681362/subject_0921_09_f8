package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 改期请求：原因必填，重校档期，通知到队。 */
@Data
public class RescheduleRequest {
    @NotNull(message = "新计划施工日不能为空")
    private LocalDate plannedDate;
    @NotBlank(message = "新时段不能为空（AM 上午 / PM 下午）")
    private String timeSlot;
    /** 改期原因（通知到队的内容里带它） */
    @NotBlank(message = "改期原因不能为空，队里要知道为什么改")
    private String reason;
    private String operatorName;
}

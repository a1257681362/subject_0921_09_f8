package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/** 点位高空标记补标/改标请求。 */
@Data
public class SiteHighAltitudeRequest {
    /** 高空点位标记：1=高空点位，0=普通点位 */
    @NotNull(message = "高空点位标记不能为空（0 普通 / 1 高空）")
    private Integer highAltitude;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}

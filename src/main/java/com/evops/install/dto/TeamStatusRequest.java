package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 安装队停用/启用请求。 */
@Data
public class TeamStatusRequest {
    /** ACTIVE 在册 / PAUSED 停用 */
    @NotBlank(message = "状态不能为空（ACTIVE 在册 / PAUSED 停用）")
    private String status;
    private String operatorName;
}

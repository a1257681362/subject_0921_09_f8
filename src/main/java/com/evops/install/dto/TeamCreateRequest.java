package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 安装队建档请求：建档即记资质 v1（INITIAL，直接生效）。 */
@Data
public class TeamCreateRequest {
    /** 队名，全局唯一 */
    @NotBlank(message = "安装队名不能为空")
    private String teamName;
    private String contactName;
    private String contactPhone;
    /** 高空证编号；无证队留空（只能接普通点位） */
    private String certNo;
    /** 高空证有效期至；填了证号必须给有效期 */
    private LocalDate certValidUntil;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}

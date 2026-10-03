package com.evops.install.dto;

import lombok.Data;

import java.time.LocalDate;

/** 换证请求：另起资质新版本（RENEW），旧版置 SUPERSEDED 可翻。 */
@Data
public class QualRenewRequest {
    /** 新高空证编号；留空表示该队换为无证（只能接普通点位） */
    private String certNo;
    /** 新高空证有效期至；填了证号必须给有效期 */
    private LocalDate certValidUntil;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}

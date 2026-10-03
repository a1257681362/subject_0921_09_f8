package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class SiteCreateRequest {
    /** 点位编号，重复存不进去 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 尺寸 */
    @NotBlank(message = "尺寸不能为空")
    private String sizeSpec;
    /** 朝向 */
    @NotBlank(message = "朝向不能为空")
    private String orientation;
    /** 商圈 */
    @NotBlank(message = "商圈不能为空")
    private String businessDistrict;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}

package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 安装队档案：队号、队名唯一；高空证编号与有效期至为当前生效资质（历史见资质版本表）。
 * 无证队可接普通点位，高空点位必须持证且有效；停用队不参与匹配。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_team")
public class InstallTeam extends BaseEntity {
    /** 队号 TM+序列，全局唯一 */
    private String teamNo;
    /** 队名，全局唯一 */
    private String teamName;
    private String contactName;
    private String contactPhone;
    /** ACTIVE 在册 / PAUSED 停用 */
    private String status;
    /** 当前生效高空证编号（无证为 null，只能接普通点位） */
    private String certNo;
    /** 当前生效高空证有效期至；资质匹配状态按「有效期至 ≥ 计划施工日」判定 */
    private LocalDate certValidUntil;
    private Long currentQualVersionId;
    private Integer currentQualVersionNo;
    private String operatorName;
    private String remark;

    /** 资质匹配状态（展示用，不落库）：按「有效期至 ≥ 当天」判定 VALID 有效 / EXPIRED 已过期 */
    @TableField(exist = false)
    private String qualStatus;
}

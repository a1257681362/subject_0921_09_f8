package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 资质匹配版本（只追加）：高空证每录一次记一版（INITIAL 建档 / RENEW 换证），
 * 旧版置 SUPERSEDED 永久可翻；派单落匹配时的版本号与证件快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_qual_version")
public class InstallQualVersion extends BaseEntity {
    private Long teamId;
    private Integer versionNo;
    /** INITIAL 建档 / RENEW 换证 */
    private String changeType;
    /** 本版高空证编号（null = 该版起无高空证） */
    private String certNo;
    /** 本版高空证有效期至 */
    private LocalDate certValidUntil;
    /** EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    private String operatorName;
    private String remark;
}

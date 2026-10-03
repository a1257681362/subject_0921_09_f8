package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 安装派单：上刊单确认（开单）同事务自动生成，按资质+档期匹配安装队。
 * 状态 PENDING 待派工 / ASSIGNED 已派工 / ARRIVED 已到场 / DONE 已完工 / CANCELED 已取消；
 * 高空点位标记、匹配资质版本与证件快照随单固化，之后换证、点位改标都与本单无关。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_dispatch")
public class InstallDispatch extends BaseEntity {
    /** 派单号 DP+序列，全局唯一 */
    private String dispatchNo;
    /** 来源上刊单 */
    private Long orderId;
    private String orderNo;
    private Long siteId;
    private String siteNo;
    /** 高空点位快照（开单时点位的标记）：1 高空 / 0 普通 */
    private Integer highAltitude;
    /** 画面名称快照 */
    private String creativeName;
    /** PENDING / ASSIGNED / ARRIVED / DONE / CANCELED */
    private String status;
    /** 每流转一次 +1 */
    private Integer versionNo;
    private Long teamId;
    private String teamNo;
    private String teamName;
    /** 计划施工日 */
    private LocalDate plannedDate;
    /** 时段：AM 上午 / PM 下午 */
    private String timeSlot;
    /** 匹配时资质版本号与证件快照（事后能翻「当时按哪版资质派的」） */
    private Integer matchedQualVersionNo;
    private String matchedCertNo;
    private LocalDate matchedCertValidUntil;
    private String assignOperator;
    private LocalDateTime assignTime;
    /** AUTO 自动匹配 / MANUAL 手工派工 */
    private String assignSource;
    private String checkinOperator;
    private LocalDateTime checkinTime;
    private String doneOperator;
    private LocalDateTime doneTime;
    /** 完工回执说明 */
    private String receiptContent;
    private String cancelReason;
    private LocalDateTime canceledTime;
    private String operatorName;
    private String remark;
}

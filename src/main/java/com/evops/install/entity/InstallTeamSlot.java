package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 安装队时段占用：一个队同一时段（自然日+上午/下午）只排一个点位。
 * ACTIVE 占用中；完工/取消/改期后 RELEASED，该时段可再排（条件更新抢回，唯一约束兜底并发）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_team_slot")
public class InstallTeamSlot extends BaseEntity {
    private Long teamId;
    private LocalDate plannedDate;
    /** AM 上午 / PM 下午 */
    private String timeSlot;
    /** ACTIVE / RELEASED */
    private String status;
    private Long dispatchId;
    private String dispatchNo;
}

package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 到队通知记录（只追加）：派工 ASSIGN / 改期 RESCHEDULE / 取消 CANCEL。
 * 改期要通知到队——谁发的、发给哪个队、什么内容、什么时刻，一翻就有。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_notice")
public class InstallNotice extends BaseEntity {
    private Long dispatchId;
    private String dispatchNo;
    private Long teamId;
    private String teamNo;
    /** ASSIGN / RESCHEDULE / CANCEL */
    private String noticeType;
    private String content;
    private String operatorName;
    private LocalDateTime notifyTime;
}

package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派单事件留底（只追加，不改不删）：生成/派工/改期/打卡/完工/取消/删照片都在这，
 * 每条带事件后单据快照 JSON。
 */
@Data
@TableName("t_install_event")
public class InstallEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dispatchId;
    private String dispatchNo;
    /** CREATE / ASSIGN / RESCHEDULE / CHECKIN / DONE / CANCEL / PHOTO_DELETE */
    private String eventType;
    private Integer versionNo;
    private String operatorName;
    private String detail;
    /** 事件后单据快照 JSON */
    private String snapshot;
    private LocalDateTime eventTime;
}

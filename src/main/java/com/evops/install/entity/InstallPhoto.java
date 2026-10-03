package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 现场照片：CHECKIN 到场打卡（必填，没照片不算打卡）/ DONE 完工回传（可选）。
 * 派单关闭前一律不许删；关闭后删一张写一条事件。SHA-256 指纹随档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_photo")
public class InstallPhoto extends BaseEntity {
    private Long dispatchId;
    /** CHECKIN / DONE */
    private String phase;
    private String fileName;
    private String contentType;
    private Long fileSize;
    /** 落盘相对路径（根目录在 install.storage-root） */
    private String storagePath;
    private String fingerprintSha256;
    private String uploaderName;
    private LocalDateTime uploadTime;
}

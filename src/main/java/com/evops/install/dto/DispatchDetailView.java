package com.evops.install.dto;

import com.evops.install.entity.InstallDispatch;
import com.evops.install.entity.InstallEvent;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallPhoto;
import lombok.Data;

import java.util.List;

/** 派单全量档案：单据 + 事件版本 + 照片 + 到队通知记录。 */
@Data
public class DispatchDetailView {
    private InstallDispatch dispatch;
    private List<InstallEvent> events;
    private List<InstallPhoto> photos;
    private List<InstallNotice> notices;
}

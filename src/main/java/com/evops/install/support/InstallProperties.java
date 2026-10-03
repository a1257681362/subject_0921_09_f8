package com.evops.install.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 安装派单配置：现场照片落盘根目录。 */
@Data
@Component
@ConfigurationProperties(prefix = "install")
public class InstallProperties {

    /** 现场照片（打卡/完工）落盘根目录（库里只存相对路径） */
    private String storageRoot = "./data/install-photos";
}

package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.runtime.file.app.FileProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 文件与工作区 API 的配置（{@code meridian.runtime.files}，ADR-0050）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
public class FileConfiguration {}

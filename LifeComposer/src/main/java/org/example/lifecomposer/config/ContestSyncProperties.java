package org.example.lifecomposer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 竞赛实时数据同步配置（镜像优先 / GitHub 主源 / events 兜底 / 校内通知独立）。
 * 对应 {@code lifecomposer.contest-sync.*}，值在 application.properties 中可覆盖。
 */
@Configuration
@ConfigurationProperties(prefix = "lifecomposer.contest-sync")
@Getter
@Setter
public class ContestSyncProperties {

    /** 总开关；关闭后调度器与启动补拉都不执行，查询仍读库内已有数据。 */
    private boolean enabled = true;

    private String mirrorUrl = "https://where-to-study.cn/contest-ddl/data/competitions.json";
    private String githubUrl = "https://nemoyuzx.github.io/contest-ddl/data/competitions.json";
    private String eventsUrl = "https://where-to-study.cn/api/contest-events";
    private String noticesUrl = "https://where-to-study.cn/api/contest-notices";

    private int connectTimeoutMillis = 5000;
    private int readTimeoutMillis = 20000;
    /** 响应体超过该字节数即视为异常，避免超大快照拖垮内存。 */
    private long maxBodyBytes = 8 * 1024 * 1024L;

    private String syncCron = "0 20 6,12,18,23 * * *";

    /** 启动时若最近一次成功同步距现在超过该小时数，则补拉一次。 */
    private int startupMaxAgeHours = 6;
}

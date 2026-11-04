package org.example.lifecomposer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 {@code @Scheduled}（v0.1.6 登录表清理用）。
 *
 * <p>项目里原本没有定时任务，所以单独开一个配置类而不是挂在启动类上，
 * 方便以后 locate "为什么会有定时任务"。目前唯一的任务是
 * {@code Service/LoginCleanupJob}。</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

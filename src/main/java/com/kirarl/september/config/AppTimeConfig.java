package com.kirarl.september.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 全局业务时间口径。
 *
 * <p>生日是「日历日期」，本身不带时区；但「今天」是由时间点派生出来的。
 * 如果直接用 {@code LocalDate.now()}，它跟随服务器 JVM 默认时区（云主机常为 UTC），
 * 就会出现这样的错判：来访者本地已经是「今天」，服务器却还认为是「昨天」，
 * 于是用户选了自己的今天，却被拒绝为「生日不能晚于今天」。
 *
 * <p>所以这里把业务时区固定为 {@code Asia/Shanghai}（东八区）。
 * 所有「今天」的判断都必须通过注入的 {@link Clock} 获取，
 * 不要在新代码里再出现无参数的 {@code LocalDate.now()}。
 *
 * <p>选东八区还有一个额外好处：中国农历的日界按国家标准以东经 120 度为界，
 * 与业务时区天然对齐，不会出现「农历日和业务日差一天」。
 */
@Configuration
public class AppTimeConfig {

    /** 业务时区：中国标准时间。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}

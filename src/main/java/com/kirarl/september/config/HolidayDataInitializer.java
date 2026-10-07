package com.kirarl.september.config;

import com.kirarl.september.entity.HolidayDay;
import com.kirarl.september.mapper.HolidayMapper;
import com.nlf.calendar.Holiday;
import com.nlf.calendar.util.HolidayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 用农历库内置的节假日数据，把 {@code holiday} 表里「整年还没有记录」的年份补上。
 *
 * <p>为什么要这一步：节假日是无法推导的政策数据，必须入库才能在不发版的前提下更新；
 * 但纯手工录入一开始会很空。农历库（cn.6tail:lunar）自带一份国务院公布的安排，
 * 用它做一次初始灌入，日历就能立刻有数据可用。
 *
 * <p>三条边界：
 * <ul>
 *   <li>只补「整年没有记录」的年份，已经有记录的年份一律不动——所以人工修正过的数据不会被覆盖。</li>
 *   <li>库里也没有数据的年份（公告尚未发布的次年）会保持空着，不写任何占位记录。</li>
 *   <li>表仍是权威来源：将来公告发布后，既可以升级 jar 再重启让它补齐，
 *       也可以直接 INSERT 覆盖，两者都不需要改代码。</li>
 * </ul>
 */
@Component
public class HolidayDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HolidayDataInitializer.class);

    /** 农历库内置节假日数据的起始年份，也是我们回溯补齐的下限。 */
    private static final int SEED_FROM_YEAR = 2004;

    private final HolidayMapper holidayMapper;
    private final Clock businessClock;

    public HolidayDataInitializer(HolidayMapper holidayMapper, Clock businessClock) {
        this.holidayMapper = holidayMapper;
        this.businessClock = businessClock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int lastYear = LocalDate.now(businessClock).getYear() + 1;
        int years = 0;
        int rows = 0;

        for (int year = SEED_FROM_YEAR; year <= lastYear; year++) {
            if (holidayMapper.countByYear(year) > 0) {
                continue;
            }

            List<Holiday> builtIn = HolidayUtil.getHolidays(year);
            if (builtIn == null || builtIn.isEmpty()) {
                // 该年安排尚未发布：保持空着，这是正常状态而不是错误。
                continue;
            }

            int inserted = 0;
            for (Holiday holiday : builtIn) {
                HolidayDay row = new HolidayDay();
                row.setHolidayDate(LocalDate.parse(holiday.getDay()));
                row.setName(holiday.getName());
                row.setDayType(holiday.isWork() ? HolidayDay.TYPE_WORK : HolidayDay.TYPE_REST);
                inserted += holidayMapper.insert(row);
            }
            years++;
            rows += inserted;
        }

        if (rows > 0) {
            log.info("holiday 表已从农历库内置数据补齐 {} 个年份，共 {} 条节假日/调休记录", years, rows);
        }
    }
}

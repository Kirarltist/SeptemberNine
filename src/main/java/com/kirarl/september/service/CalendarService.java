package com.kirarl.september.service;

import com.kirarl.september.dto.CalendarDay;
import com.kirarl.september.dto.CalendarMonth;
import com.kirarl.september.entity.HolidayDay;
import com.kirarl.september.mapper.HolidayMapper;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日历一个月网格的数据装配。
 *
 * <p>三层数据在这里汇合：
 * <ol>
 *   <li>公历网格 —— 由 {@link LocalDate} 直接推导；</li>
 *   <li>农历与节气 —— 由 {@code cn.6tail:lunar} 计算（自己算必错，见 pom.xml 注释）；</li>
 *   <li>节假日与调休 —— 来自 {@code holiday} 表，查不到就留空。</li>
 * </ol>
 *
 * <p>前端只负责把 {@code days} 铺进 7 列的网格里，不做任何日期运算。
 */
@Service
public class CalendarService {

    /** 表头，周日开头，与前端既有的 WEEKDAYS 排列保持一致。 */
    public static final List<String> WEEKDAY_LABELS = List.of("日", "一", "二", "三", "四", "五", "六");

    /** 农历库的有效范围，也让日历的浏览边界明确下来。 */
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 2100;

    private final HolidayMapper holidayMapper;
    private final Clock businessClock;

    public CalendarService(HolidayMapper holidayMapper, Clock businessClock) {
        this.holidayMapper = holidayMapper;
        this.businessClock = businessClock;
    }

    /**
     * 取某个月的日历网格。年份或月份传 null 时回落到业务口径的当月。
     *
     * @throws IllegalArgumentException 年份或月份超出可计算范围
     */
    public CalendarMonth month(Integer year, Integer month) {
        LocalDate today = LocalDate.now(businessClock);
        int targetYear = year == null ? today.getYear() : year;
        int targetMonth = month == null ? today.getMonthValue() : month;

        if (targetYear < MIN_YEAR || targetYear > MAX_YEAR) {
            throw new IllegalArgumentException("年份需要在 " + MIN_YEAR + " 到 " + MAX_YEAR + " 之间");
        }
        if (targetMonth < 1 || targetMonth > 12) {
            throw new IllegalArgumentException("月份需要在 1 到 12 之间");
        }

        LocalDate first = LocalDate.of(targetYear, targetMonth, 1);
        // 周日开头的网格：DayOfWeek 里周一=1…周日=7，取模后周日=0、周一=1…周六=6
        int leading = first.getDayOfWeek().getValue() % 7;
        LocalDate gridStart = first.minusDays(leading);
        // 补齐完整的星期，所以格子数一定是 7 的整数倍（4~6 周）
        int cells = (int) (Math.ceil((leading + first.lengthOfMonth()) / 7.0) * 7);
        LocalDate gridEnd = gridStart.plusDays(cells - 1L);

        Map<LocalDate, HolidayDay> holidays = new HashMap<>();
        for (HolidayDay holiday : holidayMapper.selectBetween(gridStart, gridEnd)) {
            holidays.put(holiday.getHolidayDate(), holiday);
        }

        List<CalendarDay> days = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            LocalDate date = gridStart.plusDays(i);
            days.add(buildDay(date, date.getMonthValue() == targetMonth, today, holidays.get(date)));
        }

        CalendarMonth view = new CalendarMonth();
        view.setYear(targetYear);
        view.setMonth(targetMonth);
        view.setToday(today.toString());
        view.setMonthLabel(targetYear + " 年 " + targetMonth + " 月");
        view.setWeekdays(WEEKDAY_LABELS);
        view.setHolidayDataAvailable(holidayMapper.countByYear(targetYear) > 0);
        view.setDays(days);
        return view;
    }

    private CalendarDay buildDay(LocalDate date, boolean inMonth, LocalDate today, HolidayDay holiday) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        Lunar lunar = solar.getLunar();

        String jieQi = lunar.getJieQi() == null ? "" : lunar.getJieQi();

        // 农历节日（春节、中秋…）排在公历节日（元旦、国庆…）前面，
        // 这样大年初一不会因为恰好也是别的公历节日而被顶掉。
        List<String> festivals = new ArrayList<>();
        if (lunar.getFestivals() != null) {
            festivals.addAll(lunar.getFestivals());
        }
        if (solar.getFestivals() != null) {
            festivals.addAll(solar.getFestivals());
        }

        CalendarDay day = new CalendarDay();
        day.setDate(date.toString());
        day.setDay(date.getDayOfMonth());
        day.setInMonth(inMonth);
        day.setLunar(lunar.getDayInChinese());
        day.setJieQi(jieQi);
        day.setLabel(resolveLabel(jieQi, festivals, lunar));
        day.setToday(date.equals(today));
        if (holiday != null) {
            day.setHolidayName(holiday.getName());
            day.setHolidayType(holiday.getDayType());
        }
        return day;
    }

    /**
     * 中国日历通行的显示优先级：节气 &gt; 农历节日 &gt; 公历节日 &gt; 农历初一显示月名 &gt; 农历日名。
     *
     * <p>把这段判断放在后端，是为了让前端保持「只渲染不算日期」，
     * 避免同一条规则在两端各写一遍而产生分歧。
     */
    private String resolveLabel(String jieQi, List<String> festivals, Lunar lunar) {
        if (!jieQi.isEmpty()) {
            return jieQi;
        }
        if (!festivals.isEmpty()) {
            return festivals.get(0);
        }
        if (lunar.getDay() == 1) {
            // 闰月的 getMonthInChinese() 自带「闰」前缀，例如「闰冬」，加「月」后为「闰冬月」
            return lunar.getMonthInChinese() + "月";
        }
        return lunar.getDayInChinese();
    }
}

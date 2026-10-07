package com.kirarl.september.service;

import com.kirarl.september.config.AppTimeConfig;
import com.kirarl.september.dto.CalendarDay;
import com.kirarl.september.dto.CalendarMonth;
import com.kirarl.september.entity.HolidayDay;
import com.kirarl.september.mapper.HolidayMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日历装配的锚点测试：不启动 Spring、不连数据库，节假日用内存桩代替。
 *
 * <p>覆盖的都是容易出错的地方：网格首尾补齐、周日开头、农历/节气文案优先级、
 * 业务时区口径、节假日标记，以及 2033 这个置闰歧义年份。
 */
class CalendarServiceTest {

    /** 2026-10-07T02:00:00Z 折合东八区 2026-10-07 10:00，所以业务今天应为 2026-10-07。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), AppTimeConfig.BUSINESS_ZONE);

    private static final class StubHolidayMapper implements HolidayMapper {

        private final Map<LocalDate, HolidayDay> data = new HashMap<>();

        StubHolidayMapper with(String date, String name, int dayType) {
            HolidayDay row = new HolidayDay();
            row.setHolidayDate(LocalDate.parse(date));
            row.setName(name);
            row.setDayType(dayType);
            data.put(row.getHolidayDate(), row);
            return this;
        }

        @Override
        public List<HolidayDay> selectBetween(LocalDate start, LocalDate end) {
            List<HolidayDay> result = new ArrayList<>();
            for (Map.Entry<LocalDate, HolidayDay> entry : data.entrySet()) {
                LocalDate date = entry.getKey();
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    result.add(entry.getValue());
                }
            }
            return result;
        }

        @Override
        public int countByYear(int year) {
            int count = 0;
            for (LocalDate date : data.keySet()) {
                if (date.getYear() == year) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int insert(HolidayDay holiday) {
            throw new UnsupportedOperationException("测试不需要写入");
        }
    }

    private static CalendarService service(HolidayMapper mapper) {
        return new CalendarService(mapper, FIXED_CLOCK);
    }

    private static CalendarDay dayOf(CalendarMonth month, String date) {
        for (CalendarDay day : month.getDays()) {
            if (date.equals(day.getDate())) {
                return day;
            }
        }
        throw new AssertionError("网格里没有 " + date);
    }

    @Test
    @DisplayName("网格补齐完整星期、首格是周日、相邻格子连续")
    void gridIsCompleteWeeksStartingSunday() {
        CalendarMonth month = service(new StubHolidayMapper()).month(2026, 10);

        assertEquals(0, month.getDays().size() % 7, "格子数必须是 7 的整数倍");
        for (int i = 0; i < month.getDays().size(); i += 7) {
            LocalDate rowStart = LocalDate.parse(month.getDays().get(i).getDate());
            int expected = rowStart.getDayOfWeek().getValue() % 7;
            assertEquals("日", month.getWeekdays().get(expected), "每行第一格必须是周日");
        }
        for (int i = 1; i < month.getDays().size(); i++) {
            assertEquals(LocalDate.parse(month.getDays().get(i - 1).getDate()).plusDays(1),
                    LocalDate.parse(month.getDays().get(i).getDate()), "相邻格子必须正好差一天");
        }

        long inMonth = month.getDays().stream().filter(CalendarDay::isInMonth).count();
        assertEquals(LocalDate.of(2026, 10, 1).lengthOfMonth(), inMonth, "当月天数应与公历一致");
    }

    @Test
    @DisplayName("春节锚点：2026-02-17 是正月初一")
    void springFestivalAnchor() {
        CalendarDay day = dayOf(service(new StubHolidayMapper()).month(2026, 2), "2026-02-17");
        assertEquals("初一", day.getLunar());
        assertEquals("春节", day.getLabel(), "农历节日应优先于农历日名显示");
    }

    @Test
    @DisplayName("节气优先于一切显示")
    void jieQiWinsOverLunarDay() {
        CalendarMonth month = service(new StubHolidayMapper()).month(2026, 10);
        assertEquals("寒露", dayOf(month, "2026-10-08").getLabel());
        assertEquals("霜降", dayOf(month, "2026-10-23").getLabel());
        assertEquals("", dayOf(month, "2026-10-09").getJieQi(), "非节气日应为空串");
    }

    @Test
    @DisplayName("农历初一显示月名")
    void lunarMonthFirstDayShowsMonthName() {
        assertEquals("九月", dayOf(service(new StubHolidayMapper()).month(2026, 10), "2026-10-10").getLabel());
    }

    @Test
    @DisplayName("「今天」按业务时区（东八区）判断，与服务器时区无关")
    void todayFollowsBusinessZone() {
        CalendarMonth month = service(new StubHolidayMapper()).month(2026, 10);
        assertEquals("2026-10-07", month.getToday());
        assertTrue(dayOf(month, "2026-10-07").isToday());
        assertFalse(dayOf(month, "2026-10-06").isToday());
    }

    @Test
    @DisplayName("休 / 班 标记来自 holiday 表")
    void holidayMarkersComeFromTable() {
        HolidayMapper mapper = new StubHolidayMapper()
                .with("2026-10-01", "国庆节", HolidayDay.TYPE_REST)
                .with("2026-10-10", "国庆节", HolidayDay.TYPE_WORK);

        CalendarMonth month = service(mapper).month(2026, 10);

        assertTrue(month.isHolidayDataAvailable());
        assertEquals("国庆节", dayOf(month, "2026-10-01").getHolidayName());
        assertEquals(HolidayDay.TYPE_REST, dayOf(month, "2026-10-01").getHolidayType().intValue());
        assertEquals(HolidayDay.TYPE_WORK, dayOf(month, "2026-10-10").getHolidayType().intValue());
        assertNull(dayOf(month, "2026-10-02").getHolidayType(), "非节假日不应有标记");
    }

    @Test
    @DisplayName("安排尚未发布的年份保持空着，且明确告知前端")
    void unpublishedYearStaysEmpty() {
        CalendarMonth month = service(new StubHolidayMapper()).month(2027, 2);
        assertFalse(month.isHolidayDataAvailable());
        for (CalendarDay day : month.getDays()) {
            assertNull(day.getHolidayType());
        }
    }

    @Test
    @DisplayName("2033 年置闰取「闰十一月」，与官方 GB/T 33661-2017 一致")
    void leapMonth2033() {
        CalendarMonth month = service(new StubHolidayMapper()).month(2033, 12);

        assertEquals("初一", dayOf(month, "2033-12-22").getLunar(), "2033-12-22 应是闰十一月初一");
        boolean hasLeapMonthLabel = month.getDays().stream()
                .anyMatch(day -> "闰冬月".equals(day.getLabel()));
        assertTrue(hasLeapMonthLabel, "闰月首日应显示闰月月名（闰冬月 = 闰十一月）");
    }

    @Test
    @DisplayName("越界的年份/月份被拒绝")
    void rejectsOutOfRangeInput() {
        CalendarService service = service(new StubHolidayMapper());
        assertNotNull(assertThrows(IllegalArgumentException.class, () -> service.month(1899, 1)).getMessage());
        assertNotNull(assertThrows(IllegalArgumentException.class, () -> service.month(2101, 1)).getMessage());
        assertNotNull(assertThrows(IllegalArgumentException.class, () -> service.month(2026, 13)).getMessage());
    }

    @Test
    @DisplayName("不传年月时回落到业务口径的当月")
    void defaultsToCurrentBusinessMonth() {
        CalendarMonth month = service(new StubHolidayMapper()).month(null, null);
        assertEquals(2026, month.getYear());
        assertEquals(10, month.getMonth());
    }
}

package com.kirarl.september.dto;

import java.util.List;

/**
 * 一个月的日历网格，直接对应前端要画的那张表。
 *
 * <p>{@code days} 的长度一定是 7 的整数倍，并且第一格固定是周日，
 * 所以前端不需要做任何日期运算，把它铺进网格即可。
 */
public class CalendarMonth {

    private int year;

    private int month;

    /** 业务口径的今天（东八区），与 {@code CalendarDay.today} 用的是同一个值。 */
    private String today;

    /** 标题文案，例如「2026 年 10 月」。 */
    private String monthLabel;

    /** 表头，周日开头：日 一 二 三 四 五 六。 */
    private List<String> weekdays;

    /**
     * 该年是否已有节假日安排数据。
     * false 表示当年安排尚未发布，日历上不会出现「休/班」标记——这是正常状态，不是错误。
     */
    private boolean holidayDataAvailable;

    private List<CalendarDay> days;

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getMonth() {
        return month;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public String getToday() {
        return today;
    }

    public void setToday(String today) {
        this.today = today;
    }

    public String getMonthLabel() {
        return monthLabel;
    }

    public void setMonthLabel(String monthLabel) {
        this.monthLabel = monthLabel;
    }

    public List<String> getWeekdays() {
        return weekdays;
    }

    public void setWeekdays(List<String> weekdays) {
        this.weekdays = weekdays;
    }

    public boolean isHolidayDataAvailable() {
        return holidayDataAvailable;
    }

    public void setHolidayDataAvailable(boolean holidayDataAvailable) {
        this.holidayDataAvailable = holidayDataAvailable;
    }

    public List<CalendarDay> getDays() {
        return days;
    }

    public void setDays(List<CalendarDay> days) {
        this.days = days;
    }
}

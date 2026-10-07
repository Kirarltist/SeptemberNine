package com.kirarl.september.dto;

/**
 * 日历网格里的一格（一天）。
 *
 * <p>它把前端渲染需要的信息一次性备齐：公历日、农历、节气、节日、节假日标记。
 * 其中 {@code label} 是后端已经按中国日历惯例解析好的展示文案，
 * 前端直接显示即可，不需要再判断优先级。
 */
public class CalendarDay {

    /** 公历日期，ISO 格式 yyyy-MM-dd。 */
    private String date;

    /** 公历日号，例如 1、28。 */
    private int day;

    /** 是否属于请求的那一个月。网格首尾会补邻月的日期，那些格子为 false。 */
    private boolean inMonth;

    /** 农历日名，例如「初一」「廿三」「三十」。 */
    private String lunar;

    /** 节气名，例如「寒露」「霜降」；不是节气日则为空串。 */
    private String jieQi;

    /** 已解析好的展示文案：节气 &gt; 农历节日 &gt; 公历节日 &gt; 农历月/日。 */
    private String label;

    /** 节假日或调休的名称，例如「春节」「国庆节」；没有则为 null。 */
    private String holidayName;

    /** 1 = 休（放假），2 = 班（调休上班）；没有则为 null。 */
    private Integer holidayType;

    /** 是否为业务口径（东八区）的今天。 */
    private boolean today;

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getDay() {
        return day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public boolean isInMonth() {
        return inMonth;
    }

    public void setInMonth(boolean inMonth) {
        this.inMonth = inMonth;
    }

    public String getLunar() {
        return lunar;
    }

    public void setLunar(String lunar) {
        this.lunar = lunar;
    }

    public String getJieQi() {
        return jieQi;
    }

    public void setJieQi(String jieQi) {
        this.jieQi = jieQi;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getHolidayName() {
        return holidayName;
    }

    public void setHolidayName(String holidayName) {
        this.holidayName = holidayName;
    }

    public Integer getHolidayType() {
        return holidayType;
    }

    public void setHolidayType(Integer holidayType) {
        this.holidayType = holidayType;
    }

    public boolean isToday() {
        return today;
    }

    public void setToday(boolean today) {
        this.today = today;
    }
}

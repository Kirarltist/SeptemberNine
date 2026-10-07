package com.kirarl.september.entity;

import java.time.LocalDate;

/**
 * 节假日 / 调休日，对应数据库表 {@code holiday} 的一行。
 *
 * <p>这是日历的「第三层」数据：它不可由公历推导，只能跟着国务院办公厅的公告走，
 * 所以是整个日历里唯一需要入库的内容。
 */
public class HolidayDay {

    /** 休息日（放假）。 */
    public static final int TYPE_REST = 1;

    /** 调休上班日（补班）。 */
    public static final int TYPE_WORK = 2;

    private LocalDate holidayDate;
    private String name;
    private Integer dayType;

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDayType() {
        return dayType;
    }

    public void setDayType(Integer dayType) {
        this.dayType = dayType;
    }
}

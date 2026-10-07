package com.kirarl.september.mapper;

import com.kirarl.september.entity.HolidayDay;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HolidayMapper {

    /**
     * 取某个日期区间内的节假日/调休记录。
     * 日历网格的首尾会补上邻月的日期，所以区间要覆盖整个网格而不是只覆盖当月。
     */
    @Select("SELECT holiday_date, name, day_type FROM `holiday` "
            + "WHERE holiday_date BETWEEN #{start} AND #{end} ORDER BY holiday_date")
    List<HolidayDay> selectBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 某一年有多少条记录。
     * 为 0 表示该年安排尚未发布（例如公告发布前的次年），日历上表现为「整年没有任何标记」。
     */
    @Select("SELECT COUNT(*) FROM `holiday` WHERE YEAR(holiday_date) = #{year}")
    int countByYear(@Param("year") int year);

    @Insert("INSERT INTO `holiday` (holiday_date, name, day_type) "
            + "VALUES (#{holidayDate}, #{name}, #{dayType})")
    int insert(HolidayDay holiday);
}

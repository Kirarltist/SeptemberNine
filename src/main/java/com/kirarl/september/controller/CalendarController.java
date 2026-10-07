package com.kirarl.september.controller;

import com.kirarl.september.dto.CalendarMonth;
import com.kirarl.september.service.CalendarService;
import com.kirarl.september.util.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日历数据接口。
 *
 * <p>不传参数时返回业务口径的当月；农历、节气、节日、节假日/调休都在同一个响应里返回，
 * 前端一次请求就能画出整月，不需要自己算日期。
 *
 * <p>说明：本项目目前没有会话/令牌机制，这个接口与其他接口一样不做身份校验，
 * 返回的也全是公开信息（农历、节气、法定节假日）。将来若要在日历上叠加个人数据
 * （例如本人标记、生日提醒），再单独给那部分接口加鉴权。
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /**
     * 取某个月的日历网格。
     *
     * @param year  年份，可省略（默认当月）
     * @param month 月份 1-12，可省略（默认当月）
     */
    @GetMapping("/month")
    public Result<CalendarMonth> month(@RequestParam(required = false) Integer year,
                                       @RequestParam(required = false) Integer month) {
        try {
            return Result.success("ok", calendarService.month(year, month));
        } catch (IllegalArgumentException ex) {
            return Result.failure(ex.getMessage());
        }
    }
}

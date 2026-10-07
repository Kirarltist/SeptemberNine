package com.kirarl.september.controller;

import com.kirarl.september.dto.LoginRequest;
import com.kirarl.september.dto.RegisterRequest;
import com.kirarl.september.dto.BindEmailRequest;
import com.kirarl.september.dto.UpdateProfileRequest;
import com.kirarl.september.entity.User;
import com.kirarl.september.service.UserService;
import com.kirarl.september.util.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
    private static final Set<String> ALLOWED_GENDERS = Set.of("男", "女", "武装直升机");
    private static final LocalDate EARLIEST_BIRTHDAY = LocalDate.of(1900, 1, 1);

    private final UserService userService;
    /** 业务时钟（东八区），见 AppTimeConfig。所有「今天」都必须走它。 */
    private final Clock businessClock;

    public LoginController(UserService userService, Clock businessClock) {
        this.userService = userService;
        this.businessClock = businessClock;
    }

    /**
     * 业务口径的「今天」。
     *
     * <p>不要改用无参数的 {@code LocalDate.now()}：那会跟随服务器 JVM 默认时区
     * （云主机常为 UTC），导致东八区用户在凌晨时段选「今天」被误判为「晚于今天」。
     */
    private LocalDate today() {
        return LocalDate.now(businessClock);
    }

    /**
     * 登录成功时把账号状态一并返回，前端据此决定是否需要进入“绑定邮箱（可选）”步骤。
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return Result.failure("用户名和密码不能为空");
        }

        String username = request.getUsername().trim();
        if (!userService.checkPassword(username, request.getPassword())) {
            return Result.failure("用户名或密码错误");
        }

        User user = userService.findByUsername(username);
        boolean hasEmail = user != null && user.getEmail() != null && !user.getEmail().isBlank();

        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("hasEmail", hasEmail);
        data.put("email", hasEmail ? user.getEmail() : null);
        // 性别与生日未设置时回传 null，由前端统一显示“保密”
        data.put("gender", user == null ? null : user.getGender());
        data.put("birthday", user == null || user.getBirthday() == null ? null : user.getBirthday().toString());
        // 下发业务口径的今天（东八区）。前端用它作为生日上限和日历里的「今天」，
        // 这样前后端对「今天」的判断永远一致，不受访问者设备时区影响。
        data.put("today", today().toString());
        return Result.success(hasEmail ? "登录成功" : "登录成功，可绑定邮箱", data);
    }

    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterRequest request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return Result.failure("用户名和密码不能为空");
        }
        if (request.getUsername().length() < 3 || request.getUsername().length() > 50) {
            return Result.failure("用户名长度必须为3到50个字符");
        }
        if (request.getPassword().length() < 6) {
            return Result.failure("密码长度不能少于6个字符");
        }

        if (!userService.register(request.getUsername().trim(), request.getPassword())) {
            return Result.failure("用户名已存在");
        }
        return Result.success("注册成功");
    }

    /**
     * 绑定邮箱为可选操作，但仍需用密码确认身份，避免任何人凭用户名篡改他人邮箱。
     */
    @PostMapping("/bind-email")
    public Result<Void> bindEmail(@RequestBody BindEmailRequest request) {
        if (request == null || isBlank(request.getUsername())
                || isBlank(request.getEmail()) || isBlank(request.getPassword())) {
            return Result.failure("用户名、密码和邮箱不能为空");
        }
        if (!request.getEmail().matches(EMAIL_PATTERN)) {
            return Result.failure("请输入正确的邮箱地址");
        }

        String username = request.getUsername().trim();
        if (!userService.checkPassword(username, request.getPassword())) {
            return Result.failure("用户名或密码错误，无法绑定邮箱");
        }
        if (!userService.bindEmail(username, request.getEmail().trim())) {
            return Result.failure("用户不存在或邮箱绑定失败");
        }
        return Result.success("邮箱绑定成功");
    }

    /**
     * 保存性别与生日。两者都可以留空，留空表示“保密”；
     * 与绑定邮箱一致，仍需密码确认身份，避免他人凭用户名改资料。
     */
    @PostMapping("/update-profile")
    public Result<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return Result.failure("用户名和密码不能为空");
        }

        String gender = normalize(request.getGender());
        if (gender != null && !ALLOWED_GENDERS.contains(gender)) {
            return Result.failure("性别只能选择男、女或武装直升机");
        }

        String rawBirthday = normalize(request.getBirthday());
        LocalDate birthday = null;
        if (rawBirthday != null) {
            try {
                birthday = LocalDate.parse(rawBirthday);
            } catch (DateTimeParseException ex) {
                // 这里是严格 ISO 解析：2024-2-5（月份不补零）、2024-02-30、
                // 以及恰好卡在下界上的 1900-02-29（1900 不是闰年）都会落到这里。
                // 所以文案要同时覆盖「格式不对」和「日期不存在」两种情况。
                return Result.failure("生日格式应为 yyyy-MM-dd，且必须是真实存在的日期");
            }
            if (birthday.isBefore(EARLIEST_BIRTHDAY)) {
                return Result.failure("生日不能早于 1900-01-01");
            }
            if (birthday.isAfter(today())) {
                return Result.failure("生日不能晚于今天");
            }
        }

        String username = request.getUsername().trim();
        if (!userService.checkPassword(username, request.getPassword())) {
            return Result.failure("用户名或密码错误，无法保存资料");
        }
        if (!userService.updateProfile(username, gender, birthday)) {
            return Result.failure("用户不存在或资料保存失败");
        }
        return Result.success("资料已保存");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }
}

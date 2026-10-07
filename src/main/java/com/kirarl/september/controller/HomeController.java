package com.kirarl.september.controller;

import com.kirarl.september.util.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Result<Map<String, String>> home() {
        return Result.success("September 后端服务运行正常", Map.of(
                "frontend", "http://localhost:5173",
                "login", "POST /api/auth/login",
                "register", "POST /api/auth/register",
                "bindEmail", "POST /api/auth/bind-email",
                "updateProfile", "POST /api/auth/update-profile"
        ));
    }
}

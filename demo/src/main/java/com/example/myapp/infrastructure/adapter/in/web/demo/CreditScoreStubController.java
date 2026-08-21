package com.example.myapp.infrastructure.adapter.in.web.demo;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅用于本地演示的下游信用服务桩：校验 Bearer 头（演示 AuthHandler 生效），返回固定信用分。
 * 与框架的 RouterFunction 端点共存，证明函数式路由与注解式 Controller 可混合使用。
 * 生产环境删除本类，把 credit.base-url 指向真实下游即可。
 */
@RestController
public class CreditScoreStubController {

    @GetMapping("/stub/credit/scores/{userId}")
    public ResponseEntity<Map<String, Object>> score(
            @PathVariable String userId,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "missing bearer token"));
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "score", 760,
                "level", "A"));
    }
}

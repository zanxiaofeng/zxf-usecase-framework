package com.example.myapp.framework.steps.config;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.http.HttpMethod;

/**
 * httpRequester 步骤的 config schema。认证 scheme 的存在性与 options 校验依赖注册表，
 * 仍由 HttpRequesterStepFactory 在装配期完成（注解无法表达）。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性。</p>
 */
@Data
public class HttpRequesterConfig {

    /** HTTP 方法（绑定大小写不敏感），缺省 GET */
    private HttpMethod method = HttpMethod.GET;

    /** 目标 URL（必填），支持 {var} 模板 */
    @NotBlank
    private String url;

    /** URI 模板变量，值支持 SpEL */
    private Map<String, Object> uriVariables = Map.of();

    /** 请求头，值支持字面量 / 模板 / SpEL */
    private Map<String, Object> headers = Map.of();

    /** 请求体 SpEL 表达式，结果序列化为 JSON */
    private String body;

    /** 认证配置；缺省不携带认证头 */
    @Valid
    private AuthConfig auth;

    /** 结果写入 #vars 的旁路键；缺省写回 payload */
    private String as;

    /** 认证块：scheme 必填（声明了 auth 就必须给出 scheme）；options 为 scheme 相关的开放 Map */
    @Data
    public static class AuthConfig {

        @NotBlank
        private String scheme;

        private Map<String, Object> options = Map.of();
    }
}

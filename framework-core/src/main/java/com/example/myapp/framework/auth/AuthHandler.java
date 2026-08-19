package com.example.myapp.framework.auth;

import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * HttpRequester 步骤的认证策略 SPI。
 *
 * <p>内置 scheme：{@code none} / {@code basic} / {@code bearer} / {@code apiKey} / {@code clientCredentials}。
 * 扩展方式：实现本接口注册为 Spring Bean，{@code scheme()} 返回新方案名即可在 YAML 中引用；
 * 与内置 scheme 同名时覆盖内置实现。
 */
public interface AuthHandler {

    /** 认证方案名（YAML 中 auth.scheme 的值）。 */
    String scheme();

    /** 把认证信息附加到请求上。options 为 YAML 中 auth.options 的原始 Map。 */
    void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options);

    /**
     * 装配期校验 options 合法性（fail-fast）。默认不校验。
     * 校验失败抛出 UseCaseAssemblyException 或 IllegalArgumentException。
     */
    default void validate(Map<String, Object> options) {
    }
}

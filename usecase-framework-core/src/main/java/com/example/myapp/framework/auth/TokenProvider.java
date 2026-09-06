package com.example.myapp.framework.auth;

/**
 * 动态令牌供给 SPI。bearer 认证配置 {@code options.tokenProvider: beanName} 时，
 * 每次请求调用 {@link #getToken()} 取最新令牌（适合需要刷新、轮换的场景）。
 */
public interface TokenProvider {

    String getToken();
}

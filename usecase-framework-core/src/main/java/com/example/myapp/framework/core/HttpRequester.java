package com.example.myapp.framework.core;

/**
 * 外部 HTTP 调用步骤。内置实现 {@code HttpRequesterStep} 基于 RestClient，
 * 通过 {@code AuthHandler} SPI 支持 none / basic / bearer / apiKey / clientCredentials 认证。
 */
public interface HttpRequester extends Step {
}

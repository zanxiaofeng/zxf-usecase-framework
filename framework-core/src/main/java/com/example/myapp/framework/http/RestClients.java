package com.example.myapp.framework.http;

import java.net.http.HttpClient;
import java.time.Duration;

import lombok.experimental.UtilityClass;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 框架内 RestClient 统一构建：所有出站调用（httpRequester 业务调用、clientCredentials 取牌）
 * 共享同一超时基线——连接 3s / 读取 10s（缺省 JDK HttpClient 无限等待，必须显式配置）。
 */
@UtilityClass
public class RestClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 基于给定 builder 构建带默认超时的 RestClient（自定义拦截器等 Builder 配置保留） */
    public RestClient withDefaultTimeouts(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return builder.requestFactory(requestFactory).build();
    }
}

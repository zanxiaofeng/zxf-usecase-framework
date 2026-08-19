package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.BearerTokenAuthHandler;
import com.example.myapp.framework.auth.TokenProvider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Bearer Token 认证：静态令牌、动态令牌（TokenProvider Bean）与装配期 options 校验
 * （tokenProvider Bean 名拼错 fail-fast）。
 */
class BearerTokenAuthHandlerTest {

    @Test
    void applyWithStaticToken() {
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(null);
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);

        handler.apply(spec, Map.of("token", "t-123"));

        verify(spec).header(eq(HttpHeaders.AUTHORIZATION), eq("Bearer t-123"));
    }

    @Test
    void applyWithTokenProviderBean() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("creditTokenProvider", (TokenProvider) () -> "t-dyn");
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(beanFactory);
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);

        handler.apply(spec, Map.of("tokenProvider", "creditTokenProvider"));

        verify(spec).header(eq(HttpHeaders.AUTHORIZATION), eq("Bearer t-dyn"));
    }

    @Test
    void applyWithoutContainerReportsMisconfiguration() {
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(null);

        // standalone 使用（无 Spring 容器）却配置了 tokenProvider：报错须指明真实原因
        assertThatThrownBy(() -> handler.apply(
                mock(RestClient.RequestHeadersSpec.class), Map.of("tokenProvider", "creditTokenProvider")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creditTokenProvider")
                .hasMessageContaining("no Spring container");
    }

    @Test
    void validateRequiresTokenOrProvider() {
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(null);

        assertThatThrownBy(() -> handler.validate(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bearer")
                .hasMessageContaining("token");
    }

    @Test
    void validateFailsFastOnUnknownTokenProviderBean() {
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(new StaticListableBeanFactory());

        assertThatThrownBy(() -> handler.validate(Map.of("tokenProvider", "typoProvider")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typoProvider");
    }

    @Test
    void validatePassesWhenTokenProviderBeanExists() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("creditTokenProvider", (TokenProvider) () -> "t-dyn");
        BearerTokenAuthHandler handler = new BearerTokenAuthHandler(beanFactory);

        assertThatCode(() -> handler.validate(Map.of("tokenProvider", "creditTokenProvider")))
                .doesNotThrowAnyException();
    }
}

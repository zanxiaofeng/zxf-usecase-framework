package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.ApiKeyAuthHandler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * API Key 认证：自定义请求头写入与装配期 options 校验。
 */
class ApiKeyAuthHandlerTest {

    private final ApiKeyAuthHandler handler = new ApiKeyAuthHandler();

    @Test
    void applySetsConfiguredHeader() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);

        handler.apply(spec, Map.of("header", "X-Api-Key", "value", "k-123"));

        verify(spec).header(eq("X-Api-Key"), eq("k-123"));
    }

    @Test
    void validateRequiresHeaderAndValue() {
        assertThatThrownBy(() -> handler.validate(Map.of("header", "X-Api-Key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey")
                .hasMessageContaining("value");
    }
}

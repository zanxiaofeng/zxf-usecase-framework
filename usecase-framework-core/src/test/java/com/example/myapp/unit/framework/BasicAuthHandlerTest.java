package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.BasicAuthHandler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Basic 认证：Authorization 头拼接（Base64 username:password）与装配期 options 校验。
 */
class BasicAuthHandlerTest {

    private final BasicAuthHandler handler = new BasicAuthHandler();

    @Test
    void applyBuildsBasicAuthorizationHeader() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);

        handler.apply(spec, Map.of("username", "u", "password", "p"));

        // "u:p" 的 Base64 为 dTpw
        verify(spec).header(eq(HttpHeaders.AUTHORIZATION), eq("Basic dTpw"));
    }

    @Test
    void validateRequiresUsernameAndPassword() {
        assertThatThrownBy(() -> handler.validate(Map.of("username", "u")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basic")
                .hasMessageContaining("password");
    }
}

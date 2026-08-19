package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.ClientCredentialsAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsTokenSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OAuth2 Client Credentials：token 缓存命中（不过期不重复取牌）、过期后原子刷新、
 * scope 参与缓存分键（同 clientId 不同 scope 不共享令牌）。
 */
class ClientCredentialsAuthHandlerTest {

    private static final Map<String, Object> OPTIONS = Map.of(
            "tokenUrl", "https://auth/token",
            "clientId", "c1",
            "clientSecret", "s1");

    @Test
    void cachesTokenUntilExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClientCredentialsAuthHandler handler = new ClientCredentialsAuthHandler(new ClientCredentialsTokenSupplier(builder.build()));

        server.expect(requestTo("https://auth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"t-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(bearerHeader(handler, OPTIONS)).isEqualTo("Bearer t-1");
        assertThat(bearerHeader(handler, OPTIONS)).isEqualTo("Bearer t-1");   // 命中缓存

        server.verify();   // 只取了一次 token
    }

    @Test
    void refetchesTokenAfterExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClientCredentialsAuthHandler handler = new ClientCredentialsAuthHandler(new ClientCredentialsTokenSupplier(builder.build()));

        // expires_in=0：减去 60s 提前量后立即视为过期 → 第二次请求重新取牌
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-1\",\"expires_in\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(bearerHeader(handler, OPTIONS)).isEqualTo("Bearer t-1");
        assertThat(bearerHeader(handler, OPTIONS)).isEqualTo("Bearer t-2");

        server.verify();
    }

    @Test
    void cachesTokensPerScope() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClientCredentialsAuthHandler handler = new ClientCredentialsAuthHandler(new ClientCredentialsTokenSupplier(builder.build()));

        // 同 tokenUrl + clientId、不同 scope：各自取牌，不得共享缓存令牌
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-read\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-write\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(bearerHeader(handler, optionsWithScope("read"))).isEqualTo("Bearer t-read");
        assertThat(bearerHeader(handler, optionsWithScope("write"))).isEqualTo("Bearer t-write");
        assertThat(bearerHeader(handler, optionsWithScope("read"))).isEqualTo("Bearer t-read");   // 各自命中缓存

        server.verify();   // 共两次取牌
    }

    private static Map<String, Object> optionsWithScope(String scope) {
        return Map.of(
                "tokenUrl", "https://auth/token",
                "clientId", "c1",
                "clientSecret", "s1",
                "scope", scope);
    }

    private String bearerHeader(ClientCredentialsAuthHandler handler, Map<String, Object> options) {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        handler.apply(spec, options);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(spec).header(eq(HttpHeaders.AUTHORIZATION), value.capture());
        return value.getValue();
    }
}

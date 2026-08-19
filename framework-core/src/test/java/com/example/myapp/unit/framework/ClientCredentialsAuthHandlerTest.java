package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.ClientCredentialsAuthHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OAuth2 Client Credentials：token 缓存命中（不过期不重复取牌）与过期后原子刷新。
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
        ClientCredentialsAuthHandler handler = new ClientCredentialsAuthHandler(builder.build());

        server.expect(requestTo("https://auth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"t-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(bearerHeader(handler)).isEqualTo("Bearer t-1");
        assertThat(bearerHeader(handler)).isEqualTo("Bearer t-1");   // 命中缓存

        server.verify();   // 只取了一次 token
    }

    @Test
    void refetchesTokenAfterExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClientCredentialsAuthHandler handler = new ClientCredentialsAuthHandler(builder.build());

        // expires_in=0：减去 60s 提前量后立即视为过期 → 第二次请求重新取牌
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-1\",\"expires_in\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(bearerHeader(handler)).isEqualTo("Bearer t-1");
        assertThat(bearerHeader(handler)).isEqualTo("Bearer t-2");

        server.verify();
    }

    private String bearerHeader(ClientCredentialsAuthHandler handler) {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        handler.apply(spec, OPTIONS);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(spec).header(eq(HttpHeaders.AUTHORIZATION), value.capture());
        return value.getValue();
    }
}

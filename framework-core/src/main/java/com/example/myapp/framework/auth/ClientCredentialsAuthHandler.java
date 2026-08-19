package com.example.myapp.framework.auth;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 Client Credentials 认证：经 {@link ClientCredentialsTokenSupplier} 取 access_token
 * （含缓存），随请求携带 Bearer 头。
 * <pre>{@code
 * auth:
 *   scheme: clientCredentials
 *   options:
 *     tokenUrl: "https://auth.internal/oauth/token"
 *     clientId: "${svc.client-id}"
 *     clientSecret: "${svc.client-secret}"
 *     scope: "read"            # 可选
 * }</pre>
 *
 * <p>取牌协议与令牌缓存策略归 {@link ClientCredentialsTokenSupplier}（SRP 拆分：
 * 缓存策略变化不再波及认证头应用）。</p>
 */
@RequiredArgsConstructor
public final class ClientCredentialsAuthHandler implements AuthHandler {

    private final ClientCredentialsTokenSupplier tokenSupplier;

    public ClientCredentialsAuthHandler() {
        this(new ClientCredentialsTokenSupplier());
    }

    /** 自定义令牌端 RestClient（超时/拦截器）的便利入口，等价于注入 token supplier */
    public ClientCredentialsAuthHandler(RestClient tokenClient) {
        this(new ClientCredentialsTokenSupplier(tokenClient));
    }

    @Override
    public String scheme() {
        return "clientCredentials";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSupplier.obtainToken(options));
    }

    @Override
    public void validate(Map<String, Object> options) {
        if (options.get("tokenUrl") == null || options.get("clientId") == null || options.get("clientSecret") == null) {
            throw new IllegalArgumentException(
                    "auth scheme 'clientCredentials' requires options.tokenUrl / clientId / clientSecret");
        }
    }
}

package com.example.myapp.framework.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 Client Credentials 认证：自动向 tokenUrl 换取 access_token 并缓存
 * （提前 60 秒视为过期），随请求携带 Bearer 头。
 * <pre>{@code
 * auth:
 *   scheme: clientCredentials
 *   options:
 *     tokenUrl: "https://auth.internal/oauth/token"
 *     clientId: "${svc.client-id}"
 *     clientSecret: "${svc.client-secret}"
 *     scope: "read"            # 可选
 * }</pre>
 */
public final class ClientCredentialsAuthHandler implements AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsAuthHandler.class);
    private static final long EXPIRY_MARGIN_MILLIS = 60_000L;

    /** 独立的令牌端调用客户端，与业务 RestClient 隔离 */
    private final RestClient tokenClient = RestClient.create();
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return "clientCredentials";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + obtainToken(options));
    }

    @Override
    public void validate(Map<String, Object> options) {
        if (options.get("tokenUrl") == null || options.get("clientId") == null || options.get("clientSecret") == null) {
            throw new IllegalArgumentException(
                    "auth scheme 'clientCredentials' requires options.tokenUrl / clientId / clientSecret");
        }
    }

    private String obtainToken(Map<String, Object> options) {
        String cacheKey = options.get("tokenUrl") + "|" + options.get("clientId");
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        synchronized (tokenCache) {
            cached = tokenCache.get(cacheKey);
            if (cached != null && !cached.expired()) {
                return cached.value();
            }
            CachedToken fresh = fetchToken(options);
            tokenCache.put(cacheKey, fresh);
            return fresh.value();
        }
    }

    @SuppressWarnings("unchecked")
    private CachedToken fetchToken(Map<String, Object> options) {
        String tokenUrl = String.valueOf(options.get("tokenUrl"));
        String clientId = String.valueOf(options.get("clientId"));
        String clientSecret = String.valueOf(options.get("clientSecret"));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (options.get("scope") != null) {
            form.add("scope", String.valueOf(options.get("scope")));
        }
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        log.debug("fetching client-credentials token from {}", tokenUrl);
        Map<String, Object> response = tokenClient.post()
                .uri(tokenUrl)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number number
                ? number.longValue() : 3600L;
        long expiresAt = System.currentTimeMillis() + expiresIn * 1000L - EXPIRY_MARGIN_MILLIS;
        return new CachedToken(String.valueOf(response.get("access_token")), expiresAt);
    }

    private record CachedToken(String value, long expiresAtMillis) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }
}

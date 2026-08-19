package com.example.myapp.framework.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 *
 * <p>令牌缓存按 (tokenUrl, clientId) 分键，经 {@link ConcurrentHashMap#compute} 原子刷新：
 * 同 key 并发请求只触发一次取牌，不同 key 之间互不阻塞。取牌失败异常传播
 * （映射函数抛错时缓存条目不变，下次请求重试）。</p>
 */
public final class ClientCredentialsAuthHandler implements AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsAuthHandler.class);
    private static final long EXPIRY_MARGIN_MILLIS = 60_000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 独立的令牌端调用客户端，与业务 RestClient 隔离；默认连接 3s / 读取 10s，token 端点挂起不拖垮业务线程 */
    private final RestClient tokenClient;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /** 默认客户端带超时；需自定义超时/拦截器时经 {@link #ClientCredentialsAuthHandler(RestClient)} 注入 */
    public ClientCredentialsAuthHandler() {
        this(defaultTokenClient());
    }

    public ClientCredentialsAuthHandler(RestClient tokenClient) {
        this.tokenClient = tokenClient;
    }

    private static RestClient defaultTokenClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

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
        return tokenCache.compute(cacheKey, (key, cached) ->
                cached == null || cached.expired() ? fetchToken(options) : cached).value();
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

package com.example.myapp.framework.autoconfigure;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.auth.BasicAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsTokenSupplier;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.http.RestClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册表 Map 装配：内置 scheme / algorithm 全部落位；用户自定义 Bean（List 注入的只剩自定义）
 * 同名时覆盖内置（README 扩展点契约）。
 */
class AutoConfigurationMapTest {

    /** 模拟用户自定义实现：与内置 bearer 同名 scheme */
    private static final class CustomBearerHandler implements AuthHandler {
        @Override
        public String scheme() {
            return "bearer";
        }

        @Override
        public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
            // 测试桩：无需实现
        }
    }

    private static final class CustomBase64Codec implements Codec {
        @Override
        public String algorithm() {
            return "base64";
        }

        @Override
        public String encode(String plain) {
            return plain;   // 测试桩：无需实现真实编码
        }
    }

    private final UseCaseFrameworkAutoConfiguration configuration = new UseCaseFrameworkAutoConfiguration();

    @Test
    void builtInAuthHandlersAreRegisteredByDefault() {
        Map<String, AuthHandler> map = configuration.authHandlerMap(
                List.of(), null, new ClientCredentialsTokenSupplier(RestClients.withDefaultTimeouts(RestClient.builder())));

        assertThat(map).containsOnlyKeys("none", "basic", "bearer", "apiKey", "clientCredentials");
    }

    @Test
    void customAuthHandlerOverridesBuiltInByScheme() {
        Map<String, AuthHandler> map = configuration.authHandlerMap(
                List.of(new CustomBearerHandler()), null, new ClientCredentialsTokenSupplier(RestClients.withDefaultTimeouts(RestClient.builder())));

        assertThat(map.get("bearer")).isInstanceOf(CustomBearerHandler.class);
        assertThat(map.get("basic")).isInstanceOf(BasicAuthHandler.class);
    }

    @Test
    void customCodecOverridesBuiltInByAlgorithm() {
        Map<String, Codec> map = configuration.codecMap(List.of(new CustomBase64Codec()));

        assertThat(map.get("base64")).isInstanceOf(CustomBase64Codec.class);
        assertThat(map).containsKeys("base64url", "url", "hex", "md5", "sha256");
    }
}

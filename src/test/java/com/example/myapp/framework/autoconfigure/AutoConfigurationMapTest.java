package com.example.myapp.framework.autoconfigure;

import com.example.myapp.framework.auth.ApiKeyAuthHandler;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.auth.BasicAuthHandler;
import com.example.myapp.framework.auth.BearerTokenAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsAuthHandler;
import com.example.myapp.framework.auth.NoAuthHandler;
import com.example.myapp.framework.codec.Base64Codec;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.HexCodec;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置/自定义注册顺序：同名 scheme / algorithm 时<b>自定义覆盖内置</b>（README 扩展点契约）。
 * Bean 注入 List 的顺序随注册顺序不定（用户 @Component 通常先于 auto-config 注册），
 * 因此不能依赖天然顺序决定覆盖方向——内置必须显式排前。
 */
class AutoConfigurationMapTest {

    /** 模拟用户自定义实现：不在 SPI 接口包（com.example.myapp.framework.auth / codec）内 */
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
    void customAuthHandlerOverridesBuiltInRegardlessOfInjectionOrder() {
        List<AuthHandler> handlers = List.of(
                new CustomBearerHandler(),                      // 自定义（同名覆盖目标），模拟注入顺序在前
                new NoAuthHandler(),
                new ApiKeyAuthHandler(),
                new BasicAuthHandler(),
                new BearerTokenAuthHandler(null),               // 内置 bearer
                new ClientCredentialsAuthHandler());

        Map<String, AuthHandler> map = configuration.authHandlerMap(handlers);

        assertThat(map.get("bearer")).isInstanceOf(CustomBearerHandler.class);
        assertThat(map).containsKeys("none", "basic", "apiKey", "clientCredentials");
    }

    @Test
    void customCodecOverridesBuiltInRegardlessOfInjectionOrder() {
        List<Codec> codecs = List.of(
                new CustomBase64Codec(),                        // 自定义（同名覆盖目标），模拟注入顺序在前
                new Base64Codec(),
                new HexCodec());

        Map<String, Codec> map = configuration.codecMap(codecs);

        assertThat(map.get("base64")).isInstanceOf(CustomBase64Codec.class);
    }
}

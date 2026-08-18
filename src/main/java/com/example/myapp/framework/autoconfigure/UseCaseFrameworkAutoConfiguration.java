package com.example.myapp.framework.autoconfigure;

import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.assemble.UseCaseAssembler;
import com.example.myapp.framework.auth.ApiKeyAuthHandler;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.auth.BasicAuthHandler;
import com.example.myapp.framework.auth.BearerTokenAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsAuthHandler;
import com.example.myapp.framework.auth.NoAuthHandler;
import com.example.myapp.framework.codec.Base64Codec;
import com.example.myapp.framework.codec.Base64UrlCodec;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.DigestCodec;
import com.example.myapp.framework.codec.HexCodec;
import com.example.myapp.framework.codec.UrlCodec;
import com.example.myapp.framework.config.UseCaseProperties;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseInvoker;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.CodecStepFactory;
import com.example.myapp.framework.steps.HttpRequesterStepFactory;
import com.example.myapp.framework.steps.LoggingStepFactory;
import com.example.myapp.framework.steps.SpelStepFactory;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import com.example.myapp.framework.steps.ValidatorStepFactory;
import com.example.myapp.framework.web.UseCaseRouterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用例编排框架自动装配入口（经 AutoConfiguration.imports 注册）：
 * <ol>
 *   <li>装配内置 StepFactory（dataLoader / dataTransformer / dataSaver / httpRequester）；</li>
 *   <li>装配内置 AuthHandler（none / basic / bearer / apiKey / clientCredentials）；</li>
 *   <li>读取 {@code usecase.definitions} 构建 UseCaseRegistry；</li>
 *   <li>生成 RouterFunction 完成 endpoint ↔ usecase 绑定。</li>
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(UseCaseProperties.class)
public class UseCaseFrameworkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UseCaseFrameworkAutoConfiguration.class);

    /** SpEL 求值器（可访问容器 Bean：@repository 等出端口） */
    @Bean
    StepExpressionEvaluator stepExpressionEvaluator(BeanFactory beanFactory) {
        return new StepExpressionEvaluator(beanFactory);
    }

    /** HttpRequester 步骤专用 RestClient：连接 3s / 读取 10s，可通过自定义同名 Bean 覆盖 */
    @Bean("useCaseRestClient")
    @ConditionalOnMissingBean(name = "useCaseRestClient")
    RestClient useCaseRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return builder.requestFactory(requestFactory).build();
    }

    // ------------------------------------------------------------------
    // 内置 AuthHandler（同名 scheme 的自定义 Bean 在收集时覆盖内置实现）
    // ------------------------------------------------------------------

    @Bean
    AuthHandler noneAuthHandler() {
        return new NoAuthHandler();
    }

    @Bean
    AuthHandler basicAuthHandler() {
        return new BasicAuthHandler();
    }

    @Bean
    AuthHandler bearerTokenAuthHandler(BeanFactory beanFactory) {
        return new BearerTokenAuthHandler(beanFactory);
    }

    @Bean
    AuthHandler apiKeyAuthHandler() {
        return new ApiKeyAuthHandler();
    }

    @Bean
    AuthHandler clientCredentialsAuthHandler() {
        return new ClientCredentialsAuthHandler();
    }

    @Bean
    Map<String, AuthHandler> authHandlerMap(List<AuthHandler> handlers) {
        Map<String, AuthHandler> map = new LinkedHashMap<>();
        for (AuthHandler handler : handlers) {
            map.put(handler.scheme(), handler);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // 内置 StepFactory
    // ------------------------------------------------------------------

    @Bean
    StepFactory dataLoaderStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataLoader", SpelStepFactory.Role.LOADER, evaluator);
    }

    @Bean
    StepFactory dataTransformerStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataTransformer", SpelStepFactory.Role.TRANSFORMER, evaluator);
    }

    @Bean
    StepFactory dataSaverStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataSaver", SpelStepFactory.Role.SAVER, evaluator);
    }

    @Bean
    StepFactory httpRequesterStepFactory(RestClient useCaseRestClient, Map<String, AuthHandler> authHandlerMap,
                                         StepExpressionEvaluator evaluator) {
        return new HttpRequesterStepFactory(useCaseRestClient, authHandlerMap, evaluator);
    }

    @Bean
    StepFactory starterStepFactory(StepExpressionEvaluator evaluator) {
        return new StarterStepFactory(evaluator);
    }

    @Bean
    StepFactory loggingStepFactory(StepExpressionEvaluator evaluator) {
        return new LoggingStepFactory(evaluator);
    }

    /**
     * 子用例调用步骤。registry 经 ObjectProvider 延迟解析：
     * step 创建发生在装配期（registry Bean 尚未就绪），运行时首次调用时才解析。
     */
    @Bean
    StepFactory subUseCaseStepFactory(ObjectProvider<UseCaseRegistry> registryProvider,
                                      StepExpressionEvaluator evaluator) {
        return new SubUseCaseStepFactory(registryProvider::getObject, evaluator);
    }

    /** 校验步骤（schema / expression 二选一）。优先复用容器中的 Jackson ObjectMapper。 */
    @Bean
    StepFactory validatorStepFactory(StepExpressionEvaluator evaluator,
                                     ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ValidatorStepFactory(evaluator, objectMapper);
    }

    // ------------------------------------------------------------------
    // 内置 Codec（注册自定义 Codec Bean 即可扩展算法）
    // ------------------------------------------------------------------

    @Bean
    Codec base64Codec() {
        return new Base64Codec();
    }

    @Bean
    Codec base64UrlCodec() {
        return new Base64UrlCodec();
    }

    @Bean
    Codec urlCodec() {
        return new UrlCodec();
    }

    @Bean
    Codec hexCodec() {
        return new HexCodec();
    }

    @Bean
    Codec md5DigestCodec() {
        return new DigestCodec("md5");
    }

    @Bean
    Codec sha256DigestCodec() {
        return new DigestCodec("sha256");
    }

    @Bean
    Map<String, Codec> codecMap(List<Codec> codecs) {
        Map<String, Codec> map = new LinkedHashMap<>();
        for (Codec codec : codecs) {
            map.put(codec.algorithm(), codec);
        }
        return map;
    }

    @Bean
    StepFactory encoderStepFactory(Map<String, Codec> codecMap, StepExpressionEvaluator evaluator) {
        return new CodecStepFactory("encoder", CodecStepFactory.Direction.ENCODE, codecMap, evaluator);
    }

    @Bean
    StepFactory decoderStepFactory(Map<String, Codec> codecMap, StepExpressionEvaluator evaluator) {
        return new CodecStepFactory("decoder", CodecStepFactory.Direction.DECODE, codecMap, evaluator);
    }

    // ------------------------------------------------------------------
    // 装配与路由绑定
    // ------------------------------------------------------------------

    @Bean
    UseCaseRegistry useCaseRegistry(UseCaseProperties properties, BeanFactory beanFactory,
                                    List<StepFactory> stepFactories) {
        return new UseCaseAssembler(beanFactory, stepFactories).assemble(properties.definitions());
    }

    /**
     * 子用例 Java 调用门面：业务代码注入本 Bean（或继承 AbstractUseCaseClient 得到类型化客户端），
     * 即可以编程方式调用 shared 用例。管道内自动继承当前上下文（StepContextHolder）。
     * registry 经 ObjectProvider 延迟解析：避免 registry → ref step → client → invoker 的创建循环。
     */
    @Bean
    UseCaseInvoker useCaseInvoker(ObjectProvider<UseCaseRegistry> registryProvider) {
        return new UseCaseInvoker(registryProvider::getObject);
    }

    @Bean
    RouterFunction<ServerResponse> useCaseRouterFunction(UseCaseRegistry registry, UseCaseProperties properties) {
        return new UseCaseRouterFactory(properties.errorMappings()).build(registry);
    }

    /** 启动日志：打印装配结果（路由表；shared 用例无 endpoint，标记为 shared） */
    @Bean
    ApplicationRunner useCaseRouteLogger(UseCaseRegistry registry) {
        return args -> {
            for (UseCase useCase : registry.all()) {
                List<String> stepNames = useCase.getSteps().stream().map(step -> step.name()).toList();
                if (useCase.isShared()) {
                    log.info("shared usecase [{}] steps={}", useCase.getId(), stepNames);
                } else {
                    log.info("route: {} {} -> usecase [{}] steps={}",
                            useCase.getEndpoint().method(), useCase.getEndpoint().path(), useCase.getId(), stepNames);
                }
            }
        };
    }
}

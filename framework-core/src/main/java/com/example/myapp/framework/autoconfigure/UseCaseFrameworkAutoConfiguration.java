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
import com.example.myapp.framework.assemble.UseCaseProperties;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.CodecStep;
import com.example.myapp.framework.steps.CodecStepFactory;
import com.example.myapp.framework.steps.EventPublisherStepFactory;
import com.example.myapp.framework.steps.HttpRequesterStepFactory;
import com.example.myapp.framework.steps.LoggingStepFactory;
import com.example.myapp.framework.steps.SpelStepFactory;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import com.example.myapp.framework.steps.ValidatorStepFactory;
import com.example.myapp.framework.web.UseCaseRouterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用例编排框架自动装配入口（经 AutoConfiguration.imports 注册）：
 * <ol>
 *   <li>装配内置 StepFactory（dataLoader / dataTransformer / dataSaver / httpRequester / starter /
 *       logging / usecase / validator / encoder / decoder）；</li>
 *   <li>装配内置 AuthHandler（none / basic / bearer / apiKey / clientCredentials）与 Codec；</li>
 *   <li>读取 {@code usecase.definitions} 构建 UseCaseRegistry；</li>
 *   <li>Servlet Web 环境下生成 RouterFunction 完成 endpoint ↔ usecase 绑定。</li>
 * </ol>
 *
 * <p><b>条件化装配（starter 契约）</b>：</p>
 * <ul>
 *   <li>所有内置 Bean 均带 {@code @ConditionalOnMissingBean}——用户定义同名 Bean 方法
 *       （或同类型的 evaluator / registry / invoker）即可整体替换内置实现；</li>
 *   <li>仅 {@code useCaseRouterFunction} / {@code useCaseRouteLogger} 要求 Servlet Web 环境
 *       （{@code @ConditionalOnWebApplication}）——非 Web 应用引入本 jar 时管道装配
 *       （Registry / UseCaseInvoker / StepFactory）仍然可用，可经
 *       {@code UseCaseInvoker#invokeStandalone} 在管道外编程调用用例；</li>
 *   <li>自定义 AuthHandler / Codec 的<em>同名 scheme / algorithm 覆盖</em>走收集顺序机制
 *       （见 {@link #frameworkProvidedFirst}），与 Bean 替换机制正交。</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(UseCaseProperties.class)
public class UseCaseFrameworkAutoConfiguration {

    /** SpEL 求值器（可访问容器 Bean：@repository 等出端口）；同类型自定义 Bean 可整体替换 */
    @Bean
    @ConditionalOnMissingBean(StepExpressionEvaluator.class)
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
    // 内置 AuthHandler（同名 scheme 的自定义 Bean 在收集时覆盖内置实现；
    // 同名 Bean 方法则经 @ConditionalOnMissingBean 直接替换内置实现）
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(name = "noneAuthHandler")
    AuthHandler noneAuthHandler() {
        return new NoAuthHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "basicAuthHandler")
    AuthHandler basicAuthHandler() {
        return new BasicAuthHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "bearerTokenAuthHandler")
    AuthHandler bearerTokenAuthHandler(BeanFactory beanFactory) {
        return new BearerTokenAuthHandler(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "apiKeyAuthHandler")
    AuthHandler apiKeyAuthHandler() {
        return new ApiKeyAuthHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "clientCredentialsAuthHandler")
    AuthHandler clientCredentialsAuthHandler() {
        return new ClientCredentialsAuthHandler();
    }

    @Bean(name = "authHandlerMap")
    @ConditionalOnMissingBean(name = "authHandlerMap")
    Map<String, AuthHandler> authHandlerMap(List<AuthHandler> handlers) {
        // 内置先注册、自定义后注册：同名 scheme 时自定义覆盖内置（扩展契约，见 README 扩展点）。
        // Bean 注入 List 的顺序随注册顺序不定，不能依赖其天然顺序决定覆盖方向。
        Map<String, AuthHandler> map = new LinkedHashMap<>();
        frameworkProvidedFirst(handlers, AuthHandler.class).forEach(handler -> map.put(handler.scheme(), handler));
        return map;
    }

    // ------------------------------------------------------------------
    // 内置 StepFactory
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(name = "dataLoaderStepFactory")
    StepFactory dataLoaderStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataLoader", SpelStepFactory.Role.LOADER, evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataTransformerStepFactory")
    StepFactory dataTransformerStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataTransformer", SpelStepFactory.Role.TRANSFORMER, evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataSaverStepFactory")
    StepFactory dataSaverStepFactory(StepExpressionEvaluator evaluator) {
        return new SpelStepFactory("dataSaver", SpelStepFactory.Role.SAVER, evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "httpRequesterStepFactory")
    StepFactory httpRequesterStepFactory(RestClient useCaseRestClient, Map<String, AuthHandler> authHandlerMap,
                                         StepExpressionEvaluator evaluator) {
        return new HttpRequesterStepFactory(useCaseRestClient, authHandlerMap, evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "starterStepFactory")
    StepFactory starterStepFactory(StepExpressionEvaluator evaluator) {
        return new StarterStepFactory(evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "loggingStepFactory")
    StepFactory loggingStepFactory(StepExpressionEvaluator evaluator) {
        return new LoggingStepFactory(evaluator);
    }

    /**
     * 子用例调用步骤，执行委托 {@link UseCaseInvoker}（与其 Java 调用入口共享实现）。
     * invoker 经 ObjectProvider 延迟解析：step 创建发生在装配期（invoker 依赖的 registry
     * Bean 尚未就绪），运行时首次调用时才解析。
     */
    @Bean
    @ConditionalOnMissingBean(name = "subUseCaseStepFactory")
    StepFactory subUseCaseStepFactory(ObjectProvider<UseCaseInvoker> invokerProvider,
                                      StepExpressionEvaluator evaluator) {
        return new SubUseCaseStepFactory(invokerProvider::getObject, evaluator);
    }

    /**
     * 事件发布步骤：活动事务内注册 afterCommit、提交成功后才外发（回滚不发布）；
     * 无事务时立即发布。发布器延迟解析（配置 publisher Bean 名或取唯一实现）。
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventPublisherStepFactory")
    StepFactory eventPublisherStepFactory(BeanFactory beanFactory, StepExpressionEvaluator evaluator) {
        return new EventPublisherStepFactory(beanFactory, evaluator);
    }

    /** 校验步骤（schema / expression 二选一）。优先复用容器中的 Jackson ObjectMapper。 */
    @Bean
    @ConditionalOnMissingBean(name = "validatorStepFactory")
    StepFactory validatorStepFactory(StepExpressionEvaluator evaluator,
                                     ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ValidatorStepFactory(evaluator, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "encoderStepFactory")
    StepFactory encoderStepFactory(Map<String, Codec> codecMap, StepExpressionEvaluator evaluator) {
        return new CodecStepFactory("encoder", CodecStep.Direction.ENCODE, codecMap, evaluator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "decoderStepFactory")
    StepFactory decoderStepFactory(Map<String, Codec> codecMap, StepExpressionEvaluator evaluator) {
        return new CodecStepFactory("decoder", CodecStep.Direction.DECODE, codecMap, evaluator);
    }

    // ------------------------------------------------------------------
    // 内置 Codec（注册自定义 Codec Bean 即可扩展算法）
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(name = "base64Codec")
    Codec base64Codec() {
        return new Base64Codec();
    }

    @Bean
    @ConditionalOnMissingBean(name = "base64UrlCodec")
    Codec base64UrlCodec() {
        return new Base64UrlCodec();
    }

    @Bean
    @ConditionalOnMissingBean(name = "urlCodec")
    Codec urlCodec() {
        return new UrlCodec();
    }

    @Bean
    @ConditionalOnMissingBean(name = "hexCodec")
    Codec hexCodec() {
        return new HexCodec();
    }

    @Bean
    @ConditionalOnMissingBean(name = "md5DigestCodec")
    Codec md5DigestCodec() {
        return new DigestCodec("md5");
    }

    @Bean
    @ConditionalOnMissingBean(name = "sha256DigestCodec")
    Codec sha256DigestCodec() {
        return new DigestCodec("sha256");
    }

    @Bean(name = "codecMap")
    @ConditionalOnMissingBean(name = "codecMap")
    Map<String, Codec> codecMap(List<Codec> codecs) {
        // 同 authHandlerMap：自定义算法后注册，同名覆盖内置
        Map<String, Codec> map = new LinkedHashMap<>();
        frameworkProvidedFirst(codecs, Codec.class).forEach(codec -> map.put(codec.algorithm(), codec));
        return map;
    }

    /**
     * 内置实现（与 SPI 接口同包，即 framework.auth / framework.codec）排前、用户自定义排后，
     * 保证后续 put 覆盖时自定义胜出。
     */
    private static <T> List<T> frameworkProvidedFirst(List<T> implementations, Class<T> spi) {
        return implementations.stream()
                .sorted(Comparator.comparing(implementation ->
                        !implementation.getClass().getPackageName().startsWith(spi.getPackageName())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 装配与路由绑定
    // ------------------------------------------------------------------

    /** 用例注册表（同类型自定义 Bean 可整体替换，接管装配流程） */
    @Bean
    @ConditionalOnMissingBean(UseCaseRegistry.class)
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
    @ConditionalOnMissingBean(UseCaseInvoker.class)
    UseCaseInvoker useCaseInvoker(ObjectProvider<UseCaseRegistry> registryProvider) {
        return new UseCaseInvoker(registryProvider::getObject);
    }

    /**
     * RouterFunction 路由绑定（仅 Servlet Web 环境装配；按 Bean 名条件化——
     * 应用内可有多个 RouterFunction Bean 共存，不能按类型判断）。
     */
    @Bean(name = "useCaseRouterFunction")
    @ConditionalOnMissingBean(name = "useCaseRouterFunction")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    RouterFunction<ServerResponse> useCaseRouterFunction(UseCaseRegistry registry, UseCaseProperties properties,
                                                         ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new UseCaseRouterFactory(properties.errorMappings(), objectMapper).build(registry);
    }

    /**
     * 启动日志：打印装配结果（路由表；shared 用例无 endpoint，标记为 shared）。
     * 仅 Servlet Web 环境装配（非 Web 应用无路由可打印）。
     */
    @Bean(name = "useCaseRouteLogger")
    @ConditionalOnMissingBean(name = "useCaseRouteLogger")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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

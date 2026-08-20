package com.example.myapp.framework.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.assemble.UseCaseAssembler;
import com.example.myapp.framework.assemble.UseCaseProperties;
import com.example.myapp.framework.auth.ApiKeyAuthHandler;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.auth.BasicAuthHandler;
import com.example.myapp.framework.auth.BearerTokenAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsAuthHandler;
import com.example.myapp.framework.auth.ClientCredentialsTokenSupplier;
import com.example.myapp.framework.auth.NoAuthHandler;
import com.example.myapp.framework.codec.Base64Codec;
import com.example.myapp.framework.codec.Base64UrlCodec;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.DigestCodec;
import com.example.myapp.framework.codec.HexCodec;
import com.example.myapp.framework.codec.UrlCodec;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.http.RestClients;
import com.example.myapp.framework.steps.CodecStep;
import com.example.myapp.framework.steps.CodecStepFactory;
import com.example.myapp.framework.steps.EventPublisherStepFactory;
import com.example.myapp.framework.steps.HttpRequesterStepFactory;
import com.example.myapp.framework.steps.LoggingStepFactory;
import com.example.myapp.framework.steps.SpelStepFactory;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import com.example.myapp.framework.steps.ValidatorStepFactory;
import com.example.myapp.framework.web.ErrorResponseMapper;
import com.example.myapp.framework.web.UseCaseRouterFactory;

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
 *   <li>StepFactory / evaluator / registry / invoker / RestClient / 两个注册表 Map 均带
 *       {@code @ConditionalOnMissingBean}——用户定义同名 Bean（或同类型的 evaluator /
 *       registry / invoker / tokenSupplier）即可整体替换内置实现；</li>
 *   <li>仅 {@code useCaseRouterFunction} / {@code useCaseRouteLogger} 要求 Servlet Web 环境
 *       （{@code @ConditionalOnWebApplication}）——非 Web 应用引入本 jar 时管道装配
 *       （Registry / UseCaseInvoker / StepFactory）仍然可用，可经
 *       {@code UseCaseInvoker#invokeStandalone} 在管道外编程调用用例；</li>
 *   <li>内置 AuthHandler / Codec 不作为独立 Bean，在注册表 Map 工厂方法内直接落位；
 *       用户注册同 scheme / algorithm 的自定义 Bean 即覆盖内置（见各 Map 方法）。</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(UseCaseProperties.class)
public class UseCaseFrameworkAutoConfiguration {

    /**
     * SpEL 求值器（可访问容器 Bean：@repository 等出端口）；同类型自定义 Bean 可整体替换
     */
    @Bean
    @ConditionalOnMissingBean(StepExpressionEvaluator.class)
    StepExpressionEvaluator stepExpressionEvaluator(BeanFactory beanFactory) {
        return new StepExpressionEvaluator(beanFactory);
    }

    /**
     * HttpRequester 步骤专用 RestClient：连接 3s / 读取 10s（统一基线见 {@link RestClients}），可通过自定义同名 Bean 覆盖
     */
    @Bean("useCaseRestClient")
    @ConditionalOnMissingBean(name = "useCaseRestClient")
    RestClient useCaseRestClient(RestClient.Builder builder) {
        return RestClients.withDefaultTimeouts(builder);
    }

    // ------------------------------------------------------------------
    // 内置 AuthHandler（不作为独立 Bean：在 map 工厂方法内直接注册，
    // List<AuthHandler> 注入的只剩用户自定义 Bean，同名 scheme 覆盖内置不言自明）
    // ------------------------------------------------------------------

    /**
     * Client Credentials 令牌供应器（取牌 + 缓存）：独立 Bean 暴露——同类型自定义 Bean
     * 可替换取牌客户端（超时/拦截器），令牌缓存在容器内唯一共享。
     */
    @Bean
    @ConditionalOnMissingBean(ClientCredentialsTokenSupplier.class)
    ClientCredentialsTokenSupplier clientCredentialsTokenSupplier() {
        return new ClientCredentialsTokenSupplier(RestClients.withDefaultTimeouts(RestClient.builder()));
    }

    /**
     * 内置 scheme 先落位、自定义 Bean 后覆盖：同名 scheme 时自定义胜出（扩展契约，见 README 扩展点）
     */
    @Bean(name = "authHandlerMap")
    @ConditionalOnMissingBean(name = "authHandlerMap")
    Map<String, AuthHandler> authHandlerMap(List<AuthHandler> customHandlers, BeanFactory beanFactory,
                                            ClientCredentialsTokenSupplier tokenSupplier) {

        return Stream.concat(
                        Stream.of(new NoAuthHandler(), new BasicAuthHandler(), new BearerTokenAuthHandler(beanFactory),
                                new ApiKeyAuthHandler(), new ClientCredentialsAuthHandler(tokenSupplier)),
                        customHandlers.stream())
                .collect(Collectors.toMap(AuthHandler::scheme, Function.identity(),
                        (builtIn, custom) -> custom, LinkedHashMap::new));
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
    StepFactory eventPublisherStepFactory(ListableBeanFactory beanFactory, StepExpressionEvaluator evaluator) {
        return new EventPublisherStepFactory(beanFactory, evaluator);
    }

    /**
     * 校验步骤（schema / expression 二选一）。优先复用容器中的 Jackson ObjectMapper。
     */
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
    // 内置 Codec（同 AuthHandler：非独立 Bean，自定义 Bean 同名 algorithm 覆盖内置）
    // ------------------------------------------------------------------

    /**
     * 内置 algorithm 先落位、自定义 Bean 后覆盖：同名 algorithm 时自定义胜出（扩展契约，见 README 扩展点）
     */
    @Bean(name = "codecMap")
    @ConditionalOnMissingBean(name = "codecMap")
    Map<String, Codec> codecMap(List<Codec> customCodecs) {
        return Stream.concat(
                        Stream.of(new Base64Codec(), new Base64UrlCodec(), new UrlCodec(),
                                new HexCodec(), new DigestCodec("md5"), new DigestCodec("sha256")),
                        customCodecs.stream())
                .collect(Collectors.toMap(Codec::algorithm, Function.identity(),
                        (builtIn, custom) -> custom, LinkedHashMap::new));
    }

    // ------------------------------------------------------------------
    // 装配与路由绑定
    // ------------------------------------------------------------------

    /**
     * 用例注册表（同类型自定义 Bean 可整体替换，接管装配流程）
     */
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
        return new UseCaseRouterFactory(new ErrorResponseMapper(properties.errorMappings()), objectMapper)
                .build(registry);
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
                    continue;
                }
                log.info("route: {} {} -> usecase [{}] steps={}",
                        useCase.getEndpoint().method(), useCase.getEndpoint().path(), useCase.getId(), stepNames);
            }
        };
    }
}

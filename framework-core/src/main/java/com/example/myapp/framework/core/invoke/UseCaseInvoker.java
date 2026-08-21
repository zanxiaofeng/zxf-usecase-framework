package com.example.myapp.framework.core.invoke;

import java.util.UUID;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.MdcScopes;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepContextHolder;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;

/**
 * 子用例 Java 调用门面：业务代码（自定义 Step Bean、领域服务、定时任务、Controller……）
 * 注入本 Bean 即可以编程方式调用任意已装配用例（设计上主要面向 shared 用例）。
 *
 * <p>三种调用语义：</p>
 * <ul>
 *   <li>{@link #invoke} —— 若当前线程正处于某管道内（经 {@link StepContextHolder} 感知），
 *       子用例<b>共享</b>当前上下文（vars/biz 互通、traceId 继承），执行前后自动保存/恢复父 payload；
 *       否则退化为 {@link #invokeStandalone}；</li>
 *   <li>{@link #invokeIsolated} —— 隔离调用：子用例在全新 vars 中执行，biz <b>拷贝继承</b>
 *       （子可读父的 businessId/traceId，子的修改不回传）；</li>
 *   <li>{@link #invokeStandalone} —— 独立调用：全新上下文（无入站请求），自动种子化 traceId，
 *       适用于管道外场景（调度任务、消息消费、普通 Service）。</li>
 * </ul>
 *
 * <p>与 YAML 中 {@code type: usecase} 步骤的区别：YAML 串联模式（未配 {@code as}）会让子结果
 * 成为父 payload；Java 调用是<b>函数式取值</b>——父 payload 总是被恢复，结果经返回值返回。</p>
 *
 * <p>异常语义与子用例 step 一致：子用例的领域异常沿 {@link StepExecutionException} 包装链原样上抛。</p>
 *
 * <p><b>为何注入 Supplier 而非 Registry 本体</b>：引用 invoker 的自定义 Step Bean 会在装配期
 * 被 registry 的创建过程触及（registry → ref step → client → invoker），若 invoker 直接持有
 * registry 会形成 Bean 创建循环；延迟解析（首次调用时解析并缓存）切断该循环。</p>
 */
@RequiredArgsConstructor
@Slf4j
public final class UseCaseInvoker {

    private final Supplier<UseCaseRegistry> registrySupplier;
    private volatile @Nullable UseCaseRegistry registry;

    private UseCaseRegistry registry() {
        UseCaseRegistry current = registry;
        if (current == null) {
            current = registrySupplier.get();
            registry = current;
        }
        return current;
    }

    /**
     * 调用子用例。管道内共享当前上下文（父 payload 自动恢复）；管道外等同独立调用。
     *
     * @param useCaseId 目标用例 id（未注册抛 IllegalArgumentException）
     * @param input     子用例初始 payload
     * @return 子用例最终 payload
     */
    public @Nullable Object invoke(String useCaseId, @Nullable Object input) {
        StepContext current = StepContextHolder.current();
        if (current == null) {
            // 静默退化会把「异步边界丢失 ThreadLocal」误当正常独立调用（traceId/biz 断链），留 DEBUG 痕迹便于排查
            log.debug("invoke [{}] outside pipeline: standalone semantics, traceId 不继承", useCaseId);
            return invokeStandalone(useCaseId, input);
        }
        return invoke(useCaseId, input, current);
    }

    /**
     * 严格共享调用：要求当前线程处于管道内，否则抛 IllegalStateException。
     * 异步边界（@Async / CompletableFuture / 虚拟线程切换）ThreadLocal 不随线程迁移——
     * 跨边界请显式使用 {@link #invokeStandalone}，并把 traceId 与必要 biz 键作为 input 显式传入。
     */
    public @Nullable Object invokeShared(String useCaseId, @Nullable Object input) {
        StepContext current = StepContextHolder.current();
        if (current == null) {
            throw new IllegalStateException(
                    "invokeShared requires a pipeline context (no StepContext on current thread); "
                            + "across async boundaries use invokeStandalone and pass traceId explicitly");
        }
        return invoke(useCaseId, input, current);
    }

    /** 在指定父上下文中共享调用：vars/biz 互通，父 payload 执行后恢复。 */
    public @Nullable Object invoke(String useCaseId, @Nullable Object input, StepContext parentContext) {
        UseCase target = registry().require(useCaseId);
        Object parentPayload = parentContext.getPayload();
        parentContext.setPayload(input);
        try {
            return target.execute(parentContext);
        } finally {
            parentContext.setPayload(parentPayload);
        }
    }

    /** 隔离调用（基于当前上下文；管道外退化为独立调用）。 */
    public @Nullable Object invokeIsolated(String useCaseId, @Nullable Object input) {
        StepContext current = StepContextHolder.current();
        return current == null ? invokeStandalone(useCaseId, input) : invokeIsolated(useCaseId, input, current);
    }

    /** 在指定父上下文基础上的隔离调用：vars 全新，biz 拷贝继承（子的修改不回传）；MDC 现场返回时恢复。 */
    public @Nullable Object invokeIsolated(String useCaseId, @Nullable Object input, StepContext parentContext) {
        UseCase target = registry().require(useCaseId);
        // biz 是 Map 拷贝而 MDC 是线程级单例：子管道内 starter 的 MDC 写入须随返回回滚，
        // 否则父管道后续日志输出子用例的 biz 值
        return MdcScopes.withRestoration(() -> {
            StepContext childContext = parentContext.newChildContext();
            childContext.inheritBizFrom(parentContext);
            childContext.setPayload(input);
            return target.execute(childContext);
        });
    }

    /** 独立调用：全新上下文（无入站请求），自动种子化 traceId（biz 区），管道外场景使用；MDC 现场返回时恢复。 */
    public @Nullable Object invokeStandalone(String useCaseId, @Nullable Object input) {
        UseCase target = registry().require(useCaseId);
        return MdcScopes.withRestoration(() -> {
            StepContext context = StepContext.standalone();
            context.putBiz(StepContext.TRACE_ID_KEY, UUID.randomUUID().toString());
            context.setPayload(input);
            return target.execute(context);
        });
    }
}

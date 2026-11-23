package com.example.usecase.framework.core.invoke;

import java.util.UUID;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import com.example.usecase.framework.core.context.MdcScopes;
import com.example.usecase.framework.core.context.StepContext;
import com.example.usecase.framework.core.dataflow.DataflowRecorder;
import com.example.usecase.framework.core.context.StepContextHolder;
import com.example.usecase.framework.core.UseCase;
import com.example.usecase.framework.core.UseCaseRegistry;

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
 * <p><b>运行期深度保护</b>：装配期的循环引用检测只覆盖 YAML 引用图（type=usecase 的 ref）；
 * Java 编程调用可形成图中不可见的递归（如 ref Step Bean 内回调 invoker 再入自身用例）。
 * 每个调用经 {@link #guarded(Supplier)} 以 ThreadLocal 深度计数守卫，超过
 * {@link #MAX_INVOCATION_DEPTH} 抛 {@link IllegalStateException}（明确报错优于 StackOverflowError）。</p>
 *
 * <p><b>为何注入 Supplier 而非 Registry 本体</b>：引用 invoker 的自定义 Step Bean 会在装配期
 * 被 registry 的创建过程触及（registry → ref step → client → invoker），若 invoker 直接持有
 * registry 会形成 Bean 创建循环；延迟解析（首次调用时解析并缓存）切断该循环。</p>
 */
@RequiredArgsConstructor
@Slf4j
public final class UseCaseInvoker {

    /** 嵌套调用深度上限：正常编排远达不到（demo 最深 2 层）；超出基本可断定编程递归 */
    static final int MAX_INVOCATION_DEPTH = 32;

    /** 当前线程的用例调用深度（跨 YAML 嵌套与 Java 调用统一计数；管道本身由 root execute 起算 0） */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

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
        return guarded(() -> {
            Object parentPayload = parentContext.getPayload();
            parentContext.setPayload(input);
            try {
                return target.execute(parentContext);
            } finally {
                parentContext.setPayload(parentPayload);
            }
        });
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
        return guarded(() -> MdcScopes.withRestoration(() -> {
            StepContext childContext = parentContext.newChildContext();
            childContext.inheritBizFrom(parentContext);
            childContext.setPayload(input);
            return target.execute(childContext);
        }));
    }

    /** 独立调用：全新上下文（无入站请求），自动种子化 traceId（biz 区），管道外场景使用；MDC 现场返回时恢复。 */
    public @Nullable Object invokeStandalone(String useCaseId, @Nullable Object input) {
        UseCase target = registry().require(useCaseId);
        return guarded(() -> MdcScopes.withRestoration(() -> {
            StepContext context = StepContext.standalone();
            StepContext current = StepContextHolder.current();
            if (current != null) {
                // 管道内调用（数据链录制场景）：录制器随新上下文传递，子链事件并入同一 trace
                DataflowRecorder recorder = current.recorder();
                if (recorder != null) {
                    context.attach(recorder);
                }
            }
            context.putBiz(StepContext.TRACE_ID_KEY, UUID.randomUUID().toString());
            context.setPayload(input);
            return target.execute(context);
        }));
    }

    /**
     * 调用深度守卫：装配期环检测只覆盖 YAML 引用图，Java 编程调用（ref Step Bean 内回调
     * invoker）可形成图中不可见的递归。超出上限抛 {@link IllegalStateException}
     * （明确报错优于耗尽栈后的 StackOverflowError）；finally 保证异常路径深度归零。
     */
    private <T> @Nullable T guarded(Supplier<T> action) {
        int depth = DEPTH.get();
        if (depth >= MAX_INVOCATION_DEPTH) {
            throw new IllegalStateException(
                    "usecase invocation depth exceeds %d — probable circular reference via programmatic invoke"
                            .formatted(MAX_INVOCATION_DEPTH));
        }
        DEPTH.set(depth + 1);
        try {
            return action.get();
        } finally {
            DEPTH.set(depth);
        }
    }
}

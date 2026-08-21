package com.example.myapp.framework.core.invoke;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.exception.UseCaseResultTypeException;

/**
 * 子用例类型化 Java 客户端基类。业务方为每个 shared 用例声明一个客户端：
 *
 * <pre>{@code
 * @Component
 * public class UserBaseClient extends AbstractUseCaseClient<String, UserDto> {
 *     public UserBaseClient(UseCaseInvoker invoker) {
 *         super(invoker, "userBaseEnrichment", UserDto.class);
 *     }
 * }
 *
 * // 使用（自定义 Step / 领域服务 / 任意 Bean 内）：
 * UserDto user = userBaseClient.invoke(businessId);
 * }</pre>
 *
 * <p>基类只做「用例 id 绑定 + 结果类型转换」，调用语义全部委托给 {@link UseCaseInvoker}：</p>
 * <ul>
 *   <li>{@link #invoke(Object)} —— 管道内共享上下文调用（父 payload 自动恢复），管道外独立调用；</li>
 *   <li>{@link #invokeShared(Object)} —— 严格共享调用：要求管道内，管道外抛 IllegalStateException；</li>
 *   <li>{@link #invokeIsolated(Object)} —— 隔离调用（vars 全新，biz 拷贝继承）；</li>
 *   <li>{@link #invokeStandalone(Object)} —— 独立调用（全新上下文，自动种子化 traceId）。</li>
 * </ul>
 *
 * <p>结果非 null 且与声明类型不符时抛 {@link UseCaseResultTypeException}（替代裸 ClassCastException，
 * 消息指明用例 id 与期望/实际类型）。</p>
 *
 * @param <I> 子用例输入（初始 payload）类型
 * @param <O> 子用例结果（最终 payload）类型
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractUseCaseClient<I, O> {

    private final UseCaseInvoker invoker;
    private final String useCaseId;
    private final Class<O> resultType;

    /** 目标用例 id（用于日志/排障） */
    protected String useCaseId() {
        return useCaseId;
    }

    /** 共享调用：管道内继承当前上下文（biz/vars 互通、父 payload 自动恢复）；管道外等同独立调用 */
    public @Nullable O invoke(I input) {
        return castResult(invoker.invoke(useCaseId, input));
    }

    /** 严格共享调用：要求管道内（异步边界请显式 invokeStandalone 并自行传递 traceId/必要 biz 键） */
    public @Nullable O invokeShared(I input) {
        return castResult(invoker.invokeShared(useCaseId, input));
    }

    /** 隔离调用：子用例 vars 全新、biz 拷贝继承，不污染当前上下文 */
    public @Nullable O invokeIsolated(I input) {
        return castResult(invoker.invokeIsolated(useCaseId, input));
    }

    /** 独立调用：全新上下文（管道外场景，如调度任务） */
    public @Nullable O invokeStandalone(I input) {
        return castResult(invoker.invokeStandalone(useCaseId, input));
    }

    private @Nullable O castResult(@Nullable Object result) {
        if (result != null && !resultType.isInstance(result)) {
            throw new UseCaseResultTypeException(useCaseId, resultType, result.getClass());
        }
        return resultType.cast(result);
    }
}

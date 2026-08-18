package com.example.myapp.framework.core;

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
 *   <li>{@link #invokeIsolated(Object)} —— 隔离调用（vars 全新，biz 拷贝继承）；</li>
 *   <li>{@link #invokeStandalone(Object)} —— 独立调用（全新上下文，自动种子化 traceId）。</li>
 * </ul>
 *
 * @param <I> 子用例输入（初始 payload）类型
 * @param <O> 子用例结果（最终 payload）类型
 */
public abstract class AbstractUseCaseClient<I, O> {

    private final UseCaseInvoker invoker;
    private final String useCaseId;
    private final Class<O> resultType;

    protected AbstractUseCaseClient(UseCaseInvoker invoker, String useCaseId, Class<O> resultType) {
        this.invoker = invoker;
        this.useCaseId = useCaseId;
        this.resultType = resultType;
    }

    /** 目标用例 id（用于日志/排障） */
    protected String useCaseId() {
        return useCaseId;
    }

    /** 共享调用：管道内继承当前上下文（biz/vars 互通、父 payload 自动恢复）；管道外等同独立调用 */
    public O invoke(I input) {
        return resultType.cast(invoker.invoke(useCaseId, input));
    }

    /** 隔离调用：子用例 vars 全新、biz 拷贝继承，不污染当前上下文 */
    public O invokeIsolated(I input) {
        return resultType.cast(invoker.invokeIsolated(useCaseId, input));
    }

    /** 独立调用：全新上下文（管道外场景，如调度任务） */
    public O invokeStandalone(I input) {
        return resultType.cast(invoker.invokeStandalone(useCaseId, input));
    }
}

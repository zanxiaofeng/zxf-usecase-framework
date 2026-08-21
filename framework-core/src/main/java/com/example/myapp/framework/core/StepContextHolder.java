package com.example.myapp.framework.core;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * 当前管道上下文的线程级持有者。
 *
 * <p>{@link UseCase#execute} 执行期间把当前 {@link StepContext} 绑定到执行线程，
 * 使管道内的 Java 代码（自定义 Step Bean、SpEL 调用的领域服务等）无需显式传参即可
 * 经 {@link UseCaseInvoker} 调用子用例并继承当前上下文（biz 关键数据区、vars、traceId）。
 * 嵌套执行（子用例）保存并恢复上一层上下文；管道结束必然清理，防止线程复用泄漏。</p>
 *
 * <p>纯 JDK 实现，core 层保持零框架依赖。</p>
 */
@UtilityClass
public class StepContextHolder {

    private final ThreadLocal<StepContext> CURRENT = new ThreadLocal<>();

    /** 当前线程正在执行的管道上下文；不在任何管道内（如定时任务、纯 Java 调用）时返回 null */
    public @Nullable StepContext current() {
        return CURRENT.get();
    }

    /** 绑定新上下文，返回被替换的上一层上下文（嵌套恢复用） */
    @Nullable StepContext set(StepContext context) {
        StepContext previous = CURRENT.get();
        CURRENT.set(context);
        return previous;
    }

    /** 恢复上一层上下文；上一层为 null 时彻底清理 */
    void restore(@Nullable StepContext previous) {
        if (previous == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(previous);
    }
}

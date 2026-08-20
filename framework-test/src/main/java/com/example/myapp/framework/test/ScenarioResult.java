package com.example.myapp.framework.test;

import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.StepContext;

/**
 * 一次场景执行的结果视图：最终 payload + 执行完毕的 {@link StepContext}（vars/biz 仍可取）。
 *
 * @param payload 管道最终 payload（即 {@code UseCase.execute} 返回值；可能为 null）
 * @param context 执行完毕的上下文（vars / biz / request 视图保持可读）
 */
public record ScenarioResult(@Nullable Object payload, StepContext context) {

    /** 类型化读取最终 payload（类型不符立即 ClassCastException） */
    public <T> T payload(Class<T> type) {
        return type.cast(payload);
    }

    /** 读取 vars 旁路数据 */
    public @Nullable Object var(String name) {
        return context.getVar(name);
    }

    /** 读取 biz 关键数据区 */
    public @Nullable Object biz(String key) {
        return context.getBiz(key);
    }
}

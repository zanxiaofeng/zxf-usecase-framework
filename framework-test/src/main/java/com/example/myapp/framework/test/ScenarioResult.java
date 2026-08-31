package com.example.myapp.framework.test;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.DataflowTrace;

/**
 * 一次场景执行的结果视图：最终 payload + 执行完毕的 {@link StepContext}（vars/biz 仍可取）
 * + 实测数据链 trace。
 *
 * @param payload 管道最终 payload（即 {@code UseCase.execute} 返回值；可能为 null）
 * @param context 执行完毕的上下文（vars / biz / request 视图保持可读）
 * @param trace   本次执行的键级读写记录（未录制场景为空 trace）
 */
public record ScenarioResult(@Nullable Object payload, StepContext context, DataflowTrace trace) {

    /** 便捷构造：无数据链 trace（未接入录制的历史调用形态） */
    public ScenarioResult(@Nullable Object payload, StepContext context) {
        this(payload, context, DataflowTrace.of(List.of()));
    }

    /** 类型化读取最终 payload（类型不符立即 ClassCastException） */
    public <T> @Nullable T payload(Class<T> type) {
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

    /** 本次执行的实测数据链（键级读写，含嵌套子用例事件） */
    public DataflowTrace trace() {
        return trace;
    }
}

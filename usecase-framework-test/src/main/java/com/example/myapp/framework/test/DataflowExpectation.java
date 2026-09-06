package com.example.myapp.framework.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowTrace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据链断言（配合 {@link UseCaseScenario#expectDataflow}）：对一次执行的实测 {@link DataflowTrace}
 * 断言中间态读写。step 以名称定位（匹配执行链上任意用例的同名 step，含嵌套子用例）。
 *
 * <pre>{@code
 * scenario.expectDataflow(flow -> flow
 *         .write("fetchCredit", "vars.credit")     // fetchCredit 写了 vars.credit
 *         .read("mergeProfile", "vars.credit")     // mergeProfile 读了它
 *         .noWrite("checkCreditPass")              // 只读不写
 *         .noRead("writer", "vars.tmp"))           // 未读取指定键
 * }</pre>
 *
 * <p>键表达式与声明格式一致：{@code payload} / {@code vars.x} / {@code biz.y}。</p>
 */
public final class DataflowExpectation {

    private final List<Consumer<DataflowTrace>> checks = new ArrayList<>();

    private DataflowExpectation() {
    }

    /** 断言入口（{@code expectDataflow} 回调接收的对象） */
    public static DataflowExpectation dataflow() {
        return new DataflowExpectation();
    }

    /** 断言该 step 写入了指定键 */
    public DataflowExpectation write(String step, String keyExpression) {
        return expectAccess(step, keyExpression, AccessEvent.Op.WRITE, true);
    }

    /** 断言该 step 读取了指定键 */
    public DataflowExpectation read(String step, String keyExpression) {
        return expectAccess(step, keyExpression, AccessEvent.Op.READ, true);
    }

    /** 断言该 step 未写入任何键 */
    public DataflowExpectation noWrite(String step) {
        checks.add(trace -> assertThat(eventsOf(trace, step, AccessEvent.Op.WRITE))
                .as("step [%s] 不应有写入事件，实际：\n%s", step, render(trace))
                .isEmpty());
        return this;
    }

    /** 断言该 step 未写入指定键 */
    public DataflowExpectation noWrite(String step, String keyExpression) {
        return expectAccess(step, keyExpression, AccessEvent.Op.WRITE, false);
    }

    /** 断言该 step 未读取任何键 */
    public DataflowExpectation noRead(String step) {
        checks.add(trace -> assertThat(eventsOf(trace, step, AccessEvent.Op.READ))
                .as("step [%s] 不应有读取事件，实际：\n%s", step, render(trace))
                .isEmpty());
        return this;
    }

    /** 断言该 step 未读取指定键 */
    public DataflowExpectation noRead(String step, String keyExpression) {
        return expectAccess(step, keyExpression, AccessEvent.Op.READ, false);
    }

    /** 对已录制的 trace 逐条执行断言（{@code UseCaseScenario.run()} 自动调用） */
    public void verify(DataflowTrace trace) {
        checks.forEach(check -> check.accept(trace));
    }

    private DataflowExpectation expectAccess(String step, String keyExpression,
                                             AccessEvent.Op op, boolean expected) {
        DataflowKey key = DataflowKey.parse(keyExpression);
        checks.add(trace -> {
            boolean matched = eventsOf(trace, step, op).stream()
                    .anyMatch(event -> event.key().matches(key));
            if (expected) {
                assertThat(matched)
                        .as("step [%s] 应%s键 '%s'，实际：\n%s", step,
                                op == AccessEvent.Op.WRITE ? "写入" : "读取", key, render(trace))
                        .isTrue();
            } else {
                assertThat(matched)
                        .as("step [%s] 不应%s键 '%s'，实际：\n%s", step,
                                op == AccessEvent.Op.WRITE ? "写入" : "读取", key, render(trace))
                        .isFalse();
            }
        });
        return this;
    }

    private static List<AccessEvent> eventsOf(DataflowTrace trace, String step, AccessEvent.Op op) {
        return trace.events().stream()
                .filter(event -> event.op() == op && event.stepName().equals(step))
                .toList();
    }

    private static String render(DataflowTrace trace) {
        return String.join("\n", trace.render());
    }
}

package com.example.myapp.framework.core.dataflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NullMarked;

/**
 * 声明-实测对照检查（静态纯函数）：对 root 执行链的实测 trace 逐条核对声明簿。
 *
 * <p>规则（仅对「有声明」的 step 生效；{@link Dataflow#UNKNOWN} 只记录不告警）：</p>
 * <ol>
 *   <li><b>未声明写入</b>——实测写入键未被该 step 声明覆盖（通配声明豁免）→ WARN；</li>
 *   <li><b>声明写入无人读取</b>——声明写入的 vars/biz 键在整条执行链（含嵌套子用例）中
 *       无任何读取 → WARN；payload 通道排除（消费在响应层，管道外）。</li>
 * </ol>
 *
 * <p>规则 2 仅对本次执行中实际产生过事件的 step 检查——未执行的 step 无实测，不做推断。</p>
 */
@NullMarked
public final class DataflowConformance {

    private DataflowConformance() {
    }

    /** 对照结论：级别 + 中文消息（WARN 由调用方打日志，INFO 为汇总行） */
    public record Finding(Level level, String message) {

        /** 结论级别 */
        public enum Level { WARN, INFO }
    }

    /**
     * 执行对照检查。
     *
     * @param book  声明簿（root 用例及传递子用例的声明）
     * @param trace 实测 trace（整条执行链，含嵌套子用例事件）
     * @return 结论列表（WARN 若干 + 末尾 INFO 汇总；无告警时仅 INFO 汇总）
     */
    public static List<Finding> check(DataflowBook book, DataflowTrace trace) {
        List<Finding> findings = new ArrayList<>();
        List<AccessEvent> events = trace.events();
        int undeclaredWrites = 0;

        // 规则 1：未声明写入
        for (AccessEvent event : events) {
            if (event.op() != AccessEvent.Op.WRITE) {
                continue;
            }
            Dataflow declared = book.of(event.useCaseId(), event.stepName());
            if (declared == null || declared.unknown() || declared.covers(event.key())) {
                continue;
            }
            undeclaredWrites++;
            findings.add(new Finding(Finding.Level.WARN,
                    "usecase [%s] step [%s] 写入了未声明的数据键 '%s'（声明 writes=%s）——请补全 dataflow() 声明或确认写入意图"
                            .formatted(event.useCaseId(), event.stepName(), event.key(), declared.writes())));
        }

        // 规则 2：声明写入无人读取（链级；排除 payload；仅实际执行过的 step）
        int unreadDeclaredWrites = 0;
        for (Map.Entry<String, Map<String, Dataflow>> useCase : book.entries().entrySet()) {
            for (Map.Entry<String, Dataflow> step : useCase.getValue().entrySet()) {
                Dataflow declared = step.getValue();
                if (declared.unknown() || !executed(trace, useCase.getKey(), step.getKey())) {
                    continue;
                }
                for (DataflowKey key : declared.writes()) {
                    if (key.channel() == DataflowKey.Channel.PAYLOAD) {
                        continue;
                    }
                    // 通配声明对齐到实测写入的具体键（消息可归因）；精确声明直接检查
                    List<DataflowKey> actualKeys = key.name().equals(DataflowKey.WILDCARD)
                            ? trace.writesOf(useCase.getKey(), step.getKey()).stream()
                                    .filter(actual -> key.matches(actual) && actual.channel() == key.channel())
                                    .toList()
                            : List.of(key);
                    for (DataflowKey actual : actualKeys) {
                        // 只认具体键读取：批量快照 dump（channel.* 通配读）是观测而非消费，不豁免告警
                        if (!trace.hasConcreteReader(actual.toString())) {
                            unreadDeclaredWrites++;
                            findings.add(new Finding(Finding.Level.WARN,
                                    "usecase [%s] step [%s] 声明写入 '%s' 在整条执行链中无人读取——若为 MDC/观测专用写入请确认"
                                            .formatted(useCase.getKey(), step.getKey(), actual)));
                        }
                    }
                }
            }
        }

        findings.add(new Finding(Finding.Level.INFO,
                "dataflow conformance: recorded accesses=%d, undeclared writes=%d, unread declared writes=%d"
                        .formatted(events.size(), undeclaredWrites, unreadDeclaredWrites)));
        return findings;
    }

    private static boolean executed(DataflowTrace trace, String useCaseId, String stepName) {
        for (AccessEvent event : trace.events()) {
            if (event.useCaseId().equals(useCaseId) && event.stepName().equals(stepName)) {
                return true;
            }
        }
        return false;
    }
}

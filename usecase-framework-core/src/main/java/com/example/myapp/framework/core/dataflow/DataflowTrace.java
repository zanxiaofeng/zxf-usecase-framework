package com.example.myapp.framework.core.dataflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.util.Assert;

/**
 * 一次执行链的实测数据链（实然视图，不可变）。
 *
 * <p>由 {@link AccessEvent} 列表构建，提供按键反查（谁写/谁读）与按步正查（写了/读了什么）。
 * 嵌套子用例（共享 / 隔离 / standalone）的事件按 {@code useCaseId} 维度扁平归属，不做树形结构。</p>
 */
public final class DataflowTrace {

    private static final String INDENT = "  ";

    private final List<AccessEvent> events;

    private DataflowTrace(List<AccessEvent> events) {
        this.events = events;
    }

    /** 从事件列表构建 trace（防御性复制） */
    public static DataflowTrace of(List<AccessEvent> events) {
        Assert.notNull(events, "events must not be null");
        return new DataflowTrace(List.copyOf(events));
    }

    /** 全部访问事件（按录制顺序） */
    public List<AccessEvent> events() {
        return events;
    }

    /** 写入该键的步骤集合（通配事件按通道互认） */
    public Set<StepRef> writersOf(String keyExpression) {
        return refsOf(keyExpression, AccessEvent.Op.WRITE);
    }

    /** 读取该键的步骤集合（通配事件按通道互认） */
    public Set<StepRef> readersOf(String keyExpression) {
        return refsOf(keyExpression, AccessEvent.Op.READ);
    }

    private Set<StepRef> refsOf(String keyExpression, AccessEvent.Op op) {
        DataflowKey key = DataflowKey.parse(keyExpression);
        Set<StepRef> refs = new LinkedHashSet<>();
        for (AccessEvent event : events) {
            if (event.op() == op && event.key().matches(key)) {
                refs.add(StepRef.of(event.useCaseId(), event.stepName()));
            }
        }
        return refs;
    }

    /**
     * 是否存在对该键的<b>具体键</b>读取（排除 {@code channel.*} 通配读——批量快照 dump 属观测
     * 而非数据消费，不构成「有人读取」）。对照检查规则 2（声明写入无人读取）用此判定。
     */
    public boolean hasConcreteReader(String keyExpression) {
        DataflowKey key = DataflowKey.parse(keyExpression);
        for (AccessEvent event : events) {
            if (event.op() == AccessEvent.Op.READ
                    && event.key().channel() == key.channel()
                    && !DataflowKey.WILDCARD.equals(event.key().name())
                    && event.key().matches(key)) {
                return true;
            }
        }
        return false;
    }

    /** 某用例某步写入的键集合（按事件顺序，去重） */
    public Set<DataflowKey> writesOf(String useCaseId, String stepName) {
        return keysOf(useCaseId, stepName, AccessEvent.Op.WRITE);
    }

    /** 某用例某步读取的键集合（按事件顺序，去重） */
    public Set<DataflowKey> readsOf(String useCaseId, String stepName) {
        return keysOf(useCaseId, stepName, AccessEvent.Op.READ);
    }

    private Set<DataflowKey> keysOf(String useCaseId, String stepName, AccessEvent.Op op) {
        Set<DataflowKey> keys = new LinkedHashSet<>();
        for (AccessEvent event : events) {
            if (event.op() == op && event.useCaseId().equals(useCaseId) && event.stepName().equals(stepName)) {
                keys.add(event.key());
            }
        }
        return keys;
    }

    /** 出现过事件的用例 id 集合（含嵌套子用例） */
    public Set<String> useCases() {
        Set<String> ids = new LinkedHashSet<>();
        for (AccessEvent event : events) {
            ids.add(event.useCaseId());
        }
        return ids;
    }

    /** 渲染人类可读文本（dev 输出与断言失败消息共用） */
    public List<String> render() {
        List<String> lines = new ArrayList<>();
        Map<String, Map<String, Set<String>>> byUseCase = new LinkedHashMap<>();
        for (AccessEvent event : events) {
            byUseCase.computeIfAbsent(event.useCaseId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(event.stepName(), k -> new LinkedHashSet<>())
                    .add((event.op() == AccessEvent.Op.READ ? "read " : "write ") + event.key());
        }
        for (Map.Entry<String, Map<String, Set<String>>> useCase : byUseCase.entrySet()) {
            lines.add("usecase [" + useCase.getKey() + "]");
            for (Map.Entry<String, Set<String>> step : useCase.getValue().entrySet()) {
                lines.add(INDENT + "step [" + step.getKey() + "] " + String.join(", ", new TreeSet<>(step.getValue())));
            }
        }
        if (lines.isEmpty()) {
            lines.add("dataflow trace: (no recorded access)");
        }
        return lines;
    }

    @Override
    public String toString() {
        return String.join("\n", render());
    }
}

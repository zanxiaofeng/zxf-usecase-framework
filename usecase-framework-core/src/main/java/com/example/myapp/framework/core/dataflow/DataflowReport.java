package com.example.myapp.framework.core.dataflow;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.util.Assert;

/**
 * 单个用例的数据流声明视图（应然）：step 名（与运行期 {@code Step#name()} 对齐）→ 声明。
 *
 * <p>由 {@code DataflowBook#ofUseCase} 提取，供静态查询（谁声明写/读某键）与启动期报告渲染。</p>
 */
public final class DataflowReport {

    private final String useCaseId;
    private final Map<String, Dataflow> steps;

    private DataflowReport(String useCaseId, Map<String, Dataflow> steps) {
        this.useCaseId = useCaseId;
        this.steps = steps;
    }

    /** 构建用例声明视图（steps 防御性复制） */
    public static DataflowReport of(String useCaseId, Map<String, Dataflow> steps) {
        Assert.hasText(useCaseId, "useCaseId must not be blank");
        Assert.notNull(steps, "steps must not be null");
        return new DataflowReport(useCaseId, Map.copyOf(steps));
    }

    /** 用例 id */
    public String useCaseId() {
        return useCaseId;
    }

    /** 声明过的 step 名集合 */
    public Set<String> steps() {
        return steps.keySet();
    }

    /** 某 step 的声明；未声明该步返回 empty */
    public Optional<Dataflow> ofStep(String stepName) {
        return Optional.ofNullable(steps.get(stepName));
    }

    /** 声明写入该键的 step 名集合（通配声明按通道互认） */
    public Set<String> writersOf(String keyExpression) {
        return stepNamesOf(DataflowKey.parse(keyExpression), true);
    }

    /** 声明读取该键的 step 名集合（通配声明按通道互认） */
    public Set<String> readersOf(String keyExpression) {
        return stepNamesOf(DataflowKey.parse(keyExpression), false);
    }

    private Set<String> stepNamesOf(DataflowKey key, boolean writes) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, Dataflow> entry : steps.entrySet()) {
            Dataflow dataflow = entry.getValue();
            if (dataflow.unknown()) {
                continue;
            }
            boolean declared = writes
                    ? dataflow.writes().stream().anyMatch(k -> k.matches(key))
                    : dataflow.reads().stream().anyMatch(k -> k.matches(key));
            if (declared) {
                names.add(entry.getKey());
            }
        }
        return names;
    }
}

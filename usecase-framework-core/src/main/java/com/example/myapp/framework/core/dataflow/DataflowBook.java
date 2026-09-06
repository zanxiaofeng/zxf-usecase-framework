package com.example.myapp.framework.core.dataflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.Assert;

/**
 * 装配期声明簿：按用例聚合各 step 的数据流声明（应然视图）。
 *
 * <p>root 用例的 book 含其（传递）子用例的声明条目——对照检查按 {@code useCaseId} 维度
 * 各归各判定，不做跨用例键名合并（那是 {@code VarsWriteIndex} 展示层的职责）。</p>
 */
public final class DataflowBook {

    private final Map<String, Map<String, Dataflow>> declarations = new LinkedHashMap<>();

    /** 登记一条 step 声明（同键重复登记以最后一次为准；装配期每 step 仅登记一次） */
    public void put(String useCaseId, String stepName, Dataflow dataflow) {
        Assert.hasText(useCaseId, "useCaseId must not be blank");
        Assert.hasText(stepName, "stepName must not be blank");
        Assert.notNull(dataflow, "dataflow must not be null");
        declarations.computeIfAbsent(useCaseId, k -> new LinkedHashMap<>()).put(stepName, dataflow);
    }

    /** 某用例某步的声明；未登记（不在本 book 覆盖范围）返回 null */
    public Dataflow of(String useCaseId, String stepName) {
        Map<String, Dataflow> steps = declarations.get(useCaseId);
        return steps == null ? null : steps.get(stepName);
    }

    /** 某用例的全部 step 声明（只读视图）；该用例无条目时返回空 Map */
    public Map<String, Dataflow> ofUseCase(String useCaseId) {
        Map<String, Dataflow> steps = declarations.get(useCaseId);
        return steps == null ? Map.of() : Collections.unmodifiableMap(steps);
    }

    /** 某用例的声明视图（{@link DataflowReport}）；该用例无条目时返回空视图 */
    public DataflowReport reportOf(String useCaseId) {
        return DataflowReport.of(useCaseId, ofUseCase(useCaseId));
    }

    /** 全部声明条目（只读视图）：useCaseId → (stepName → Dataflow) */
    public Map<String, Map<String, Dataflow>> entries() {
        return Collections.unmodifiableMap(declarations);
    }
}

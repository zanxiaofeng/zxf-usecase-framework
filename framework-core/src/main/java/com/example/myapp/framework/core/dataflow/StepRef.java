package com.example.myapp.framework.core.dataflow;

/**
 * 运行期步骤标识：用例 id + 步骤名（与 {@code StepDefinition#nameOr(type)} 对齐）。
 *
 * @param useCaseId 所属用例 id
 * @param stepName  步骤名
 */
public record StepRef(String useCaseId, String stepName) {

    /** 构建步骤标识 */
    public static StepRef of(String useCaseId, String stepName) {
        return new StepRef(useCaseId, stepName);
    }

    @Override
    public String toString() {
        return useCaseId + "#" + stepName;
    }
}

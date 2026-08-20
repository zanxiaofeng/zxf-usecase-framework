package com.example.myapp.framework.core.exception;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.DataSnapshot;

/**
 * 步骤执行失败：携带 useCaseId 与 stepName，cause 为原始异常（领域异常、下游调用异常等）。
 * 传输层沿 cause 链还原原始异常做状态码映射。
 *
 * <p>{@link #withDiagnostics} 附带失败时的键级数据现场（仅类型与键名，进日志不进响应体）；
 * 嵌套子用例多层包装时最内层现场优先（已带现场的异常不再被外层覆盖）。</p>
 */
@Getter
public class StepExecutionException extends RuntimeException {

    private final String useCaseId;
    private final String stepName;
    /** 失败现场（最内层优先）；未附加时为 null */
    private @Nullable DataSnapshot diagnostics;

    public StepExecutionException(String useCaseId, String stepName, Throwable cause) {
        super("usecase [%s] step [%s] failed: %s"
                .formatted(useCaseId, stepName, cause == null ? null : cause.getMessage()), cause);
        this.useCaseId = useCaseId;
        this.stepName = stepName;
    }

    /** 附加失败现场（幂等：已带现场时保留最内层，返回 this 便于 throw 链式使用） */
    public StepExecutionException withDiagnostics(DataSnapshot snapshot) {
        if (this.diagnostics == null) {
            this.diagnostics = snapshot;
        }
        return this;
    }
}

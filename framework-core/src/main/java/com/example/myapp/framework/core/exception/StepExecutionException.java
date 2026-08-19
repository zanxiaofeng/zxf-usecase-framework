package com.example.myapp.framework.core.exception;

/**
 * 步骤执行失败：携带 useCaseId 与 stepName，cause 为原始异常（领域异常、下游调用异常等）。
 * 传输层沿 cause 链还原原始异常做状态码映射。
 */
public class StepExecutionException extends RuntimeException {

    private final String useCaseId;
    private final String stepName;

    public StepExecutionException(String useCaseId, String stepName, Throwable cause) {
        super("usecase [%s] step [%s] failed: %s"
                .formatted(useCaseId, stepName, cause == null ? null : cause.getMessage()), cause);
        this.useCaseId = useCaseId;
        this.stepName = stepName;
    }

    public String getUseCaseId() {
        return useCaseId;
    }

    public String getStepName() {
        return stepName;
    }
}

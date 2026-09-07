package com.example.datatransfer.core.exception;

import java.util.List;

/**
 * validations 校验失败（评审 6.3/6.4）：fail_fast 模式携带首个失败、collect 模式携带全部失败，
 * 经 {@link #getFailures()} 取结构化明细。抛出点在 Unflatten 之前——不生成脏数据。
 */
public class ValidationException extends TransferException {

    private final List<ValidationFailure> failures;

    public ValidationException(String specName, List<ValidationFailure> failures) {
        super("validation failed (" + specName + "): "
                + failures.stream().map(ValidationFailure::toString).reduce((a, b) -> a + "; " + b).orElse(""));
        this.failures = List.copyOf(failures);
    }

    public List<ValidationFailure> getFailures() {
        return failures;
    }
}

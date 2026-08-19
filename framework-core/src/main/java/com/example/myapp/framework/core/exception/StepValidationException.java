package com.example.myapp.framework.core.exception;

import lombok.Getter;

/**
 * 校验步骤失败异常：携带稳定错误码（缺省 VALIDATION_ERROR）与可读消息（含 schema 失败明细）。
 * 传输层默认映射为 400，可被 usecase.error-mappings 覆盖。
 */
@Getter
public class StepValidationException extends RuntimeException implements ErrorCoded {

    public static final String DEFAULT_CODE = "VALIDATION_ERROR";

    private final String errorCode;

    public StepValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    @Override
    public int defaultHttpStatus() {
        return 400;
    }
}

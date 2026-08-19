package com.example.myapp.domain.exception;

import com.example.myapp.framework.core.exception.ErrorCoded;

/**
 * 类型化业务异常基类：携带稳定错误码（客户端契约）。
 *
 * <p>说明：实现框架的 {@link ErrorCoded} 是务实选择（一次 implements 换取传输层直接读码）。
 * 若坚持领域层零依赖，可去掉 implements —— 框架会回退到反射调用 getErrorCode()。
 */
public abstract class DomainException extends RuntimeException implements ErrorCoded {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }
}

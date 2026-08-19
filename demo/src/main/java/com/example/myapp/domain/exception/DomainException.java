package com.example.myapp.domain.exception;

import lombok.Getter;

/**
 * 类型化业务异常基类：携带稳定错误码（客户端契约）。
 *
 * <p>不实现框架的 {@code ErrorCoded} 接口——领域层保持零框架依赖（DIP：依赖方向恒指向
 * domain）；传输层经反射读取 {@link #getErrorCode()}（ErrorResponseMapper 反射回退），行为不变。
 * HTTP 状态码由 usecase.error-mappings 配置映射，不属于领域语义。</p>
 */
@Getter
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

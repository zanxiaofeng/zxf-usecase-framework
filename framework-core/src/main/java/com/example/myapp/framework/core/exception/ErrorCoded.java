package com.example.myapp.framework.core.exception;

/**
 * 携带稳定错误码的异常契约。领域异常实现本接口后，传输层可直接把 errorCode 写入响应体。
 *
 * <p>若坚持领域层零依赖的纯粹性，可不实现本接口——框架会回退到反射调用
 * {@code getErrorCode()} 方法读取错误码（见 framework.web 的异常映射）。
 */
public interface ErrorCoded {

    String getErrorCode();

    /**
     * 未被 usecase.error-mappings / @ResponseStatus 覆盖时的默认 HTTP 状态码。
     * 例如校验类异常返回 400。缺省 500。
     */
    default int defaultHttpStatus() {
        return 500;
    }
}

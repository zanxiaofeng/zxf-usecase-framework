package com.example.myapp.framework.core;

/**
 * 用例装配失败（配置错误）：启动期抛出，fail-fast，不进入运行期。
 */
public class UseCaseAssemblyException extends RuntimeException {

    public UseCaseAssemblyException(String message) {
        super(message);
    }

    public UseCaseAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}

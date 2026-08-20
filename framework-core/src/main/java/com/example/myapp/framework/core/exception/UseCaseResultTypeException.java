package com.example.myapp.framework.core.exception;

/**
 * 类型化子用例客户端（AbstractUseCaseClient）的结果类型不匹配：子用例末步输出与声明的结果类型不符。
 * 属编程/配置错误（子用例管道或客户端声明有误），不经 errorMappings，走 500 兜底。
 */
public class UseCaseResultTypeException extends RuntimeException {

    public UseCaseResultTypeException(String useCaseId, Class<?> expectedType, Class<?> actualType) {
        super("usecase [%s] expected result type %s but got %s — check the sub-usecase's final step output"
                .formatted(useCaseId, expectedType.getSimpleName(), actualType.getSimpleName()));
    }
}

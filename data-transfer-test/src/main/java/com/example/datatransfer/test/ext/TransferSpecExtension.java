package com.example.datatransfer.test.ext;

import java.lang.reflect.Method;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import com.example.datatransfer.test.TransferAssert;

/**
 * {@link TransferSpecTest} 注解的执行器（设计文档 §10.8）：用
 * {@link InvocationInterceptor}（JUnit 6 起统一拦截接口，TestExecutionInterceptor 已移除）
 * 在测试方法调用处执行契约断言，断言通过后再进入方法体（可为空）；
 * 相比 BeforeEachCallback，失败堆栈归属更准确。
 */
public class TransferSpecExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext context) throws Throwable {
        TransferSpecTest annotation =
                invocationContext.getExecutable().getAnnotation(TransferSpecTest.class);
        if (annotation == null) {
            invocation.proceed();
            return;
        }

        TransferAssert assertion = TransferAssert.assertThat(annotation.spec())
                .withFixture(annotation.fixture())
                .ignorePaths(annotation.ignorePaths());
        if (annotation.mode() == TransferAssert.CompareMode.PARTIAL) {
            assertion.partiallyMatches(annotation.expected());
        } else {
            assertion.matchesExpected(annotation.expected());
        }

        invocation.proceed();
    }
}

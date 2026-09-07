package com.example.datatransfer.test.ext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.example.datatransfer.test.TransferAssert;

/**
 * 注解驱动的契约测试（设计文档 §10.8）：元标注 {@code @Test} 与
 * {@code @ExtendWith(TransferSpecExtension.class)}——扩展随注解自带，
 * 测试类无需再显式注册（漏注册会让断言静默跳过、空方法体假绿）；
 * {@link TransferSpecExtension} 在测试方法调用处执行契约断言，方法体可为空。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TransferSpecExtension.class)
@org.junit.jupiter.api.Test
public @interface TransferSpecTest {

    /** TransferSpec 资源位置（classpath 优先、文件系统兜底） */
    String spec();

    /** 输入样本资源位置 */
    String fixture();

    /** 期望输出资源位置 */
    String expected();

    TransferAssert.CompareMode mode() default TransferAssert.CompareMode.STRICT;

    String[] ignorePaths() default {};
}

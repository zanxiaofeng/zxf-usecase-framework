package com.example.myapp.framework.core;

/**
 * 编码步骤：对输入做可逆编码（base64 / base64url / url / hex）或不可逆摘要（md5 / sha256）。
 * 输入来自 {@code source} 表达式（缺省 {@code #payload}），结果按 as/payload 规则落地。
 * 算法由 {@code Codec} SPI 提供，可注册自定义 Codec Bean 扩展。
 */
public interface Encoder extends Step {
}

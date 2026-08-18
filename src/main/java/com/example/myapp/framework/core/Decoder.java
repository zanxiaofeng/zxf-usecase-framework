package com.example.myapp.framework.core;

/**
 * 解码步骤：与 {@link Encoder} 互逆。装配期校验算法支持解码（digest 类算法不可用于 decoder，fail-fast）。
 */
public interface Decoder extends Step {
}

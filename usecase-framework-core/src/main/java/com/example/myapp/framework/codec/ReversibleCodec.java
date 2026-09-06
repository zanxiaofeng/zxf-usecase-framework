package com.example.myapp.framework.codec;

/**
 * 可逆编解码算法 SPI：在 {@link Codec} 之上承诺 {@link #decode(String)} 能力。
 *
 * <p>仅可逆算法（base64 / hex / url 等）实现本接口；单向算法（digest 类）只实现 {@link Codec}。
 * decoder 步骤在装配期经类型校验 fail-fast，替代运行期 {@code UnsupportedOperationException}。</p>
 */
public interface ReversibleCodec extends Codec {

    /** 解码（编码的逆操作）。 */
    String decode(String encoded);
}

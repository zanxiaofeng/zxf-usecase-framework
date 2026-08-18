package com.example.myapp.framework.codec;

/**
 * 编解码算法 SPI。encoder / decoder 步骤通过 {@code algorithm} 名称引用。
 *
 * <p>内置算法：{@code base64} / {@code base64url} / {@code url} / {@code hex} / {@code md5} / {@code sha256}。
 * 扩展：实现本接口注册为 Spring Bean 即可在 YAML 中使用新算法名。</p>
 */
public interface Codec {

    /** 算法名（YAML 中 config.algorithm 的值）。 */
    String algorithm();

    String encode(String plain);

    /** 是否支持解码（digest 类算法返回 false）。 */
    default boolean supportsDecode() {
        return false;
    }

    default String decode(String encoded) {
        throw new UnsupportedOperationException("codec '" + algorithm() + "' does not support decode");
    }
}

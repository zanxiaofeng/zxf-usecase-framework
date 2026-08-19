package com.example.myapp.framework.codec;

/**
 * 编解码算法 SPI。encoder / decoder 步骤通过 {@code algorithm} 名称引用。
 *
 * <p>内置算法：{@code base64} / {@code base64url} / {@code url} / {@code hex} / {@code md5} / {@code sha256}。
 * 扩展：实现本接口注册为 Spring Bean 即可在 YAML 中使用新算法名。</p>
 *
 * <p>本接口只承诺编码能力；可逆算法另实现 {@link ReversibleCodec}（ISP：
 * 单向算法不被迫继承用不到的 decode 能力）。</p>
 */
public interface Codec {

    /** 算法名（YAML 中 config.algorithm 的值）。 */
    String algorithm();

    String encode(String plain);
}

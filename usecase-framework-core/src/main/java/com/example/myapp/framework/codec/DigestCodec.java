package com.example.myapp.framework.codec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 单向摘要算法（md5 / sha256 / ...），仅实现 {@link Codec} 的编码能力，
 * 不实现 {@link ReversibleCodec}——decoder 步骤引用时在装配期 fail-fast。
 */
public final class DigestCodec implements Codec {

    private final String algorithm;
    private final String jcaName;

    /** @param algorithm 对外算法名，如 "md5" / "sha256"（JCA 名自动映射为大写无横线形式）；不支持时构造期快失败 */
    public DigestCodec(String algorithm) {
        this.algorithm = algorithm.toLowerCase(Locale.ROOT);
        this.jcaName = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
        try {
            MessageDigest.getInstance(this.jcaName);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("unsupported digest algorithm: " + algorithm, e);
        }
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public String encode(String plain) {
        try {
            // MessageDigest 非线程安全，不能缓存为字段——每次获取新实例（算法已在构造期验证）
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(jcaName).digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("unreachable: algorithm validated at construction", e);
        }
    }
}

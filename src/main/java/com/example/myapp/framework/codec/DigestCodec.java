package com.example.myapp.framework.codec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 单向摘要算法（md5 / sha256 / ...），仅可用于 encoder，不可用于 decoder。
 */
public final class DigestCodec implements Codec {

    private final String algorithm;
    private final String jcaName;

    /** @param algorithm 对外算法名，如 "md5" / "sha256"（JCA 名自动映射为大写无横线形式） */
    public DigestCodec(String algorithm) {
        this.algorithm = algorithm.toLowerCase(Locale.ROOT);
        this.jcaName = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public String encode(String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance(jcaName);
            return HexFormat.of().formatHex(digest.digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("unsupported digest algorithm: " + jcaName, e);
        }
    }

    @Override
    public boolean supportsDecode() {
        return false;
    }
}

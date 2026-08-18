package com.example.myapp.framework.codec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Base64UrlCodec implements Codec {

    @Override
    public String algorithm() {
        return "base64url";
    }

    @Override
    public String encode(String plain) {
        return Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean supportsDecode() {
        return true;
    }

    @Override
    public String decode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}

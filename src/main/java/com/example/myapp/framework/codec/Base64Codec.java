package com.example.myapp.framework.codec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Base64Codec implements Codec {

    @Override
    public String algorithm() {
        return "base64";
    }

    @Override
    public String encode(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean supportsDecode() {
        return true;
    }

    @Override
    public String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}

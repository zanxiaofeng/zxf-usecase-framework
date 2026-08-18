package com.example.myapp.framework.codec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class HexCodec implements Codec {

    @Override
    public String algorithm() {
        return "hex";
    }

    @Override
    public String encode(String plain) {
        return HexFormat.of().formatHex(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean supportsDecode() {
        return true;
    }

    @Override
    public String decode(String encoded) {
        return new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8);
    }
}

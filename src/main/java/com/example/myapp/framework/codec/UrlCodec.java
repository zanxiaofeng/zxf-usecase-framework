package com.example.myapp.framework.codec;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class UrlCodec implements Codec {

    @Override
    public String algorithm() {
        return "url";
    }

    @Override
    public String encode(String plain) {
        return URLEncoder.encode(plain, StandardCharsets.UTF_8);
    }

    @Override
    public boolean supportsDecode() {
        return true;
    }

    @Override
    public String decode(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}

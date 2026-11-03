package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.plan2.domain.Sha256;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the JDK ({@link MessageDigest}) over the UTF-8 bytes of a text, as lowercase hexadecimal. */
public final class JdkSha256 implements Sha256 {

    @Override
    public String hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

package com.nexora.reports.plan2.domain;

/**
 * Port: SHA-256 of the UTF-8 bytes of a text, as lowercase hexadecimal. The domain computes canonical JSON and
 * compares fingerprints; the digest itself is provided by infrastructure with the JDK implementation
 * ({@code com.nexora.reports.plan2.infrastructure.JdkSha256}), so the domain depends on no security provider and no
 * cryptography is written by hand (A4-B2-FIX1).
 */
@FunctionalInterface
public interface Sha256 {

    String hex(String text);
}

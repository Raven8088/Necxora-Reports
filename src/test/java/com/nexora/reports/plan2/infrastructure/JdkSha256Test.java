package com.nexora.reports.plan2.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A4-B2-FIX1: the JDK digest behind the {@code Sha256} port gives the published SHA-256 test vectors (FIPS 180). */
class JdkSha256Test {

    @Test
    void givesTheOfficialSha256TestVectors() {
        JdkSha256 sha256 = new JdkSha256();

        assertThat(sha256.hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
                .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    }
}

package com.nexora.reports.result.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A4-B6, precision 4: the cell text limit counts UTF-16 units, keeps the mark inside and never splits a pair. */
class CellTextTest {

    @Test
    void aTextThatFitsIsReturnedUnchanged() {
        String text = "a".repeat(32_767);

        assertThat(CellText.limit(text, 32_767) == text).isTrue();
        assertThat(CellText.limit(null, 10)).isNull();
    }

    @Test
    void aLongTextIsCutToTheLimitWithTheMarkInside() {
        String cut = CellText.limit("a".repeat(40_000), 32_767);

        assertThat(cut.length()).isEqualTo(32_767);
        assertThat(cut.endsWith(CellText.MARK)).isTrue();
        assertThat(cut.substring(0, 32_764)).isEqualTo("a".repeat(32_764));
    }

    @Test
    void aSurrogatePairIsNeverSplitAtTheCut() {
        String emoji = "😀";
        for (int max = 10; max <= 14; max++) {
            String cut = CellText.limit(emoji.repeat(20), max);

            assertThat(cut.length() <= max).isTrue();
            String body = cut.substring(0, cut.length() - CellText.MARK.length());
            assertThat(body.length() % 2).isEqualTo(0);
            assertThat(body.codePoints().allMatch(cp -> cp == 0x1F600)).isTrue();
        }
    }

    @Test
    void theMarkIsThreeUtf16Units() {
        assertThat(CellText.MARK.length()).isEqualTo(3);
        assertThat(CellText.fits("abc", 3)).isTrue();
        assertThat(CellText.fits("abcd", 3)).isFalse();
    }
}

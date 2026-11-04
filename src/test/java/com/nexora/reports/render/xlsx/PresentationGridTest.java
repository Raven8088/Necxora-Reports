package com.nexora.reports.render.xlsx;

import com.nexora.reports.plan2.ContractFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Presentation of the Excel reports (D-XL-1, EX-19, «misma rejilla»): the presentation changes how a report looks,
 * never what it holds. For every {@link GridFixtures} fixture (simple, grouped with its own total, composite, partial,
 * another schema, English, values kept as text, texts cut, 300 rows), the sheets, their order and names, every cell
 * (address, type, exact value, number format, quote prefix), the frozen rows, the autofilters and the absence of merged
 * cells are identical to {@code grid/<name>.grid}, written by the renderer BEFORE the presentation change
 * ({@link GridBaselines}). The column order requested by the user is part of the addresses compared.
 */
class PresentationGridTest {

    /** SHA-256 of each baseline as written before EX-19 (LF line ends): a baseline cannot be regenerated silently. */
    static final Map<String, String> BASELINES = Map.ofEntries(
            Map.entry("empresas", "d085d0cf333aa70dc95404bd520ae946395ba6c207165bbe4c262dc45be3b0e8"),
            Map.entry("escritor-compuesto", "cddc2c513730531dad6d6ecbcc15de3cf951b4a8a38e660131e751557b65856a"),
            Map.entry("escritor-prueba", "43a39993910c617af2452e1a11b2563494c931c0708c611521bdb71be6f7cdd8"),
            Map.entry("escritor-simple", "0105f08b0e59108490549891cb3bf5edb6cd417fb6319d11889c9704cb605d11"),
            Map.entry("pa-a-300-filas", "e3e7a84d5cafda77491dbabfc5f4c2dbbd67603fdab0c703ac331da144bbf2b0"),
            Map.entry("pa-a-en", "e85598b90897543d0c58d0d819585e21132c9529dc167c8a99a00c1e306b2ba1"),
            Map.entry("pa-a-es", "473013868d6df26e24bd61ac619b4dc6b6f30d9afbdc8dd6d2a2d1c496369de4"),
            Map.entry("pa-a-parcial", "2fa5051d675cb0f6023554d100a1398d3a6cebd71ad785544a7cd8bddf56ce30"),
            Map.entry("pa-a-texto-exacto", "40891ee3c9bfe294fc483203d6cb7cc24ac7551951914bc374aa940d129ff8d7"),
            Map.entry("pa-a-textos-recortados", "765ba17da5d92d09b4debe7e735d898640225e8eaf695a475221eda8098ac538"));

    private static String baseline(String name) {
        return new String(ContractFixtures.bytes("com/nexora/reports/render/xlsx/grid/" + name + ".grid"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void everyFixtureKeepsExactlyTheGridOfTheRendererBeforeThePresentationChange() {
        Map<String, Supplier<byte[]>> fixtures = GridFixtures.all();
        assertThat(fixtures.keySet()).containsExactlyInAnyOrderElementsOf(BASELINES.keySet());
        for (Map.Entry<String, Supplier<byte[]>> f : fixtures.entrySet()) {
            assertThat(GridSnapshot.of(f.getValue().get())).as(f.getKey()).isEqualTo(baseline(f.getKey()));
        }
    }

    @Test
    void theBaselinesAreTheOnesRecordedBeforeTheChange() throws NoSuchAlgorithmException {
        for (Map.Entry<String, String> e : BASELINES.entrySet()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(baseline(e.getKey())
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(HexFormat.of().formatHex(digest)).as(e.getKey()).isEqualTo(e.getValue());
        }
    }
}

package com.nexora.reports.credentials.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EX-TLS-1, F1: the capability exists only when the build put its resource in the jar. */
class TransportCapabilityTest {

    @TempDir
    Path dir;

    private ClassLoader loaderWith(String content) throws Exception {
        if (content != null) {
            Path file = dir.resolve(TransportCapability.RESOURCE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, null);
    }

    @Test
    void withoutTheResourceThereIsNoCapability() throws Exception {
        assertThat(TransportCapability.load(loaderWith(null))).isEqualTo(TransportCapability.NONE);
        assertThat(TransportCapability.NONE.capable()).isFalse();
    }

    @Test
    void theResourceOfTheDevelopmentBuildGivesTheCapabilityAndTheBuildInstant() throws Exception {
        TransportCapability c = TransportCapability.load(loaderWith("capable=true\nbuilt-at=2026-11-04T10:00:00Z\n"));
        assertThat(c.capable()).isTrue();
        assertThat(c.builtAt()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
    }

    @Test
    void aDamagedResourceIsAnErrorNotACapability() throws Exception {
        assertThatThrownBy(() -> TransportCapability.load(loaderWith("capable=false\nbuilt-at=2026-11-04T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TransportCapability.load(loaderWith("capable=true\nbuilt-at=@unfiltered@")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theTestClasspathOfANormalBuildHasNoCapability() {
        // mvn verify without -Pdev-insecure-transport: target/classes has no META-INF/nexora resource.
        if (getClass().getClassLoader().getResource(TransportCapability.RESOURCE) == null) {
            assertThat(TransportCapability.load(getClass().getClassLoader()).capable()).isFalse();
        } else {
            assertThat(TransportCapability.load(getClass().getClassLoader()).capable()).isTrue();
        }
    }
}

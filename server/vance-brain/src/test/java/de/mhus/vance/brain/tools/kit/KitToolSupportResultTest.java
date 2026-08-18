package de.mhus.vance.brain.tools.kit;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.kit.KitOperationResultDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a kit operation tells the model it did.
 *
 * <p>The version used to be absent, and the only place it could be read
 * was the commit string — which spells {@code library:3.1.0} for a store
 * kit and a bare SHA for a git one. Anything reading a version out of that
 * works for one source and silently fails for the other.
 */
class KitToolSupportResultTest {

    @Test
    void resultToMap_reportsTheVersionSeparatelyFromTheCommit() {
        Map<String, Object> out = KitToolSupport.resultToMap(
                KitOperationResultDto.builder()
                        .kitName("widgets")
                        .mode("UPDATE")
                        .version("3.1.0")
                        .sourceCommit("library:3.1.0")
                        .build());

        assertThat(out).containsEntry("version", "3.1.0");
        assertThat(out).containsEntry("commit", "library:3.1.0");
    }

    @Test
    void resultToMap_gitKit_stillCarriesTheVersion() {
        // The case the commit string cannot answer at all.
        Map<String, Object> out = KitToolSupport.resultToMap(
                KitOperationResultDto.builder()
                        .kitName("widgets")
                        .mode("INSTALL")
                        .version("2.4.0")
                        .sourceCommit("9f2a1c4e5b6d7a8f9012345678abcdef01234567")
                        .build());

        assertThat(out).containsEntry("version", "2.4.0");
    }

    @Test
    void resultToMap_failedUpdate_omitsTheVersionRatherThanGuessing() {
        // No descriptor was ever read, so there is nothing truthful to say.
        Map<String, Object> out = KitToolSupport.resultToMap(
                KitOperationResultDto.builder()
                        .kitName("widgets")
                        .mode("UPDATE")
                        .warnings(List.of("update failed: source unreachable"))
                        .build());

        assertThat(out).doesNotContainKey("version");
        assertThat(out).containsEntry("warnings", List.of("update failed: source unreachable"));
    }
}

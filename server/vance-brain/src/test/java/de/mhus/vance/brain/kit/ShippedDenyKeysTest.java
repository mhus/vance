package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The three setting deny-lists as the product actually ships them.
 *
 * <p>Exists because the failure it catches is invisible in the code: each list
 * has a default spelled out in a {@code @Value} placeholder <em>and</em> a
 * value in {@code application.yml}, and the file wins. {@code kit.*} was in the
 * Java default of {@code agentWriteDenyKeys} and missing from the shipped
 * configuration, so the running product was the weaker of the two and every
 * unit test that constructed the policy by hand passed.
 *
 * <p>Asserted as "contains at least", not as equality: an operator shortening
 * the list for their deployment is a supported thing to do, and this test is
 * about what we ship, not about what anyone may configure.
 */
class ShippedDenyKeysTest {

    /** What an <em>agent</em> may never write. */
    private static final List<String> AGENT_WRITE = List.of(
            "ai.provider.*",     // redirect the project's model traffic
            "vault.*",           // redirect its secret lookups
            "store.*",           // rewrite the purchase identity (kit-store.md §3 S3)
            "kit.*",             // decide where the project gets its tool definitions
            "jaglan.mount.*");   // point a document namespace at the pod's file system

    /** What a {@code {{secret:…}}} reference may never resolve. */
    private static final List<String> SECRET_REFERENCE = List.of(
            "ai.provider.*",
            "vault.*",
            "store.token.*");

    /** What a <em>kit</em> may never write into a project. */
    private static final List<String> KIT_WRITE = List.of(
            "ai.provider.*", "vault.*", "store.*", "kit.*", "jaglan.mount.*");

    @Test
    void agentWriteDenyKeys_shipsEveryPatternTheDefaultPromises() {
        assertThat(patterns("settings", "agentWriteDenyKeys"))
                .containsAll(AGENT_WRITE);
    }

    @Test
    void secretReferenceDenyKeys_shipsStoreTokenButNotKitToken() {
        List<String> shipped = patterns("settings", "secretReferenceDenyKeys");
        assertThat(shipped).containsAll(SECRET_REFERENCE);
        // Deliberate asymmetry, and the reason the two lists are separate: a
        // provisioning document resolves kit.token.<host> through exactly such
        // a reference, so denying it here would break provisioning to close a
        // store leak.
        assertThat(shipped).doesNotContain("kit.*", "kit.token.*");
    }

    @Test
    void kitSettingDenyKeys_shipsEveryPatternTheDefaultPromises() {
        assertThat(patterns("kits", "settingDenyKeys")).containsAll(KIT_WRITE);
    }

    @SuppressWarnings("unchecked")
    private static List<String> patterns(String section, String key) {
        Map<String, Object> root;
        try (InputStream in = ShippedDenyKeysTest.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read application.yml", e);
        }
        Map<String, Object> vance = (Map<String, Object>) root.get("vance");
        Map<String, Object> block = (Map<String, Object>) vance.get(section);
        assertThat(block).as("vance.%s in application.yml", section).isNotNull();
        Object raw = block.get(key);
        assertThat(raw).as("vance.%s.%s in application.yml", section, key).isNotNull();
        return Arrays.stream(raw.toString().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

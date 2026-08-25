package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The manifest block is two optional keys, so most of what is worth testing is
 * what it *ignores* and where it sits in the document.
 */
class BistromathConfigTest {

    private static ApplicationDocument manifest(Map<String, Object> block) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(BistromathConfig.BLOCK, block);
        return new ApplicationDocument("application", BistromathConfig.BLOCK,
                "Invoices", null, config, new LinkedHashMap<>());
    }

    @Test
    void from_bothKeys_areRead() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("landing", "list");
        block.put("init", "setup.js");

        BistromathConfig config = BistromathConfig.from(manifest(block));

        assertThat(config.landing()).isEqualTo("list");
        assertThat(config.init()).isEqualTo("setup.js");
        assertThat(config.program()).isEqualTo("setup.js");
    }

    @Test
    void program_withoutInit_isMainJs() {
        assertThat(BistromathConfig.empty().program())
                .isEqualTo(BistromathConfig.DEFAULT_PROGRAM)
                .isEqualTo("main.js");
    }

    @Test
    void from_missingBlock_isEmptyRatherThanAnError() {
        ApplicationDocument bare = new ApplicationDocument("application",
                BistromathConfig.BLOCK, null, null, new LinkedHashMap<>(),
                new LinkedHashMap<>());

        BistromathConfig config = BistromathConfig.from(bare);

        assertThat(config.landing()).isNull();
        assertThat(config.init()).isNull();
    }

    @Test
    void from_emptyBlock_isValid() {
        BistromathConfig config = BistromathConfig.from(manifest(new LinkedHashMap<>()));

        assertThat(config.landing()).isNull();
    }

    /**
     * A manifest from the first build carries {@code views:}, {@code tables:},
     * {@code scripts:} and a {@code schemaVersion}. Ignoring them is right: the
     * app is fully describable without them, and refusing to open an app over a
     * key that no longer means anything would be the worse failure.
     */
    @Test
    void from_keysOfTheOlderSchema_areIgnored() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("schemaVersion", 1);
        block.put("views", List.of(Map.of("handle", "main", "ref", "views/main.yaml")));
        block.put("tables", List.of());
        block.put("scripts", List.of("scripts/main.js"));
        block.put("landing", "main");

        BistromathConfig config = BistromathConfig.from(manifest(block));

        assertThat(config.landing()).isEqualTo("main");
        assertThat(config.program()).isEqualTo("main.js");
    }

    @Test
    void from_blockThatIsNotAMapping_isRejected() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(BistromathConfig.BLOCK, "nope");
        ApplicationDocument doc = new ApplicationDocument("application",
                BistromathConfig.BLOCK, null, null, config, new LinkedHashMap<>());

        assertThatThrownBy(() -> BistromathConfig.from(doc))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`custom` is not a mapping");
    }

    /**
     * The block sits at the <b>top level</b> of the manifest document, not
     * nested under a {@code config:} key.
     *
     * <p>{@code ApplicationDocument.config} is a logical grouping;
     * {@link ApplicationCodec} hoists every top-level map into it on read and
     * writes it back flat on serialise. Nesting under a literal {@code config:}
     * would be read as a block *called* "config", and
     * {@code config().get("custom")} would be null — an app with a silently
     * empty manifest, the worst shape this could fail in.
     *
     * <p>The test goes through the codec, because building the document by hand
     * is exactly what cannot catch this.
     */
    @Test
    void manifest_roundTripsThroughTheCodecWithTheBlockAtTopLevel() {
        BistromathConfig original = new BistromathConfig("list", "setup.js", List.of(), null, null);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(BistromathConfig.BLOCK, original.toBlock());
        ApplicationDocument doc = new ApplicationDocument("application",
                BistromathConfig.BLOCK, "Invoices", null, config, new LinkedHashMap<>());

        String yaml = ApplicationCodec.serialize(doc, "application/yaml");
        BistromathConfig reread = BistromathConfig.from(
                ApplicationCodec.parse(yaml, "application/yaml"));

        assertThat(reread).isEqualTo(original);
        assertThat(yaml).contains("\ncustom:").doesNotContain("\nconfig:");
    }

    @Test
    void toBlock_withoutKeys_isEmptySoTheManifestStaysBare() {
        assertThat(BistromathConfig.empty().toBlock()).isEmpty();
    }

    // ── handles ───────────────────────────────────────────────────

    /**
     * A handle is a file name now, and it lands in a URL and in an
     * {@code AppTarget} (which forbids {@code |}). So this is a constraint on
     * what a view document may be called.
     */
    @Test
    void isValidHandle_acceptsSlugs() {
        assertThat(BistromathConfig.isValidHandle("main")).isTrue();
        assertThat(BistromathConfig.isValidHandle("invoice-detail")).isTrue();
        assertThat(BistromathConfig.isValidHandle("v2_list")).isTrue();
        assertThat(BistromathConfig.isValidHandle("a1")).isTrue();
    }

    @Test
    void isValidHandle_rejectsWhatCannotTravelInALink() {
        assertThat(BistromathConfig.isValidHandle("Main")).isFalse();
        assertThat(BistromathConfig.isValidHandle("my view")).isFalse();
        assertThat(BistromathConfig.isValidHandle("a|b")).isFalse();
        assertThat(BistromathConfig.isValidHandle("-lead")).isFalse();
        assertThat(BistromathConfig.isValidHandle("")).isFalse();
    }
}

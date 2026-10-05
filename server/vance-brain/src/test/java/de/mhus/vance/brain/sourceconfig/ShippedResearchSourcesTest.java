package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The shipped default source documents under
 * {@code vance-defaults/_vance/config/research/} are the only search sources a
 * fresh installation has — the routing defaults name them and the dispatcher
 * finds them through the classpath layer of the document cascade. If one of
 * them is malformed, research starts empty and the operator has nothing to
 * fix it with. This test parses each one exactly the way the loader does at
 * dispatch time.
 *
 * <p>The shipped set is <em>keyless only</em>: sources that need a credential
 * are templates the operator fills in, never silent defaults that burn
 * somebody's credits.
 */
class ShippedResearchSourcesTest {

    private static final String RESOURCE_DIR = "/vance-defaults/_vance/config/research/";

    /** filename (→ instance id) to the protocol the document must declare. */
    private static final Map<String, String> SHIPPED = new LinkedHashMap<>();

    static {
        SHIPPED.put("wikipedia", "wikipedia");
        SHIPPED.put("openalex", "openalex");
        SHIPPED.put("arxiv", "arxiv");
        SHIPPED.put("pubmed", "pubmed");
        SHIPPED.put("openlibrary", "openlibrary");
        SHIPPED.put("hackernews", "hackernews");
        SHIPPED.put("firecrawl-keyless", "firecrawl");
    }

    private final SourceConfigLoader loader = new SourceConfigLoader(Mockito.mock(DocumentService.class));

    @Test
    void shipped_sources_parse_and_declare_their_protocol() {
        SHIPPED.forEach((name, protocol) -> {
            SourceConfig config = parse(name);

            assertThat(config.protocol())
                    .as("protocol of shipped source '%s'", name)
                    .isEqualTo(protocol);
            assertThat(config.enabled())
                    .as("shipped source '%s' must be enabled out of the box", name)
                    .isTrue();
        });
    }

    @Test
    void shipped_sources_carry_the_kind_marker() {
        // The body marker is the durable half of kind detection (the folder
        // is the other half) — a shipped document without it would be the
        // first one to lose its edit form.
        SHIPPED.keySet().forEach(name -> {
            SourceConfig config = parse(name);
            assertThat(config.extras())
                    .as("$meta marker of shipped source '%s'", name)
                    .containsKey("$meta");
        });
    }

    @Test
    void searxng_ships_as_a_disabled_example_with_a_placeholder_url() {
        SourceConfig config = parse("searxng");

        assertThat(config.protocol()).isEqualTo("searxng");
        // A working instance URL cannot be invented for the operator — the
        // example stays disabled until they place their own document.
        assertThat(config.enabled()).isFalse();
    }

    private SourceConfig parse(String name) {
        return loader.parse(name, SourceConfigPaths.RESEARCH + name + ".yaml", readResource(name + ".yaml"));
    }

    private static String readResource(String filename) {
        try (InputStream in = ShippedResearchSourcesTest.class.getResourceAsStream(RESOURCE_DIR + filename)) {
            assertThat(in).as("shipped source '%s' must exist", filename).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

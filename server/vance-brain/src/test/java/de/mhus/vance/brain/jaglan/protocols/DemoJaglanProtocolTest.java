package de.mhus.vance.brain.jaglan.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The demo mount is a fixture, and a fixture that is not itself pinned is a
 * second thing to debug when a test using it fails.
 *
 * <p>What matters here is that it models the parameterised-read contract
 * <i>correctly</i> — declaring the capability, changing its answer with the
 * query, and refusing rather than ignoring — because anything that uses it as
 * a reference will inherit whatever it does.
 */
class DemoJaglanProtocolTest {

    private static final String MOUNT = "demo";

    private JaglanInstance instance() {
        return new DemoJaglanProtocol().instantiate(new JaglanInstanceConfig(
                MOUNT, DemoJaglanProtocol.ID, "", "", () -> null, "acme", "research", Map.of()));
    }

    @Test
    void capabilities_declareTheParameterisedReadAndReadOnlyAccess() {
        var caps = instance().capabilities();

        // Without the declaration the dispatcher refuses every query, so this
        // is the one flag the whole fixture depends on.
        assertThat(caps.supportsQuery()).isTrue();
        assertThat(caps.access()).isEqualTo(MountAccess.RO);
    }

    @Test
    void list_showsBothDocumentsAndNotTheParameterisedView() {
        List<MountedStat> entries = instance().list("");

        // A view is not a thing that exists — it is a thing you can ask for.
        assertThat(entries).extracting(MountedStat::path)
                .containsExactlyInAnyOrder("readme.md", "analysis.yaml");
    }

    @Test
    void open_withoutQuery_isAChartOverTheDefaultWindow() {
        String body = read(instance().open("analysis.yaml"));

        assertThat(body).startsWith("$meta:\n  kind: chart");
        assertThat(body).contains("2026-01-01").contains("2026-06-30");
    }

    @Test
    void open_withQuery_changesTheDataAndKeepsTheKind() {
        String plain = read(instance().open("analysis.yaml"));
        String view = read(instance().open("analysis.yaml", "from=2026-02-01&to=2026-03-31"));

        assertThat(view).isNotEqualTo(plain);
        assertThat(view).contains("2026-02-01").contains("2026-03-31");
        // Same kind with and without a query: the reader renders from the mime
        // on its own row, so a view that changed type would not survive.
        assertThat(view).startsWith("$meta:\n  kind: chart");
    }

    @Test
    void open_withTheSameQueryTwice_isByteIdentical() {
        // Deterministic on purpose. A fixture whose output moved on its own
        // would make "the query arrived" indistinguishable from "something
        // changed".
        String first = read(instance().open("analysis.yaml", "from=2026-02-01&to=2026-03-31"));
        String second = read(instance().open("analysis.yaml", "from=2026-02-01&to=2026-03-31"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void open_withAnUnparseableDate_isRefusedRatherThanDefaulted() {
        // Substituting a window nobody asked for is the failure mode with no
        // visible symptom — a chart for the wrong period, correct in every
        // other respect.
        assertThatThrownBy(() -> instance().open("analysis.yaml", "from=letzten-Montag"))
                .isInstanceOf(JaglanProtocolException.class)
                .hasMessageContaining("from");
    }

    @Test
    void open_withAnInvertedWindow_isRefused() {
        assertThatThrownBy(() ->
                instance().open("analysis.yaml", "from=2026-06-01&to=2026-01-01"))
                .isInstanceOf(JaglanProtocolException.class)
                .hasMessageContaining("must be before");
    }

    @Test
    void open_queryAgainstAPathThatTakesNone_isRefused() {
        assertThatThrownBy(() -> instance().open("readme.md", "from=2026-01-01"))
                .isInstanceOf(JaglanProtocolException.class)
                .hasMessageContaining("takes no parameters");
    }

    @Test
    void stat_unknownPath_isEmptyAndNotAThrow() {
        // Empty is authoritative — the reader forgets the row. A throw would
        // tell it the mount is broken.
        assertThat(instance().stat("nope.yaml")).isEmpty();
    }

    @Test
    void stat_ofADocument_carriesTheMimeTheViewWillKeep() {
        Optional<MountedStat> stat = instance().stat("analysis.yaml");

        assertThat(stat).isPresent();
        assertThat(stat.orElseThrow().mimeType()).isEqualTo("text/yaml");
        assertThat(stat.orElseThrow().directory()).isFalse();
    }

    private static String read(InputStream in) {
        try (InputStream open = in) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

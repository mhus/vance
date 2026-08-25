package de.mhus.vance.brain.jaglan.protocols;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * A mount that computes its own content — the reference case for
 * <b>parameterised views</b>, and the only one that needs no second process.
 *
 * <p>It exists because the feature is otherwise unreachable by hand: a query
 * can be produced by the REST endpoint or by an agent, but until the form kind
 * is built nobody can point at a real source and see one work. Every other
 * protocol either serves files ({@code local}) or needs a foreign application
 * running ({@code ode}).
 *
 * <p><b>Everything here is derived from the path and the query</b> — no disk,
 * no network, no state. That is what makes it usable as a fixture: the same
 * address gives the same bytes on every pod and in every test, and a wrong
 * answer is a bug in the plumbing rather than in somebody's data.
 *
 * <h2>What it serves</h2>
 * <pre>
 *   readme.md                        what this mount is, in prose
 *   report.md                        a document that links to one named view
 *   analysis.yaml                    kind: chart over a default window
 *   analysis.yaml?from=&amp;to=          the same chart over the window you asked for
 * </pre>
 *
 * <p>{@code analysis.yaml} keeps its kind and its mime with or without a query,
 * which is the contract rule for a computed view rather than a convenience: the
 * reader renders from the mime on its own metadata row, so a view that changed
 * type would not survive the trip. What the query changes is the data.
 *
 * <p>It is read-only, and there is nothing behind it to write to.
 */
public class DemoJaglanInstance implements JaglanInstance {

    /** The parameterised document — the one worth pointing a query at. */
    static final String ANALYSIS = "analysis.yaml";

    static final String README = "readme.md";

    /**
     * A document whose only content is a link to one named view.
     *
     * <p>Here because a query is otherwise something only a URL bar or an
     * agent can produce: this is the one place a person can <em>click</em> on
     * a parameterised view, which is also the only way to find out whether the
     * consumers on that path carry the query or drop it.
     */
    static final String REPORT = "report.md";

    /** Window used when nobody asked for one. */
    static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 1, 1);
    static final LocalDate DEFAULT_TO = LocalDate.of(2026, 6, 30);

    /** The window {@link #REPORT} links to — deliberately not the default one,
     *  so "the query arrived" is visible in the rendered chart's own title. */
    static final String LINKED_QUERY = "from=2026-02-01&to=2026-03-31";

    /** Points per chart, whatever the window — a demo, not a data set. */
    private static final int POINTS = 12;

    private final String mount;
    private final Duration metadataTtl;

    DemoJaglanInstance(String mount, Duration metadataTtl) {
        this.mount = mount;
        this.metadataTtl = metadataTtl;
    }

    @Override
    public String mount() {
        return mount;
    }

    @Override
    public String protocolId() {
        return DemoJaglanProtocol.ID;
    }

    @Override
    public JaglanCapabilities capabilities() {
        return new JaglanCapabilities(
                MountAccess.RO,
                /* canSearch */ false,
                /* itemCount */ 3L,
                metadataTtl,
                /* maxBytes */ null,
                /* supportsQuery */ true,
                "Demo (computed)");
    }

    @Override
    public Optional<MountedStat> stat(String pathInMount) {
        String path = normalise(pathInMount);
        if (path.isEmpty()) {
            return Optional.of(MountedStat.directory(""));
        }
        if (ANALYSIS.equals(path)) {
            return Optional.of(new MountedStat(
                    path, false, body(DEFAULT_FROM, DEFAULT_TO).length,
                    "text/yaml", null, null, MountAccess.RO));
        }
        if (README.equals(path)) {
            return Optional.of(new MountedStat(
                    path, false, readme().length, "text/markdown", null, null, MountAccess.RO));
        }
        if (REPORT.equals(path)) {
            return Optional.of(new MountedStat(
                    path, false, report().length, "text/markdown", null, null, MountAccess.RO));
        }
        // An answer, not a failure: the reader forgets the row for this path.
        return Optional.empty();
    }

    @Override
    public List<MountedStat> list(String pathInMount) {
        if (!normalise(pathInMount).isEmpty()) {
            // One flat folder. Anything deeper is empty rather than an error —
            // an empty listing is authoritative and simply removes nothing.
            return List.of();
        }
        return List.of(
                stat(README).orElseThrow(),
                stat(REPORT).orElseThrow(),
                stat(ANALYSIS).orElseThrow());
    }

    @Override
    public InputStream open(String pathInMount) {
        return open(pathInMount, null);
    }

    @Override
    public InputStream open(String pathInMount, @Nullable String query) {
        String path = normalise(pathInMount);
        if (README.equals(path) || REPORT.equals(path)) {
            if (query != null && !query.isBlank()) {
                // Refused rather than ignored, even here: handing back the
                // plain file is exactly the silent-wrong-answer this whole
                // feature exists to prevent, and a demo that models it wrongly
                // teaches the wrong thing.
                throw new JaglanProtocolException(mount,
                        "mount '" + mount + "': '" + path + "' takes no parameters");
            }
            return stream(README.equals(path) ? readme() : report());
        }
        if (ANALYSIS.equals(path)) {
            Map<String, String> params = parse(query);
            LocalDate from = date(params.get("from"), DEFAULT_FROM, "from");
            LocalDate to = date(params.get("to"), DEFAULT_TO, "to");
            if (!from.isBefore(to)) {
                throw new JaglanProtocolException(mount,
                        "mount '" + mount + "': 'from' (" + from + ") must be before 'to' ("
                                + to + ")");
            }
            return stream(body(from, to));
        }
        throw new JaglanProtocolException(mount,
                "mount '" + mount + "' has no '" + path + "'");
    }

    // ── content ──────────────────────────────────────────────────────

    /**
     * A {@code kind: chart} document over the requested window.
     *
     * <p>The series is a deterministic function of the dates — no clock, no
     * randomness — so the same query always renders the same chart. A demo
     * whose output moved on its own would be useless for telling "the query
     * arrived" apart from "something changed".
     */
    private static byte[] body(LocalDate from, LocalDate to) {
        long span = to.toEpochDay() - from.toEpochDay();
        StringBuilder yaml = new StringBuilder()
                .append("$meta:\n")
                .append("  kind: chart\n")
                .append("chart:\n")
                .append("  chartType: line\n")
                .append("  title: Demo trend ").append(from).append(" … ").append(to).append('\n')
                .append("xAxis:\n")
                .append("  type: time\n")
                .append("  label: Date\n")
                .append("yAxis:\n")
                .append("  type: value\n")
                .append("  label: Value\n")
                .append("series:\n")
                .append("  - name: computed\n")
                .append("    data:\n");
        for (int i = 0; i < POINTS; i++) {
            LocalDate at = from.plusDays(span * i / (POINTS - 1));
            yaml.append("      - { x: ").append(at)
                    .append(", y: ").append(value(at)).append(" }\n");
        }
        return yaml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A stable pseudo-measurement for a date.
     *
     * <p>Derived from the epoch day so that a different window produces a
     * visibly different curve — the point of the demo is that changing a
     * parameter changes what you see.
     */
    private static int value(LocalDate at) {
        long day = at.toEpochDay();
        return (int) (50 + 40 * Math.sin(day / 9.0) + (day % 7));
    }

    private static byte[] readme() {
        return ("""
                # Demo mount

                A mount that computes its own content. Nothing here is on disk.

                | Path | |
                |---|---|
                | `readme.md` | this file |
                | `report.md` | a document that links to one named view |
                | `analysis.yaml` | a `kind: chart` document |

                ## Parameterised view

                `analysis.yaml` answers differently when you give it a window:

                ```
                _ext/<mount>/analysis.yaml?from=2026-02-01&to=2026-03-31
                ```

                Same path, same document row, same kind — different data. A
                query is a read parameter, never part of the address, so the
                view is not listed here and never will be: the parameter space
                belongs to the source and is not finite.

                Both parameters are ISO dates and both are optional. `from`
                must be before `to`, and anything else is refused rather than
                quietly corrected.

                `report.md` holds that address as a clickable link.
                """).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A Markdown document whose entire point is the link inside it.
     *
     * <p>The link is the <b>relative</b> form, {@code vance:analysis.yaml} —
     * the document next to this one, named the way one document names its
     * neighbour. The scheme is a marker and not a mode (see
     * {@code DocumentRefResolver}): the slashes decide, so this is the same
     * reference as a bare {@code analysis.yaml}, only visibly one of ours.
     *
     * <p>It is written that way on purpose rather than spelled out from
     * {@code _ext/}: a mount folder is named by whoever wrote the mount
     * document, so an absolute link would hard-code a name this instance only
     * learns at construction time — and would keep working while saying
     * something false about where the target lives.
     */
    private byte[] report() {
        return ("""
                # Linked view

                One link, and that is the whole document.

                [Demo trend 2026-02-01 … 2026-03-31](vance:%s?%s)

                The target is `%s` next to this file, read over a window. Same
                path, same document row, same kind — the query is a parameter
                of the read, so nothing here is a second document and nothing
                new shows up in the folder.

                ## About the link

                It is the relative form: `vance:` marks the reference as one of
                ours and says nothing about where it points — the slashes do
                that. So this reads exactly like a bare `%s`, while
                `vance:/%s` would mean the project root and `vance://p/%s`
                a different project.

                Relative on purpose: the folder this file sits in is named by
                whoever wrote the mount document, and a link that spells that
                name out would still resolve while claiming something false
                about where its target lives.

                ## What it is for

                Reading the link tells you nothing; following it does. A
                consumer that takes the path and drops the query answers with
                the default window (`%s … %s`) and looks entirely successful
                doing it. The chart carries its window in the title, so the
                two are told apart by looking.
                """).formatted(
                        ANALYSIS, LINKED_QUERY,
                        ANALYSIS,
                        ANALYSIS, ANALYSIS, ANALYSIS,
                        DEFAULT_FROM, DEFAULT_TO)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ── internals ────────────────────────────────────────────────────

    private LocalDate date(@Nullable String raw, LocalDate fallback, String name) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // Refused, not defaulted: silently substituting a window nobody
            // asked for is the failure mode with no visible symptom.
            throw new JaglanProtocolException(mount,
                    "mount '" + mount + "': '" + name + "' is not an ISO date: " + raw);
        }
    }

    /**
     * The query as a map, last value wins.
     *
     * <p>Enough for a demo whose parameters are single-valued. A source with
     * multiple-choice inputs would keep every value — see {@code OdeQuery} on
     * the SDK side, which does.
     */
    private static Map<String, String> parse(@Nullable String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (StringUtils.isBlank(query)) {
            return out;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String raw) {
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String normalise(@Nullable String pathInMount) {
        String path = pathInMount == null ? "" : pathInMount.trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static InputStream stream(byte[] body) {
        return new ByteArrayInputStream(body);
    }
}

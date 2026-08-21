package de.mhus.vance.shared.starred;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for {@code vance-starred} — a user's list of starred
 * documents. Second member of the {@code vance-*} kind family that types
 * Vance's own configuration documents, after {@code vance-workflow}.
 *
 * <p><b>Diagnosis, not enforcement.</b> {@code validate} is advisory by
 * contract — findings never block a write — so this handler cannot keep the file
 * well-formed. It is feedback where the handwriting happens: the Cortex validate
 * action and {@code kind_validate}. {@link StarredService} carries its own
 * lenient read and round-trip-safe write for exactly that reason.
 *
 * <p>Beyond shape and required fields it checks the two things nothing else in
 * the system would notice:
 *
 * <ul>
 *   <li><b>Duplicate {@code (project, path)}</b> (error) — breaks the star
 *       toggle's idempotency: it finds the first, the second stays behind as a
 *       ghost.</li>
 *   <li><b>Two resolvable entries with the same {@code type}</b> (warning) — the
 *       invisible failure mode of the technical half. {@code findByType} silently
 *       takes the first, a "send to" lands in the wrong document, and nothing
 *       anywhere says so.</li>
 * </ul>
 *
 * <p><b>What it deliberately does not check:</b> whether a target exists, and
 * whether its live kind still matches the stored one. {@code DocRefs} is bound
 * to one tenant/project — every path it takes is project-relative — and this is
 * a cross-project list, so most entries are out of its reach. Widening that SPI
 * for one kind would also mean giving it a permission axis it does not have.
 * Existence and drift are answered by {@link StarredService#reconcile}, which
 * has the security context. Same-project entries could be checked here; they are
 * not, because that would make the diagnosis depend on where the target happens
 * to live.
 *
 * <p>{@code detects()} stays {@code false}: a YAML with {@code project} /
 * {@code path} / {@code title} keys is far too generic a shape to claim, and a
 * loose detector wins over more specific kinds that sort after it.
 */
@Service
public class StarredKindHandler implements KindHandler {

    @Override
    public String getName() {
        return StarredCodec.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String location = StringUtils.isBlank(ctx.docPath()) ? StarredCodec.KIND : ctx.docPath();
        StarredCodec.Result parsed = StarredCodec.parse(content, location);

        List<Finding> findings = new ArrayList<>(parsed.findings());
        findings.addAll(crossEntryFindings(parsed.document(), location));
        return List.copyOf(findings);
    }

    /**
     * Checks that only make sense across the whole list. Kept separate from the
     * codec, which is about the wire form of a single entry.
     */
    static List<Finding> crossEntryFindings(StarredDocument doc, String location) {
        List<Finding> findings = new ArrayList<>();

        Set<StarredItem.Key> seen = new HashSet<>();
        for (StarredItem item : doc.items()) {
            if (!seen.add(item.key())) {
                findings.add(Finding.error(location, StarredCodec.KIND + "-duplicate",
                        "'" + item.project() + "/" + item.path() + "' is listed more than once"
                                + " — only the first entry is ever used"));
            }
        }

        // Only resolvable entries can be picked by a lookup, so a disabled
        // duplicate type is not a conflict.
        Map<String, List<StarredItem>> byType = new LinkedHashMap<>();
        for (StarredItem item : doc.resolvable()) {
            if (item.type() == null || item.type().isBlank()) continue;
            byType.computeIfAbsent(item.type(), k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<StarredItem>> e : byType.entrySet()) {
            if (e.getValue().size() < 2) continue;
            StarredItem winner = e.getValue().get(0);
            findings.add(Finding.warning(location, StarredCodec.KIND + "-ambiguous-type",
                    e.getValue().size() + " entries have type '" + e.getKey()
                            + "'; a lookup for it always returns the first ('"
                            + winner.project() + "/" + winner.path() + "')"));
        }

        return findings;
    }
}

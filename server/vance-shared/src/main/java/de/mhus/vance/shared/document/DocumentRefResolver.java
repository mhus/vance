package de.mhus.vance.shared.document;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Central, deterministic resolver for document references — the one
 * place that turns an authored reference (in a recipe, skill, guard,
 * embed, link, …) into a {@code (projectId, path)} pair. No I/O, no LLM:
 * pure string resolution following RFC-3986 reference resolution against
 * the base {@code vance://<currentProject>/<referrerDir>/}.
 *
 * <p>One grammar, {@code vance:} scheme optional:
 * <ul>
 *   <li>{@code path} — relative, resolved against the referrer directory
 *       ("next to the skill").</li>
 *   <li>{@code /path} — absolute in the <em>current</em> project (from its root).</li>
 *   <li>{@code //projectId/path} — the same path in another project
 *       (authority = project {@code name}, unique).</li>
 *   <li>{@code vance:/path}, {@code vance://projectId/path} — the same,
 *       with the scheme; a scheme always makes the reference absolute
 *       (never relative), so {@code vance:foo} means {@code /foo} in the
 *       current project.</li>
 * </ul>
 *
 * <p>A {@code ?query} (e.g. {@code ?kind=…}) is split off and returned on
 * {@link DocumentRef#query()}; a {@code #fragment} is dropped. Paths are
 * canonicalised ({@code .}/{@code ..} collapsed, empty and duplicate
 * separators removed); a {@code ..} that escapes above the project root is
 * a {@link DocumentRefException}.
 *
 * <p><b>Enforcement stays at the call site.</b> The resolver only computes
 * the target {@code (projectId, path)} — a cross-project reference does not
 * imply access. The caller loads the document through its normal path and
 * applies the usual permission check on the resolved project.
 *
 * <p>See {@code specification/public/document-refs.md}.
 */
@Service
public class DocumentRefResolver {

    /** The optional URI scheme. A ref carrying it is always absolute. */
    public static final String SCHEME = "vance:";

    /**
     * Resolves {@code ref} against {@code ctx}.
     *
     * @throws DocumentRefException on a blank ref, a blank authority, or a
     *                              {@code ..} segment escaping above root
     */
    public DocumentRef resolve(String ref, DocumentRefContext ctx) {
        if (ref == null || ref.isBlank()) {
            throw new DocumentRefException("document ref must not be blank");
        }
        if (ctx == null) {
            throw new DocumentRefException("document ref context must not be null");
        }
        String raw = ref.trim();

        // Drop #fragment, split off ?query.
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        @Nullable String query = null;
        int q = raw.indexOf('?');
        if (q >= 0) {
            String qs = raw.substring(q + 1);
            query = qs.isEmpty() ? null : qs;
            raw = raw.substring(0, q);
        }

        // A scheme makes the reference absolute — force a leading slash so
        // `vance:foo` reads as `/foo` (current project root), never relative.
        if (raw.startsWith(SCHEME)) {
            raw = raw.substring(SCHEME.length());
            if (!raw.startsWith("/")) {
                raw = "/" + raw;
            }
        }

        String projectId;
        String rawPath;
        String base;
        if (raw.startsWith("//")) {
            // //authority/path — another project.
            String rest = raw.substring(2);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                projectId = rest;
                rawPath = "";
            } else {
                projectId = rest.substring(0, slash);
                rawPath = rest.substring(slash + 1);
            }
            if (projectId.isBlank()) {
                throw new DocumentRefException("document ref '" + ref + "' has a blank project");
            }
            base = "";
        } else if (raw.startsWith("/")) {
            // /path — current project, from root.
            projectId = ctx.currentProjectId();
            rawPath = raw.substring(1);
            base = "";
        } else {
            // path — relative to the referrer directory.
            projectId = ctx.currentProjectId();
            rawPath = raw;
            base = ctx.referrerDir();
        }

        String merged = base.isEmpty() ? rawPath : base + "/" + rawPath;
        return new DocumentRef(projectId, canonicalize(merged, ref), query);
    }

    /**
     * Collapses {@code .}/{@code ..}, drops empty segments; a {@code ..}
     * above root escapes and is rejected. Returns a canonical path with no
     * leading or trailing slash.
     */
    private static String canonicalize(String path, String originalRef) {
        Deque<String> stack = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (stack.isEmpty()) {
                    throw new DocumentRefException(
                            "document ref '" + originalRef + "' escapes above the project root");
                }
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        return String.join("/", stack);
    }
}

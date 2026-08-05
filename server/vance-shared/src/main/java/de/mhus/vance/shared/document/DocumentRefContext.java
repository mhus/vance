package de.mhus.vance.shared.document;

/**
 * The base a relative {@link DocumentRefResolver} reference resolves
 * against: the current project plus the directory of the referring
 * document. Equivalent to the base URI {@code vance://<currentProjectId>/<referrerDir>/}
 * in RFC-3986 reference resolution.
 *
 * @param currentProjectId project a same-project ref ({@code /path} or a
 *                         relative {@code path}) resolves within
 * @param referrerDir      canonical directory of the referring document
 *                         (no leading/trailing slash); {@code ""} = project
 *                         root. A relative ref resolves against it.
 */
public record DocumentRefContext(String currentProjectId, String referrerDir) {

    public DocumentRefContext {
        if (currentProjectId == null || currentProjectId.isBlank()) {
            throw new DocumentRefException("currentProjectId must not be blank");
        }
        referrerDir = normalizeDir(referrerDir);
    }

    /** Context rooted at the project root (relative refs resolve from root). */
    public static DocumentRefContext root(String currentProjectId) {
        return new DocumentRefContext(currentProjectId, "");
    }

    /** Context with an explicit referrer directory. */
    public static DocumentRefContext of(String currentProjectId, String referrerDir) {
        return new DocumentRefContext(currentProjectId, referrerDir);
    }

    /**
     * Context whose referrer directory is the parent folder of
     * {@code referrerDocPath} — so a bare {@code guard.js} next to a
     * skill at {@code _vance/skills/x/skill.yaml} resolves to
     * {@code _vance/skills/x/guard.js}.
     */
    public static DocumentRefContext fromReferrerDocument(
            String currentProjectId, String referrerDocPath) {
        String dir = normalizeDir(referrerDocPath);
        int slash = dir.lastIndexOf('/');
        return new DocumentRefContext(currentProjectId, slash < 0 ? "" : dir.substring(0, slash));
    }

    private static String normalizeDir(String dir) {
        if (dir == null) {
            return "";
        }
        String d = dir.trim();
        while (d.startsWith("/")) {
            d = d.substring(1);
        }
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }
}

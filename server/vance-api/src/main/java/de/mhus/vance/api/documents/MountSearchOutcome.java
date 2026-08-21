package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What happened to a search that was issued inside a mounted folder
 * ({@code _ext/<mount>/…}). {@code null} on an ordinary listing — no mount, or
 * no search term.
 *
 * <p><b>Why this exists at all.</b> The folder listing's search is a Mongo
 * query over the rows it has. Inside a mount those rows are only the entries
 * somebody happened to browse to, so the same query that is complete anywhere
 * else is systematically incomplete here — and used to answer "0 results" for
 * a source holding tens of thousands of matches. Silently incomplete is the
 * worst of the available failure modes: a reader concludes the file is not
 * there.
 *
 * <p>So the search is delegated to the source, and the outcome travels with
 * the result. A client that ignores this field is no worse off than before;
 * one that reads it can say which question was actually answered.
 */
@GenerateTypeScript("documents")
public enum MountSearchOutcome {

    /**
     * The source searched its own catalogue and these are its hits.
     *
     * <p>They span the <b>whole mount</b>, not the folder that was being
     * browsed: the contract has no way to scope a search to a subtree, and
     * narrowing the results afterwards would turn a useful answer into an
     * empty one — a file seven levels down is exactly what somebody searches
     * for rather than browses to. Clients should present these as mount-wide.
     *
     * <p>Not paged. The contract has no cursor, so the first page carries
     * everything the source returned and later pages are empty.
     */
    DELEGATED,

    /**
     * The mount declares that it cannot search, so nothing was asked.
     *
     * <p>The result holds whatever rows are cached, which is not an answer to
     * the question. Clients should say so and offer browsing instead.
     */
    UNSUPPORTED,

    /**
     * The source was asked and could not answer — unreachable, or muted after
     * a recent failure.
     *
     * <p>Distinct from {@link #UNSUPPORTED}: this one is worth retrying, and
     * it means the mount exists and normally does answer.
     */
    UNAVAILABLE
}

package de.mhus.vance.brain.milliways;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.permission.SecurityContext;

/**
 * What a {@link ShareHandler} gets: the {@link ShareTarget} plus the
 * resolved document. A handler never has to look the document up, and
 * never has to wonder whether the sharer may read it — {@link
 * MilliwaysService} enforced {@code Document READ} before building this.
 *
 * <p>Project-scoped, deliberately without {@code sessionId}/{@code
 * processId}: sharing is an act on a document, not inside a session.
 */
public record ShareScope(
        SecurityContext ctx,
        String tenantId,
        String projectId,
        String path,
        DocumentDocument document) {

    static ShareScope of(ShareTarget target, DocumentDocument document) {
        return new ShareScope(
                target.ctx(), target.tenantId(), target.projectId(), target.path(), document);
    }

    /** The sharer's username — {@code UserDocument.name}. */
    public String sharer() {
        return ctx().subjectId();
    }

    /** Last path segment, i.e. the document's file name. */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

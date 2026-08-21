package de.mhus.vance.brain.milliways;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.net.SafeLink;
import de.mhus.vance.shared.permission.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link ShareHandler} gets: the {@link ShareTarget} plus, when the
 * subject names a document, the resolved one. A handler never has to look a
 * document up, and never has to wonder whether the sharer may read it —
 * {@link MilliwaysService} enforced {@code Document READ} before building this.
 *
 * <p>The {@link #subject} here is the <b>sanitised</b> one: link scheme
 * checked, snippet and title collapsed and capped. Handlers cannot forget to
 * do it because they never see the raw form.
 *
 * <p>Project-scoped, deliberately without {@code sessionId}/{@code processId}:
 * sharing is an act on a thing, not inside a session.
 */
public record ShareScope(
        SecurityContext ctx,
        String tenantId,
        String projectId,
        ShareSubject subject,
        @Nullable DocumentDocument document) {

    static ShareScope of(
            ShareTarget target, ShareSubject sanitised, @Nullable DocumentDocument document) {
        return new ShareScope(
                target.ctx(), target.tenantId(), target.projectId(), sanitised, document);
    }

    /** The sharer's username — {@code UserDocument.name}. */
    public String sharer() {
        return ctx().subjectId();
    }

    public boolean hasDocument() {
        return document != null;
    }

    /** Last path segment of the referenced document, or {@code null}. */
    public @Nullable String fileName() {
        String path = subject.documentPath();
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * One label for the thing being shared, used by the modal title, the mail
     * subject and the inbox item title — three places that would otherwise
     * each invent their own fallback and each assume a document.
     *
     * <p>Cascade: the caller's title, the document's title, the file name, the
     * link's host, then a generic word.
     */
    public String displayTitle() {
        if (subject.title() != null) return subject.title();
        if (document != null && document.getTitle() != null && !document.getTitle().isBlank()) {
            return document.getTitle();
        }
        String fileName = fileName();
        if (fileName != null) return fileName;
        String host = SafeLink.hostOf(subject.link());
        if (host != null) return host;
        return "Shared item";
    }
}

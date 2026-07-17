package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import io.milton.http.Auth;
import io.milton.http.HttpManager;
import io.milton.http.LockManager;
import io.milton.http.Request;
import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Maps {@code /brain/{tenant}/webdav/{project}/{path…}} URLs onto document-store
 * resources. Decides file vs. virtual-folder vs. Redis sidecar and returns the
 * matching {@link AbstractDavResource}, or {@code null} for a 404. All data
 * access goes through {@link DocumentService} (data ownership) and
 * {@link SidecarStore}. See {@code planning/webdav-support.md}.
 */
public class DocumentResourceFactory implements ResourceFactory {

    private final DocumentService documentService;
    private final VanceWebDavSecurityManager securityManager;
    private final SidecarStore sidecarStore;
    private final LockManager lockManager;
    private final WebDavProperties properties;

    public DocumentResourceFactory(
            DocumentService documentService,
            VanceWebDavSecurityManager securityManager,
            SidecarStore sidecarStore,
            LockManager lockManager,
            WebDavProperties properties) {
        this.documentService = documentService;
        this.securityManager = securityManager;
        this.sidecarStore = sidecarStore;
        this.lockManager = lockManager;
        this.properties = properties;
    }

    @Override
    public @Nullable Resource getResource(String host, String url) {
        return WebDavPaths.parse(url).map(this::resolve).orElse(null);
    }

    /** Resolve a parsed coordinate to a resource, or {@code null} (→ 404). */
    @Nullable Resource resolve(WebDavPaths.Coords coords) {
        if (coords.project() == null) {
            // Bare /brain/{tenant}/webdav — enumerating projects is not offered.
            return null;
        }
        String path = coords.path();
        if (path.isEmpty()) {
            return new DavFolderResource(this, coords);
        }
        String leaf = leafOf(path);
        if (properties.isSidecar(leaf)) {
            return sidecarStore.get(coords.tenantId(), coords.project(), path)
                    .map(data -> (Resource) new DavSidecarResource(this, coords, data))
                    .orElse(null);
        }
        Optional<DocumentDocument> doc = documentService.findByPath(coords.tenantId(), coords.project(), path);
        if (doc.isPresent() && !properties.isHidden(leaf)) {
            return new DavFileResource(this, coords, doc.get());
        }
        if (folderExists(coords.tenantId(), coords.project(), path)) {
            return new DavFolderResource(this, coords);
        }
        return null;
    }

    private boolean folderExists(String tenantId, String project, String path) {
        DocumentService.FolderListing listing = documentService.listByFolder(tenantId, project, path, null, 0, 1);
        return !listing.folders().isEmpty() || listing.totalFiles() > 0;
    }

    DocumentService documentService() {
        return documentService;
    }

    VanceWebDavSecurityManager securityManager() {
        return securityManager;
    }

    SidecarStore sidecarStore() {
        return sidecarStore;
    }

    LockManager lockManager() {
        return lockManager;
    }

    WebDavProperties properties() {
        return properties;
    }

    /** Username of the currently-authenticated WebDAV principal, or {@code null}. */
    @Nullable String currentUser() {
        DavPrincipal principal = currentPrincipal();
        return principal == null ? null : principal.username();
    }

    /**
     * Writer identity for document mutations. No {@code editorId} (WebDAV has no
     * live-editor connection); the username drives the {@code WriterRole.USER}
     * so the {@code lockedFor} soft-lock applies to WebDAV writes.
     */
    DocumentService.WriterIdentity currentWriter() {
        String user = currentUser();
        return DocumentService.WriterIdentity.of(null, user, user);
    }

    private static @Nullable DavPrincipal currentPrincipal() {
        Request request = HttpManager.request();
        if (request == null) {
            return null;
        }
        Auth auth = request.getAuthorization();
        if (auth == null) {
            return null;
        }
        return auth.getTag() instanceof DavPrincipal principal ? principal : null;
    }

    private static String leafOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

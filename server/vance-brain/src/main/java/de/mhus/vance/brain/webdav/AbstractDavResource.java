package de.mhus.vance.brain.webdav;

import io.milton.http.Auth;
import io.milton.http.LockInfo;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.http.LockToken;
import io.milton.http.Request;
import io.milton.http.exceptions.LockedException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.PreConditionFailedException;
import io.milton.resource.LockableResource;
import io.milton.resource.PropFindableResource;
import io.milton.resource.Resource;
import java.util.Date;
import org.jspecify.annotations.Nullable;

/**
 * Common base for all WebDAV resources backed by the document store. Carries
 * the parsed {@link WebDavPaths.Coords} and delegates authentication /
 * authorisation to the {@link VanceWebDavSecurityManager} (mirrors milton's own
 * filesystem demo, where every resource defers to the security manager).
 *
 * <p>All resources are {@link LockableResource}: locking is delegated to the
 * Redis-backed lock manager. This is also what makes milton's {@code OPTIONS}
 * advertise {@code DAV: 1,2} and enforce locks on writes. LOCK/UNLOCK request
 * dispatch itself is handled in {@code WebDavLockService} (invoked from the
 * filter), so no level-2 protocol needs registering. See
 * {@code planning/webdav-support.md}.
 */
public abstract class AbstractDavResource implements Resource, PropFindableResource, LockableResource {

    protected final DocumentResourceFactory factory;
    private final WebDavPaths.Coords coords;

    protected AbstractDavResource(DocumentResourceFactory factory, WebDavPaths.Coords coords) {
        this.factory = factory;
        this.coords = coords;
    }

    public WebDavPaths.Coords coords() {
        return coords;
    }

    /** {@code true} for collections (project root, folders); {@code false} for files. */
    abstract boolean isCollection();

    @Override
    public Object authenticate(String user, String password) {
        return factory.securityManager().authenticate(user, password);
    }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth) {
        return factory.securityManager().authorise(request, method, auth, this);
    }

    @Override
    public String getRealm() {
        return factory.securityManager().getRealm(null);
    }

    @Override
    public @Nullable String getUniqueId() {
        return null;
    }

    @Override
    public @Nullable Date getModifiedDate() {
        return null;
    }

    @Override
    public @Nullable Date getCreateDate() {
        return null;
    }

    @Override
    public @Nullable String checkRedirect(Request request) {
        return null;
    }

    // ─── LockableResource ────────────────────────────────────────────────

    @Override
    public LockResult lock(LockTimeout timeout, LockInfo lockInfo)
            throws NotAuthorizedException, PreConditionFailedException, LockedException {
        return factory.lockManager().lock(timeout, lockInfo, this);
    }

    @Override
    public LockResult refreshLock(String token, LockTimeout timeout)
            throws NotAuthorizedException, PreConditionFailedException {
        return factory.lockManager().refresh(token, timeout, this);
    }

    @Override
    public void unlock(String tokenId) throws NotAuthorizedException, PreConditionFailedException {
        factory.lockManager().unlock(tokenId, this);
    }

    @Override
    public @Nullable LockToken getCurrentLock() {
        return factory.lockManager().getCurrentToken(this);
    }

    /** Leaf name of {@code path}, or the project name for the root collection. */
    static String leafName(WebDavPaths.Coords coords) {
        String path = coords.path();
        if (path.isEmpty()) {
            String project = coords.project();
            return project == null ? "" : project;
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.CollectionResource;
import io.milton.resource.DeletableResource;
import io.milton.resource.MakeCollectionableResource;
import io.milton.resource.PutableResource;
import io.milton.resource.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A virtual folder in the project's document namespace exposed as a WebDAV
 * collection. Folders have no backing entity — they are derived from document
 * path prefixes — so {@code MKCOL} of an empty folder writes a hidden,
 * TTL-bounded {@code .vancedir} marker (§8.1) to keep it alive until real files
 * arrive. Listings hide sidecars and the marker (§6). See
 * {@code planning/webdav-support.md}.
 */
class DavFolderResource extends AbstractDavResource
        implements CollectionResource, MakeCollectionableResource, PutableResource, DeletableResource {

    private static final int PAGE = 200;

    DavFolderResource(DocumentResourceFactory factory, WebDavPaths.Coords coords) {
        super(factory, coords);
    }

    @Override
    boolean isCollection() {
        return true;
    }

    @Override
    public String getName() {
        return leafName(coords());
    }

    @Override
    public @Nullable Resource child(String childName) throws NotAuthorizedException, BadRequestException {
        return factory.resolve(childCoords(childName));
    }

    @Override
    public List<? extends Resource> getChildren() throws NotAuthorizedException, BadRequestException {
        DocumentService ds = factory.documentService();
        WebDavProperties props = factory.properties();
        String tenant = coords().tenantId();
        String project = requireProject();
        String path = coords().path();

        List<Resource> children = new ArrayList<>();
        int page = 0;
        while (true) {
            DocumentService.FolderListing listing = ds.listByFolder(tenant, project, path, null, page, PAGE);
            if (page == 0) {
                for (String folder : listing.folders()) {
                    if (!props.isHidden(folder)) {
                        children.add(new DavFolderResource(factory, childCoords(folder)));
                    }
                }
            }
            for (DocumentDocument file : listing.files()) {
                String leaf = leafOf(file.getPath());
                if (!props.isHidden(leaf)) {
                    children.add(new DavFileResource(
                            factory, new WebDavPaths.Coords(tenant, project, file.getPath()), file));
                }
            }
            if (listing.files().size() < PAGE) {
                break;
            }
            page++;
        }
        return children;
    }

    @Override
    public Resource createNew(String newName, InputStream inputStream, Long length, String contentType)
            throws IOException, ConflictException, NotAuthorizedException, BadRequestException {
        WebDavPaths.Coords childCoords = childCoords(newName);
        String childPath = childCoords.path();
        if (factory.properties().isSidecar(newName)) {
            byte[] bytes = inputStream.readAllBytes();
            factory.sidecarStore().put(coords().tenantId(), requireProject(), childPath, bytes);
            return new DavSidecarResource(factory, childCoords, bytes);
        }
        try {
            DocumentDocument created = factory.documentService().create(
                    coords().tenantId(), requireProject(), childPath,
                    null, null, DocumentService.mimeFromPath(childPath),
                    inputStream, factory.currentUser());
            return new DavFileResource(factory, childCoords, created);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ConflictException(this, e.getMessage());
        } catch (DocumentService.DocumentLockedException e) {
            throw new ConflictException(this, e.getMessage());
        }
    }

    @Override
    public CollectionResource createCollection(String newName)
            throws NotAuthorizedException, ConflictException, BadRequestException {
        WebDavPaths.Coords childCoords = childCoords(newName);
        String markerPath = childCoords.path() + "/" + factory.properties().getFolderMarkerName();
        Instant expiresAt = Instant.now().plus(factory.properties().getFolderMarkerTtl());
        factory.documentService().upsertEphemeralText(
                coords().tenantId(), requireProject(), markerPath,
                null, null, "", factory.currentUser(), expiresAt);
        return new DavFolderResource(factory, childCoords);
    }

    @Override
    public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
        // Recursive: trash visible descendants (files) and recurse into
        // subfolders, then remove this folder's own hidden marker. Scoped
        // per-resource, so it never escapes the (tenant, project) boundary.
        for (Resource child : getChildren()) {
            if (child instanceof DeletableResource deletable) {
                deletable.delete();
            }
        }
        String path = coords().path();
        if (!path.isEmpty()) {
            String markerPath = path + "/" + factory.properties().getFolderMarkerName();
            Optional<DocumentDocument> marker = factory.documentService()
                    .findByPath(coords().tenantId(), requireProject(), markerPath);
            marker.ifPresent(m -> factory.documentService().delete(m.getId(), factory.currentWriter()));
        }
    }

    private WebDavPaths.Coords childCoords(String name) {
        String path = coords().path();
        String childPath = path.isEmpty() ? name : path + "/" + name;
        return new WebDavPaths.Coords(coords().tenantId(), coords().project(), childPath);
    }

    private String requireProject() {
        String project = coords().project();
        if (project == null) {
            throw new IllegalStateException("Folder resource has no project: " + coords());
        }
        return project;
    }

    private static String leafOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

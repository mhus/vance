package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import io.milton.common.RangeUtils;
import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.CollectionResource;
import io.milton.resource.CopyableResource;
import io.milton.resource.DeletableResource;
import io.milton.resource.GetableResource;
import io.milton.resource.MoveableResource;
import io.milton.resource.ReplaceableResource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * A single document exposed as a WebDAV file. Reads stream from
 * {@link DocumentService#loadContent}; writes go through
 * {@code replaceContent} / {@code trash} / {@code update} so the document
 * store's optimistic-locking, soft-lock ({@code lockedFor}) and lifecycle
 * rules all apply. See {@code planning/webdav-support.md}.
 */
class DavFileResource extends AbstractDavResource
        implements GetableResource, ReplaceableResource, DeletableResource,
        MoveableResource, CopyableResource {

    private final DocumentDocument doc;

    DavFileResource(DocumentResourceFactory factory, WebDavPaths.Coords coords, DocumentDocument doc) {
        super(factory, coords);
        this.doc = doc;
    }

    @Override
    boolean isCollection() {
        return false;
    }

    @Override
    public String getName() {
        return leafName(coords());
    }

    @Override
    public @Nullable String getUniqueId() {
        // Feeds milton's ETag generator. storageId changes on every content
        // write, so the ETag flips exactly when the bytes change.
        return doc.getStorageId() != null ? doc.getStorageId() : doc.getId();
    }

    @Override
    public @Nullable Date getModifiedDate() {
        return doc.getCreatedAt() == null ? null : Date.from(doc.getCreatedAt());
    }

    @Override
    public @Nullable Date getCreateDate() {
        return doc.getCreatedAt() == null ? null : Date.from(doc.getCreatedAt());
    }

    @Override
    public void sendContent(OutputStream out, @Nullable Range range,
            Map<String, String> params, @Nullable String contentType)
            throws IOException, NotAuthorizedException, BadRequestException {
        try (InputStream in = factory.documentService().loadContent(doc)) {
            if (range != null) {
                RangeUtils.writeRange(in, range, out);
            } else {
                in.transferTo(out);
            }
            out.flush();
        }
    }

    @Override
    public @Nullable Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        String mime = doc.getMimeType();
        return mime == null || mime.isBlank() ? "application/octet-stream" : mime;
    }

    @Override
    public @Nullable Long getContentLength() {
        return doc.getSize() > 0 ? doc.getSize() : null;
    }

    @Override
    public void replaceContent(InputStream in, @Nullable Long length)
            throws BadRequestException, ConflictException, NotAuthorizedException {
        try {
            // MIME stays derived from the (unchanged) extension — pass null to
            // leave it untouched. See planning/webdav-support.md §8.5.
            factory.documentService().replaceContent(doc.getId(), in, null,
                    factory.currentWriter(), factory.currentActor());
        } catch (DocumentService.DocumentLockedException e) {
            throw new ConflictException(this, e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(this, "Concurrent modification");
        } catch (RuntimeException e) {
            throw new BadRequestException(this, e.getMessage());
        }
    }

    @Override
    public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
        try {
            factory.documentService().trash(doc.getId(), factory.currentWriter(), factory.currentActor());
        } catch (DocumentService.DocumentLockedException e) {
            throw new ConflictException(this, e.getMessage());
        }
    }

    @Override
    public void moveTo(CollectionResource dest, String name)
            throws ConflictException, NotAuthorizedException, BadRequestException {
        WebDavPaths.Coords destCoords = requireSameProject(dest);
        String newPath = childPath(destCoords.path(), name);
        try {
            factory.documentService().update(
                    doc.getId(), null, null, null, newPath,
                    null, null, null, null, factory.currentWriter(), factory.currentActor());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ConflictException(this, e.getMessage());
        } catch (DocumentService.DocumentLockedException e) {
            throw new ConflictException(this, e.getMessage());
        }
    }

    @Override
    public void copyTo(CollectionResource dest, String name)
            throws NotAuthorizedException, BadRequestException, ConflictException {
        WebDavPaths.Coords destCoords = requireSameProject(dest);
        String newPath = childPath(destCoords.path(), name);
        byte[] bytes;
        try (InputStream in = factory.documentService().loadContent(doc)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new BadRequestException(this, "Failed to read source content");
        }
        try {
            // create(...) mints a fresh lineageId — a copy is a genuinely new
            // document, not a version of the source. See §8.4.
            factory.documentService().create(
                    coords().tenantId(), destCoords.project(), newPath,
                    doc.getTitle(), doc.getTags(), doc.getMimeType(),
                    new ByteArrayInputStream(bytes), factory.currentUser(), factory.currentActor());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ConflictException(this, e.getMessage());
        }
    }

    private WebDavPaths.Coords requireSameProject(CollectionResource dest) throws ConflictException {
        if (!(dest instanceof AbstractDavResource davDest)
                || !coords().tenantId().equals(davDest.coords().tenantId())
                || !java.util.Objects.equals(coords().project(), davDest.coords().project())) {
            // Cross-project move/copy isn't representable through the
            // single-project update path in v1.
            throw new ConflictException(this, "Cross-project move/copy is not supported");
        }
        return davDest.coords();
    }

    private static String childPath(String folderPath, String name) {
        return folderPath.isEmpty() ? name : folderPath + "/" + name;
    }
}

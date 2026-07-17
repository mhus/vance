package de.mhus.vance.brain.webdav;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.DeletableResource;
import io.milton.resource.GetableResource;
import io.milton.resource.ReplaceableResource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A macOS/Windows client-noise sidecar ({@code .DS_Store}, {@code ._*}, …)
 * served from the opaque Redis {@link SidecarStore} — never persisted as a
 * document. Hidden from directory listings; only reachable by direct name.
 * See {@code planning/webdav-support.md} §6.
 */
class DavSidecarResource extends AbstractDavResource
        implements GetableResource, ReplaceableResource, DeletableResource {

    private final byte[] data;

    DavSidecarResource(DocumentResourceFactory factory, WebDavPaths.Coords coords, byte[] data) {
        super(factory, coords);
        this.data = data;
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
    public void sendContent(OutputStream out, @Nullable Range range,
            Map<String, String> params, @Nullable String contentType) throws IOException {
        out.write(data);
        out.flush();
    }

    @Override
    public @Nullable Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "application/octet-stream";
    }

    @Override
    public Long getContentLength() {
        return (long) data.length;
    }

    @Override
    public void replaceContent(InputStream in, @Nullable Long length)
            throws BadRequestException, ConflictException, NotAuthorizedException {
        try {
            byte[] bytes = in.readAllBytes();
            factory.sidecarStore().put(
                    coords().tenantId(), requireProject(), coords().path(), bytes);
        } catch (IOException e) {
            throw new BadRequestException(this, "Failed to read sidecar content");
        }
    }

    @Override
    public void delete() {
        factory.sidecarStore().delete(coords().tenantId(), requireProject(), coords().path());
    }

    private String requireProject() {
        String project = coords().project();
        if (project == null) {
            throw new IllegalStateException("Sidecar resource has no project: " + coords());
        }
        return project;
    }
}

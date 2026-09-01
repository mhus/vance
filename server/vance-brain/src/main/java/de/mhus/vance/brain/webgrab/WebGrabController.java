package de.mhus.vance.brain.webgrab;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /brain/{tenant}/grab} — save the page a browser is showing.
 *
 * <p><b>Its own route rather than {@code /documents/upload}</b>, for the same
 * two reasons {@code /capture} beat {@code /entry} in the links app:
 *
 * <ul>
 *   <li><b>A narrow route makes a narrow profile.</b> The generic upload
 *       endpoint would hand a browser extension "create any document at any
 *       path in this project". This one decides the folder and the name
 *       itself.</li>
 *   <li><b>The conversion has to live somewhere.</b> Through the generic
 *       endpoint the extension would have to turn HTML into Markdown itself —
 *       putting the format decision in the least maintainable place, to be
 *       reimplemented by every later client (Safari, a shell alias).</li>
 * </ul>
 *
 * <p>Multipart rather than JSON: a grabbed PDF is bytes, and base64 in a JSON
 * body would inflate it by a third for no gain.
 *
 * <p>Authorisation is the ordinary one, and that is the point — nothing new was
 * needed for the token to work here. {@code Resource.Document CREATE} is
 * checked against the target project, and a project-pinned integration token is
 * already confined to its project by {@code PermissionService}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WebGrabController {

    /**
     * Ceiling on one grab. Generous for an article or a paper, small enough
     * that a mis-pointed extension cannot fill a project.
     */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private final WebGrabService grabService;
    private final RequestAuthority authority;

    @PostMapping(value = "/brain/{tenant}/grab", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GrabResultView grab(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String url,
            @RequestParam(required = false) @Nullable String folder,
            @RequestParam(required = false) @Nullable String title,
            @RequestPart("content") MultipartFile content,
            HttpServletRequest request) {

        String targetFolder = WebGrabService.normaliseFolder(folder);
        // Checked against the folder rather than the final path: the name is
        // derived after the content is read, and an authorisation decision must
        // not depend on work done for a caller who may not be allowed to ask.
        authority.enforce(request,
                new Resource.Document(tenant, projectId, targetFolder), Action.CREATE);

        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to grab.");
        }
        if (content.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Grab is larger than " + (MAX_BYTES / 1024 / 1024) + " MB.");
        }
        if (url.isBlank()) {
            // The source is not decoration: it resolves every relative link in
            // the page and is the only record of where the document came from.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required.");
        }

        byte[] bytes;
        try {
            bytes = content.getBytes();
        } catch (IOException e) {
            log.warn("Grab upload failed tenant='{}' project='{}' url='{}'",
                    tenant, projectId, url, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the upload.");
        }

        String mimeType = content.getContentType();
        WebGrabService.Grabbed grabbed;
        try {
            grabbed = grabService.grab(tenant, projectId, targetFolder, url, mimeType, bytes,
                    title, AccessFilterBase.usernameOrNull(request),
                    WriteActor.user(authority.contextOf(request)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return new GrabResultView(grabbed.path(), grabbed.title(), grabbed.mimeType(),
                grabbed.converted(), bytes.length);
    }

    /**
     * What a grab produced.
     *
     * <p>{@code converted} rather than leaving the caller to infer it from the
     * mime type: "we turned your page into Markdown" and "we stored your PDF"
     * are different outcomes, and a popup that says which one happened is the
     * difference between trust and a shrug.
     *
     * <p>No {@code @GenerateTypeScript} — like the links capture shapes, this
     * serves an external HTTP client, not the Vue app.
     */
    public record GrabResultView(
            String path,
            String title,
            @Nullable String mimeType,
            boolean converted,
            int sourceBytes) {}
}

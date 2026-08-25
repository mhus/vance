package de.mhus.vance.brain.sheet;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetComputed;
import de.mhus.vance.shared.document.kind.SheetDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST surface for {@code kind: sheet} server-side evaluation under
 * {@code /brain/{tenant}/sheet/...}. {@code calc} recomputes + persists
 * the {@code $computed} overlay (server-authoritative, finance-style);
 * {@code snapshot} recomputes without persisting (for embed reads).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SheetController {

    private final DocumentService documentService;
    private final SheetEvalService evalService;
    private final SheetXlsxService xlsxService;
    private final RequestAuthority authority;
    private final SecurityContextFactory contextFactory;

    @PostMapping("/brain/{tenant}/sheet/calc")
    public SheetComputed calc(@PathVariable String tenant,
                              @RequestParam String projectId,
                              @RequestParam String path,
                              HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireSheet(tenant, projectId, path);
        SheetDocument sheet = SheetCodec.parse(readBody(doc), doc.getMimeType());
        SheetComputed computed = evalService.evaluate(sheet);
        String body = SheetCodec.serialize(sheet, computed, doc.getMimeType());
        documentService.update(doc.getId(), null, null, body, null,
                null, null, null, null,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(tenant, currentUser(request), doc.getPath()));
        log.info("SheetController.calc tenant='{}' path='{}' values={}",
                tenant, path, computed.values().size());
        return computed;
    }

    @GetMapping("/brain/{tenant}/sheet/snapshot")
    public SheetComputed snapshot(@PathVariable String tenant,
                                  @RequestParam String projectId,
                                  @RequestParam String path,
                                  HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        DocumentDocument doc = requireSheet(tenant, projectId, path);
        SheetDocument sheet = SheetCodec.parse(readBody(doc), doc.getMimeType());
        return evalService.evaluate(sheet);
    }

    @GetMapping("/brain/{tenant}/sheet/export")
    public ResponseEntity<byte[]> export(@PathVariable String tenant,
                                         @RequestParam String projectId,
                                         @RequestParam String path,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        DocumentDocument doc = requireSheet(tenant, projectId, path);
        SheetDocument sheet = SheetCodec.parse(readBody(doc), doc.getMimeType());
        byte[] body;
        MediaType contentType;
        String ext;
        if ("csv".equalsIgnoreCase(format)) {
            body = xlsxService.exportCsv(sheet).getBytes(StandardCharsets.UTF_8);
            contentType = MediaType.parseMediaType("text/csv");
            ext = "csv";
        } else {
            body = xlsxService.exportXlsx(sheet);
            contentType = MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            ext = "xlsx";
        }
        String filename = leafName(doc.getPath()) + "." + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(body);
    }

    @PostMapping("/brain/{tenant}/sheet/import")
    public Map<String, Object> importSheet(@PathVariable String tenant,
                                           @RequestParam String projectId,
                                           @RequestParam String path,
                                           @RequestParam(defaultValue = "xlsx") String format,
                                           @RequestParam("file") MultipartFile file,
                                           HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireSheet(tenant, projectId, path);
        SheetDocument sheet;
        try {
            byte[] bytes = file.getBytes();
            sheet = "csv".equalsIgnoreCase(format)
                    ? xlsxService.importCsv(new String(bytes, StandardCharsets.UTF_8))
                    : xlsxService.importXlsx(bytes);
        } catch (IOException e) {
            throw new ToolException("Could not read upload: " + e.getMessage());
        }
        String body = SheetCodec.serialize(sheet, doc.getMimeType());
        documentService.update(doc.getId(), null, null, body, null,
                null, null, null, null,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(tenant, currentUser(request), doc.getPath()));
        log.info("SheetController.import tenant='{}' path='{}' format='{}' cells={}",
                tenant, path, format, sheet.cells().size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", doc.getPath());
        out.put("cellCount", sheet.cells().size());
        out.put("rows", sheet.rows());
        out.put("columns", sheet.schema().size());
        out.put("body", body);
        out.put("mimeType", doc.getMimeType());
        return out;
    }

    private static String leafName(String path) {
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    private DocumentDocument requireSheet(String tenant, String projectId, String path) {
        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No document at '" + path + "'."));
        if (!"sheet".equals(doc.getKind())) {
            throw new ToolException("Document '" + path + "' is not a sheet (kind="
                    + doc.getKind() + ").");
        }
        return doc;
    }

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read sheet '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        // One spelling for "who is doing this". Reading the attribute by
        // hand is what put the wrong name here: nothing ever set
        // "vanceUserId", so every actor recorded from this request was null.
        return AccessFilterBase.usernameOrNull(req);
    }
}

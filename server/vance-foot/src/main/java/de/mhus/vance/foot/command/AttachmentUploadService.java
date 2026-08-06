package de.mhus.vance.foot.command;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Uploads staged files as project documents and hands back the
 * {@link AttachmentRef}s the chat turn rides on.
 *
 * <p>Same contract as the web composer's {@code useChatboxUpload}: one
 * document per file under {@link #CHATBOX_FOLDER}, filename prefixed
 * with a random segment so two screenshots of the same page don't
 * overwrite each other, and the document id returned as the ref. Both
 * clients writing to the same folder is deliberate — a file dropped in
 * the browser and one attached in the CLI should end up equally
 * findable in the documents editor.
 *
 * <p>All-or-nothing: a failed upload aborts the send rather than
 * quietly delivering a partial set, because "I attached three files" and
 * "the model saw two" is the kind of mismatch nobody notices until the
 * answer is wrong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentUploadService {

    /** Folder convention shared with the web composer. */
    public static final String CHATBOX_FOLDER = "_chatbox";

    private final ObjectProvider<BrainRestClientService> restProvider;
    private final FootConfig config;

    /**
     * Uploads every file in {@code files} into {@code projectId}.
     *
     * @throws IllegalStateException when no REST client is available or
     *         an upload fails — the caller aborts the send and reports
     */
    public List<AttachmentRef> upload(List<Path> files, String projectId) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException(
                    "no project bound to this session — cannot upload attachments");
        }
        BrainRestClientService rest = restProvider.getIfAvailable();
        if (rest == null) {
            throw new IllegalStateException("no connection to the brain");
        }
        String path = "/brain/" + config.getAuth().getTenant()
                + "/documents/upload?projectId=" + urlEncode(projectId);
        List<AttachmentRef> refs = new ArrayList<>(files.size());
        for (Path file : files) {
            refs.add(uploadOne(rest, path, file));
        }
        return refs;
    }

    private AttachmentRef uploadOne(BrainRestClientService rest, String url, Path file) {
        String target = CHATBOX_FOLDER + "/" + randomPrefix() + "_" + sanitise(fileName(file));
        DocumentDto doc;
        try {
            doc = rest.postMultipartFile(url, file, "file", DocumentDto.class, "path", target);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "upload failed for " + fileName(file) + ": " + e.getMessage(), e);
        }
        if (doc == null || doc.getId() == null || doc.getId().isBlank()) {
            throw new IllegalStateException(
                    "upload of " + fileName(file) + " returned no document id");
        }
        log.debug("attachment uploaded: {} → {} (id={})", file, doc.getPath(), doc.getId());
        return new AttachmentRef(doc.getId());
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "upload" : name.toString();
    }

    /** Eight hex chars — enough to keep same-named uploads apart. */
    private static String randomPrefix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Drops what the brain's path normaliser would reject or collapse.
     * Mirrors the web composer's {@code sanitiseFilename}.
     */
    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isBlank() ? "upload" : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

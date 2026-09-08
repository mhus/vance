package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.age.AgeCipher;
import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code doc_encrypt} — encrypt plaintext with age and store it as a NEW
 * armored {@code .age} document. Either encrypts an existing plaintext
 * document ({@code fromPath}) or the given {@code content} directly; the
 * target defaults to {@code <fromPath>.age} and a missing {@code .age}
 * suffix is appended. The source is never touched and an existing target is
 * never overwritten — the agent cannot read what it would destroy.
 *
 * <p>Recipients are raw age public keys ({@code age1…}) — v1 has no
 * server-side key directory, so the user shares their public key in the
 * conversation (public keys are safe to paste anywhere). Passphrases are
 * deliberately NOT supported here: a passphrase in a tool parameter lands
 * in the conversation history and session memory, which would break the
 * one invariant the whole feature rests on — the private secret never
 * leaves the user's hands. Passphrase encryption is a web-UI concern.
 */
@Component
@RequiredArgsConstructor
public class DocEncryptTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProperties(),
            "required", List.of("recipients"));

    private static Map<String, Object> buildProperties() {
        // No documentSelectorProperties here — the tool takes `fromPath` for
        // its source, not the generic `path`/`id` selector, and advertising
        // those would invite ignored parameters.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("projectId", Map.of(
                "type", "string",
                "description", "Optional project name. Defaults "
                        + "to the active project."));
        p.put("fromPath", Map.of("type", "string",
                "description", "Path of an existing PLAINTEXT document to encrypt. "
                        + "Mutually exclusive with 'content'. The source stays untouched."));
        p.put("content", Map.of("type", "string",
                "description", "Plaintext to encrypt directly (when not copying from a "
                        + "document). Mutually exclusive with 'fromPath'."));
        p.put("toPath", Map.of("type", "string",
                "description", "Target path for the NEW encrypted document. Default "
                        + "(fromPath mode): '<fromPath>.age'. A missing '.age' suffix is "
                        + "appended. Required when encrypting 'content' directly."));
        p.put("recipients", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "age public keys ('age1...') that can decrypt the document. "
                        + "At least one required. Public keys are safe to share — ask the "
                        + "user for theirs if unknown."));
        return Map.copyOf(p);
    }

    private final KindToolSupport support;
    private final DocumentLinkBuilder linkBuilder;

    @Override
    public String name() {
        return "doc_encrypt";
    }

    @Override
    public String description() {
        return "Encrypt plaintext with age and store it as a NEW armored .age "
                + "document — either an existing plaintext document (fromPath) or the "
                + "given content directly. Every listed recipient (age public key, "
                + "'age1...') will be able to decrypt; only they can — the server never "
                + "sees plaintext at rest. The source stays untouched, an existing "
                + "target is never overwritten. Encrypted documents cannot be read or "
                + "edited by tools; the user unlocks them in the web UI. See "
                + "manual_read('age-encryption').";
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "No recipient at hand = ask the user for their age public key "
                + "(age1... — safe to share); source already encrypted = the user must "
                + "decrypt in the web UI first; target exists = pick another toPath, "
                + "never overwrite what you cannot read.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Encrypt documents with age (rare)";
    }

    @Override
    public Set<String> labels() {
        return Set.of("write", "document", "eddie");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("knowledge", "documents");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String fromPath = KindToolSupport.paramString(params, "fromPath");
        String content = KindToolSupport.paramRawString(params, "content");
        if (fromPath == null && content == null) {
            throw new ToolException("Provide either 'fromPath' (an existing plaintext "
                    + "document) or 'content' (plaintext to encrypt)");
        }
        if (fromPath != null && content != null) {
            throw new ToolException("'fromPath' and 'content' are mutually exclusive");
        }

        List<String> recipients = parseRecipients(params);
        String toPath = resolveTargetPath(fromPath, params);

        // Load and read the source through the buffer-aware support path so
        // in-flight edits of the same process are included, not the stale
        // disk copy.
        String plaintext;
        if (fromPath != null) {
            DocumentDocument source = support.loadDocument(Map.of("path", fromPath), ctx);
            if (AgeDocumentKind.isAgeEncrypted(source.getKind(), source.getMimeType())) {
                throw new ToolException("Document '" + fromPath + "' is age-encrypted "
                        + "already — re-encrypting needs the private key, which only the "
                        + "user holds. Ask the user to decrypt it in the web UI "
                        + "(Actions -> Decrypt) first.");
            }
            plaintext = support.readBody(source, ctx);
        } else {
            plaintext = content;
        }

        // Fail fast: the write gate runs before any crypto work — a
        // refused send should cost neither the scrypt/X25519 cycles nor
        // the latency, and permission is not something to earn by doing
        // work first.
        var project = support.eddieContext().resolveProject(params, ctx, false);
        support.enforceDocWrite(ctx, project.getName(), toPath,
                de.mhus.vance.shared.permission.Action.CREATE);

        String armored = AgeCipher.encryptArmored(plaintext, recipients);

        DocumentDocument created;
        try {
            created = support.documentService().create(
                    ctx.tenantId(),
                    project.getName(),
                    toPath,
                    /*title*/ null,
                    /*tags*/ null,
                    AgeDocumentKind.MIME_TYPE,
                    new ByteArrayInputStream(armored.getBytes(StandardCharsets.UTF_8)),
                    ctx.userId(),
                    support.writeActor(ctx, toPath));
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException("Target '" + toPath + "' already exists — pick another "
                    + "toPath; an encrypted document cannot be reviewed before overwriting "
                    + "it, so doc_encrypt never overwrites.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("kind", created.getKind());
        if (created.getMimeType() != null) {
            out.put("mimeType", created.getMimeType());
        }
        if (fromPath != null) {
            out.put("fromPath", fromPath);
        }
        out.put("recipientCount", recipients.size());
        out.put("markdownLink", linkBuilder.linkFor(created, ctx.projectId()));
        return out;
    }

    private static List<String> parseRecipients(Map<String, Object> params) {
        List<String> recipients = params != null && params.get("recipients") instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        if (recipients.isEmpty()) {
            throw new ToolException("'recipients' is required — at least one age public "
                    + "key (age1...). Public keys are safe to share: ask the user for theirs.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String recipient : recipients) {
            String trimmed = recipient.trim();
            if (!AgeKeys.isRecipient(trimmed)) {
                throw new ToolException("Not a well-formed age recipient (expected age1...): "
                        + recipient);
            }
            unique.add(trimmed);
        }
        return new ArrayList<>(unique);
    }

    private static String resolveTargetPath(String fromPath, Map<String, Object> params) {
        String toPath = KindToolSupport.paramString(params, "toPath");
        if (toPath == null || toPath.isBlank()) {
            if (fromPath == null) {
                throw new ToolException("'toPath' is required when encrypting 'content' "
                        + "directly — there is no source path to derive it from.");
            }
            toPath = fromPath;
        }
        // The .age suffix keeps the extension↔mime↔kind triple aligned that
        // upload mapping, webdav mapping and the double-extension inner-type
        // hint all key off — normalize rather than trust the caller's casing.
        if (!toPath.toLowerCase().endsWith("." + AgeDocumentKind.FILE_EXTENSION)) {
            toPath = toPath + "." + AgeDocumentKind.FILE_EXTENSION;
        }
        return toPath;
    }
}

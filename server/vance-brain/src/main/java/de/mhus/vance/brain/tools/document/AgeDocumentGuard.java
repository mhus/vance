package de.mhus.vance.brain.tools.document;

import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;

/**
 * Named refusals for age-encrypted documents across the doc tool family.
 * The server stores ciphertext only, so tools must neither pull it into
 * the LLM context (noise the model would treat as text) nor overwrite it
 * with plaintext (which would destroy the document — the model cannot
 * produce armored ciphertext). See {@code planning/age-encryption.md} §4.
 */
public final class AgeDocumentGuard {
    private AgeDocumentGuard() {
    }

    /**
     * Read-side guard for tools that surface a document's body: refuse
     * with a named message that tells the model what to do instead —
     * ask the user, who is the only side holding the key.
     */
    public static void requireReadable(DocumentDocument doc) {
        if (AgeDocumentKind.isAgeEncrypted(doc.getKind(), doc.getMimeType())) {
            throw new ToolException("Document '" + doc.getPath() + "' is age-encrypted: "
                    + "the server stores ciphertext only and the plaintext exists only "
                    + "in the user's client. Ask the user to open or decrypt it in the "
                    + "web UI (Actions -> Decrypt) — reading the raw ciphertext is "
                    + "not useful.");
        }
    }

    /**
     * Write-side guard for tools that mutate a document's body: any write
     * would replace the ciphertext with plaintext and destroy the
     * document, because the model cannot re-encrypt.
     */
    public static void requireWritable(DocumentDocument doc) {
        if (AgeDocumentKind.isAgeEncrypted(doc.getKind(), doc.getMimeType())) {
            throw new ToolException("Document '" + doc.getPath() + "' is age-encrypted: "
                    + "writing would replace the encrypted ciphertext with plaintext "
                    + "and destroy the document. Age documents are edited in the web "
                    + "UI, where the client re-encrypts on save.");
        }
    }

    /**
     * Creation-side guard for tools that create typed documents: a body
     * the model produced is never armored ciphertext, so an {@code age}
     * document cannot be born from a stub or a generated body. An armored
     * body (real ciphertext the model is moving, e.g. from a workspace
     * file) passes — storing ciphertext is exactly what age documents are
     * for.
     */
    public static void requireCreatable(String kind, String content) {
        if (AgeDocumentKind.KIND.equals(kind) && !AgeDocumentKind.looksArmored(content)) {
            throw new ToolException("kind 'age' documents hold armored ciphertext only. "
                    + "The model cannot produce it — write the content as a normal "
                    + "document instead; the user encrypts via the web UI's Encrypt "
                    + "action or by uploading a .age file.");
        }
    }
}

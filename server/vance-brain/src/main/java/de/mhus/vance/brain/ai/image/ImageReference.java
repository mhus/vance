package de.mhus.vance.brain.ai.image;

/**
 * One materialised reference image for an image-edit call — the
 * provider-agnostic form of "prompt + picture in, picture out".
 *
 * <p>Resolved by the caller (Fenchurch) from a document via the same
 * attachment pipeline the chat uses: scope-checked, MIME-validated,
 * size-capped. Providers receive bytes and a mime type and do no
 * document I/O — the wire encoding (base64 data URL, multipart part,
 * multimodal block) is adapter business.
 *
 * @param documentId source document id, for logs and audit metadata
 * @param mimeType   normalised mime type ({@code image/png}, …)
 * @param data       the decoded image bytes
 */
public record ImageReference(String documentId, String mimeType, byte[] data) {

    public ImageReference {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is blank");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data is empty");
        }
    }

    /** Base64 data-URL form, the encoding OpenRouter's input_references expects. */
    public String toDataUrl() {
        return "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(data);
    }
}

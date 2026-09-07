package de.mhus.vance.shared.document.kind;

import de.mhus.vance.api.documents.AgeDocumentKind;

/**
 * Kind handler for age-encrypted documents ({@code kind: age}).
 *
 * <p>No codec and no validation: the body is armored ciphertext, and
 * everything semantic about it lives behind a key the server does not
 * have. The one capability it carries is {@link #detects} — the armor
 * begin line is an unmistakable marker, so an armored body written
 * without an explicit {@code kind} (e.g. a workspace file copied into
 * documents) is typed as {@code age} instead of collapsing into the
 * {@code text} fallback, where the Web-UI would render raw ciphertext.
 */
public class AgeKindHandler implements KindHandler {

    @Override
    public String getName() {
        return AgeDocumentKind.KIND;
    }

    @Override
    public boolean detects(String content) {
        return AgeDocumentKind.looksArmored(content);
    }

    @Override
    public int detectionPriority() {
        // Unmistakable marker, same reasoning as diagram's mermaid fence —
        // and it must sort ahead of every text-shaped fallback entirely.
        return 5;
    }
}

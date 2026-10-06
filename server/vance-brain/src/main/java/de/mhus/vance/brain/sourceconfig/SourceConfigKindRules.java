package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Findings every source-config kind shares — the rules that are about the
 * {@link SourceConfig} shape itself, not about one subsystem's protocols.
 *
 * <p>Extracted from the research handler when feeds and mounts grew their own
 * kinds: the credential-declaration rule applies to an {@code apiKey} wherever
 * it appears, and the instance-name derivation (filename stem) is the same
 * convention for all three source-config folders.
 */
final class SourceConfigKindRules {

    private SourceConfigKindRules() {
        /* constants only */
    }

    /** Fallback name for messages when the path is unknown. */
    static final String ANONYMOUS = "source";

    /**
     * The credential notation is a <em>declaration</em>, not a mechanism: a
     * bare value already passes through unchanged ({@code SecretResolver}), so
     * the file cannot be read for whether it holds a reference or a raw key.
     * That is worth a hint — never an error, since both forms work.
     *
     * @param code subsystem-specific finding code (e.g.
     *        {@code research-source-credential-undeclared}) — the shared rule,
     *        the subsystem's identity.
     */
    static List<Finding> credentialFindings(String target, String code, @Nullable String apiKey) {
        if (StringUtils.isBlank(apiKey) || SecretResolver.isLiteral(apiKey)) {
            return List.of();
        }
        if (apiKey.contains("{{secret:")) {
            return List.of();
        }
        return List.of(Finding.warning(
                target,
                code,
                "apiKey is neither a {{secret:…}} reference nor a declared {noop} literal — "
                        + "the document does not say whether it holds a credential"));
    }

    /**
     * The instance id the parser reports in its messages: the file stem, the
     * same derivation the setup wizard applies — the filename <em>is</em> the
     * instance id for these documents.
     */
    static String sourceName(String docPath) {
        if (StringUtils.isBlank(docPath)) {
            return ANONYMOUS;
        }
        String stem = StringUtils.substringAfterLast(docPath, "/");
        if (stem.isEmpty()) {
            stem = docPath;
        }
        stem = StringUtils.removeEnd(stem, ".yaml");
        return stem.isBlank() ? ANONYMOUS : stem;
    }
}

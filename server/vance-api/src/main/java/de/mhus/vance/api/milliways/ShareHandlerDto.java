package de.mhus.vance.api.milliways;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One way to share a document, as offered to the user. Milliways lists
 * <em>every</em> handler it knows, available or not — a missing menu entry
 * reads as "does not exist", a greyed-out one with a reason reads as
 * "here is the lever". See {@code planning/milliways-sharing.md} §4.
 *
 * <p>{@link #available} {@code false} is not an error and carries
 * {@link #statusText} explaining what is missing (e.g. no SMTP pack in
 * this project). Submitting to an unavailable handler is rejected at the
 * target, because this list is only a snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("milliways")
public class ShareHandlerDto {

    /**
     * Stable handler id — names the <em>transport</em>, not the medium and
     * not the recipient class ({@code inbox}, {@code smtp}).
     */
    private String id;

    /**
     * Display name as {@code Map<lang, text>} — the same localized shape
     * {@code FormFieldDto.label} uses, so the Web-UI resolves it with the
     * {@code resolveLocalized} helper it already has.
     */
    private Map<String, String> label;

    private boolean available;

    /** Why not available. {@code null} when {@link #available}. */
    private @Nullable String statusText;
}

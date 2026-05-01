package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Optional per-file metadata travelling with a {@link TransferInit}.
 * Receiver applies what it can; unknown / unsafe values are silently
 * dropped (e.g. mode bits outside the configured mask).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferFileAttrs {

    /** POSIX permission bits as octal string ({@code "0644"}, {@code "0755"}, ...). */
    private @Nullable String mode;

    /** Modification time as ISO-8601 instant ({@code "2026-05-01T14:23:11Z"}). */
    private @Nullable String mtime;
}

package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code POST /brain/{tenant}/backup/runs}. The server returns
 * the new token expiry when (and only when) it extended a sliding-lifetime
 * token, so the client can warn the user if the run landed outside the
 * expected window.
 */
@GenerateTypeScript("backup")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupRunsResponse(boolean extended, @Nullable Long tokenExpiresAtMs, long idleDaysCeiling) {}

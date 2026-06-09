package de.mhus.vance.api.fook;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code POST /brain/{tenant}/fook/submit}.
 * Returned immediately — triage happens asynchronously and lands
 * as an inbox item later.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("fook")
public class FookSubmissionResponseDto {

    /** UUID assigned at enqueue time. The UI can show this back to
     *  the user; the inbox item will reference the resulting
     *  ticket id (different uuid). */
    private String submissionId;

    /** Always {@code queued} in v1 — no rate-limit on the
     *  user-direct path (engine-direct goes through the tool which
     *  has its own per-process budget). */
    private String status;
}

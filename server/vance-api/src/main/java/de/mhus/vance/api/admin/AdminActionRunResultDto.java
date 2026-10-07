package de.mhus.vance.api.admin;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire result of one admin action run — one generic shape for every
 * action, so the client renders any action's outcome without knowing
 * it: a one-line {@link #summary} plus per-item rows
 * ({@link AdminActionItemDto}). {@link #ok} is the overall verdict —
 * an action that found problems still responds 200; ok=false is a
 * finding, not a transport error.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("admin")
public class AdminActionRunResultDto {

    /** Id of the action that ran. */
    private String actionId;

    /** Overall verdict — false when any item failed. */
    private boolean ok;

    /** One-line outcome summary (English — detail texts are server-side findings). */
    private String summary;

    /** Per-item rows; empty when the action has no item granularity. */
    private List<AdminActionItemDto> items;

    /** Total wall-clock duration of the run, in milliseconds. */
    private long durationMs;
}

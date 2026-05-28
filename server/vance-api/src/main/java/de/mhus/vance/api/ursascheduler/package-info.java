/**
 * Wire-contract DTOs for the scheduler subsystem — cron-triggered
 * Think-Process spawns configured as YAML documents under
 * {@code _vance/scheduler/<name>.yaml}.
 *
 * <p>See {@code specification/scheduler.md} for the full design.
 * REST endpoints live at
 * {@code /brain/{tenant}/project/{project}/scheduler}; agent tools live
 * under the {@code scheduler} label.
 */
@NullMarked
package de.mhus.vance.api.ursascheduler;

import org.jspecify.annotations.NullMarked;

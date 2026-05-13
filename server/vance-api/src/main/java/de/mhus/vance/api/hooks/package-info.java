/**
 * Wire-contract DTOs for the hooks subsystem — event-driven outbound
 * reactions defined as YAML documents under
 * {@code _vance/hooks/<event>/<name>.yaml}.
 *
 * <p>See {@code specification/hooks.md} for the full design. REST
 * endpoints live at {@code /brain/{tenant}/project/{project}/hooks};
 * agent tools live under the {@code hook} label.
 */
@NullMarked
package de.mhus.vance.api.hooks;

import org.jspecify.annotations.NullMarked;

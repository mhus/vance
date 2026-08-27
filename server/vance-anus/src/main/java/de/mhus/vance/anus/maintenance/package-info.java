/**
 * The maintenance runs: delete or rename a project, delete or rename a user.
 *
 * <p><b>Why here and not in {@code vance-shared}.</b> The handler SPIs and all
 * ~46 handlers stay in shared, next to the entities they speak for — they have
 * to, or the shell could not see them. What lives here is only the two
 * <em>collectors</em>, and they belong to the shell for one reason that is
 * stated in both specs: these operations deliberately have no REST surface and
 * no LLM tool. Their gates are a typed confirmation and a pod drain, both of
 * which only exist at a terminal.
 *
 * <p>As beans in the brain they were unused, and an unused service that deletes
 * a tenant's data is an invitation: the next person who wants "delete project"
 * in the admin UI wires a controller to it and gets the sweep without the
 * gates. Moving the collectors out makes the documented boundary structural.
 *
 * <p>The price, so it is not discovered later: if a brain-side project or user
 * delete is ever wanted, these two classes move back — and
 * {@link de.mhus.vance.anus.maintenance.HubProjectUserDataHandler} with them,
 * because it is the one handler that depends on a collector.
 */
@NullMarked
package de.mhus.vance.anus.maintenance;

import org.jspecify.annotations.NullMarked;

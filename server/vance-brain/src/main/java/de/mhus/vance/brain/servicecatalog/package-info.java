/**
 * The service catalog — a few lines of description per integration (Jira, Slack, …) that turn the list of a
 * pack's tools into something a person can pick from: what the service is, which login its tools need, and which
 * of its tools are the ones people reach for.
 *
 * <p>It is <b>not</b> a second place that lists tools. The tools, their parameters and results come from the
 * packs themselves ({@code ToolSpec}); an entry only describes the service they belong to and points at its
 * best-known operations by name. A service without an entry still lists, just without the description.
 * Entries are documents ({@code _vance/service-catalog/<name>.yaml}) in the usual cascade — bundled, tenant,
 * project — so a tenant describes its own integrations the same way.
 */
@NullMarked
package de.mhus.vance.brain.servicecatalog;

import org.jspecify.annotations.NullMarked;

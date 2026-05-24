/**
 * Wire-contract DTOs for the prompt-wizard subsystem.
 *
 * <p>Wizards are YAML-defined form bundles ({@code wizards/&lt;name&gt;.yaml})
 * under the standard document-cascade ({@code project → _user_&lt;user&gt; →
 * _tenant → classpath:vance-defaults/wizards/}). The Web-UI renders
 * them as a tab in the chat editor; on submit the rendered prompt
 * is injected into the chat input field — wizards are a UI affordance,
 * not a separate spawn path. See {@code specification/wizards.md}
 * for the full spec.
 *
 * <p>Served by {@code de.mhus.vance.brain.wizard.WizardController}
 * under {@code /brain/{tenant}/wizards/...}.
 */
@NullMarked
package de.mhus.vance.api.wizard;

import org.jspecify.annotations.NullMarked;

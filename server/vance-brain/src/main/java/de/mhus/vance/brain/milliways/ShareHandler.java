package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;

/**
 * One way to show a document to a human. A handler declares whether it can
 * be used here, <em>what it needs to know</em>, and how to deliver.
 *
 * <p>Four methods, not one. Without {@link #form} the Web-UI would have to
 * hard-code an input mask per handler, making every new handler a UI
 * release — the same reason the {@code ode} search contract lets the source
 * declare its capabilities instead of the dispatcher guessing them.
 *
 * <p>{@link #id()} names the <em>transport</em>, not the medium and not the
 * recipient class: {@code smtp} rather than {@code mail}, {@code inbox}
 * rather than {@code user}. A second way to reach the same audience (a
 * provider-API mailer, a chat DM) has its own configuration, failures and
 * availability, so it becomes a second handler rather than an {@code if}
 * inside the first.
 *
 * <p>Implementations are Spring beans; {@link MilliwaysService} collects
 * them. An addon can contribute one without brain knowing about it.
 *
 * <p><b>Authorization.</b> The service enforces {@code Document READ} for
 * the sharer before any method here is called. Anything beyond that is the
 * handler's own job — delivering to a user, leaving the tenant — and is
 * enforced by the handler, not by the façade.
 *
 * <p>See {@code planning/milliways-sharing.md}.
 */
public interface ShareHandler {

    /** Stable id — goes into the REST path, the audit entry and the metric tag. */
    String id();

    /** Display name as {@code Map<lang, text>}, resolved by the client. */
    Map<String, String> label();

    /**
     * Can this handler be used for this document, by this user, right now?
     * A negative answer carries its reason and is not an error.
     */
    ShareAvailability availability(ShareScope scope);

    /**
     * The fields the user must fill in. Dynamic option lists must arrive
     * with {@code choices} already populated — {@code choicesFrom} is bound
     * to setting-form resolution and is not available here.
     *
     * <p>Only called for an available handler.
     */
    List<FormFieldDto> form(ShareScope scope);

    /**
     * Deliver. Called only for an available handler, with the document
     * already resolved and read-checked.
     *
     * @throws ShareException when the submission is unusable — the message
     *                        is shown to the user
     */
    ShareResult share(ShareRequest request);
}

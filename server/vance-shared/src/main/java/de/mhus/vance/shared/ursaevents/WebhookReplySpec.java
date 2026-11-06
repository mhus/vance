package de.mhus.vance.shared.ursaevents;

import org.jspecify.annotations.Nullable;

/**
 * {@code reply:} of an event: what the caller is answered with instead of the standard
 * {@code {event, workflowName, workflowRunId}} body. The action still starts as usual; only the answer is
 * replaced, so a provider that wants a fixed acknowledgement (Slack: an empty 200 within three seconds)
 * gets exactly that.
 *
 * @param status      HTTP status, 200 to 299
 * @param contentType media type of the body; {@code application/json} for a structured body, else
 *                    {@code text/plain}
 * @param body        text, or a map/list sent as JSON; {@code null} for no body
 */
public record WebhookReplySpec(
        int status, String contentType, @Nullable Object body) {}

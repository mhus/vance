package de.mhus.vance.brain.servicecatalog;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One service of the catalog, parsed from {@code _vance/service-catalog/<name>.yaml}:
 *
 * <pre>
 * title: { en: "Jira", de: "Jira" }
 * description: { en: "…", de: "…" }
 * icon: ticket
 * match: [jira]                     # pack names this entry describes — "jira" and "jira_rest" both match
 * type: imap_mailbox                # or: the kind of server tool a pack is made from, whatever its name
 * login:
 *   provider: atlassian             # the OAuth provider whose login of the calling user the tools need
 *   manual: oauth-tools             # the manual that says how to connect it
 * highlights:
 *   - tool: createIssue             # an operation of the pack, by the part after the pack's prefix
 *     description: { en: "…" }
 *     params: { body: { fields: { … } } }     # an example for a form to start from
 * triggers:
 *   - name: push                    # what the service can start a workflow with
 *     description: { en: "…" }
 *     mode: webhook                 # webhook (default) | poll
 *     handler: github               # optional SPI name for what cannot be declared
 *     event:                        # webhook: what the generated event document starts from
 *       auth: { signature: { header: X-Hub-Signature-256, algorithm: HmacSHA256, prefix: "sha256=" } }
 *       idempotencyHeader: X-GitHub-Delivery
 *     subscribe:                    # optional pair: calls that register/remove the event URL at the service
 *       tool: github_rest__repos_create_webhook
 *       params: { url: "${url}", secret: "${secret}" }
 *       idFrom: json.id             # where the registration's id is in the result
 *     unsubscribe:
 *       tool: github_rest__repos_delete_webhook
 *       params: { hook_id: "${subscription}" }
 *     inputs:                       # what the person says when switching it on, used as ${input.<name>}
 *       - { name: channel, description: { en: "Channel id" } }
 *     payloadSchema: { … }          # optional, JSON Schema of the delivered payload
 *   - name: new-message
 *     mode: poll
 *     poll:
 *       tool: slack_rest__conversations_history
 *       schedule: "0 0/5 * * * *"
 *       params: { channel: "C0123", oldest: "${cursor}" }
 *       itemsFrom: json.messages    # list in the result (default json)
 *       idFrom: ts                  # id of one entry (default id)
 *       cursorFrom: json.messages.0.ts   # optional
 *       initial: skip               # skip (default) | all
 * </pre>
 *
 * @param name        file name without {@code .yaml}
 * @param title       per language
 * @param description per language
 * @param icon        an icon name of the web UI, or the path of a web UI asset ({@code icons/jira.svg}, served from
 *                    {@code _vance/webui/}, see {@code webui-assets.md}), or {@code null}
 * @param match       pack names (the {@code service} of a tool) the entry describes; may be empty when {@code packType}
 *                    names them instead
 * @param packType    the type of server tool ({@code imap_mailbox}, {@code smtp_sender}) whose packs the entry
 *                    describes, whatever the operator named them; {@code null} for an entry that goes by name only
 * @param provider    OAuth provider id, or {@code null} when the tools need no user login
 * @param manual      manual name, or {@code null}
 * @param highlights  the operations people reach for first
 * @param triggers    what the service can start a workflow with; may be empty
 */
public record ServiceCatalogEntry(
        String name,
        Map<String, String> title,
        Map<String, String> description,
        @Nullable String icon,
        List<String> match,
        @Nullable String packType,
        @Nullable String provider,
        @Nullable String manual,
        List<Highlight> highlights,
        List<Trigger> triggers) {

    /** How a trigger reaches us: the service calls (webhook) or we ask on a schedule (poll). */
    public enum TriggerMode {
        WEBHOOK,
        POLL
    }

    /**
     * A trigger of the service. It is a <em>template</em>: it describes what the generated event document (webhook)
     * or scheduler document (poll) starts from. Nothing of it runs on its own.
     *
     * @param name          unique within the entry
     * @param description   per language
     * @param mode          webhook or poll
     * @param handler       name of an SPI class for what cannot be declared, or {@code null}
     * @param event         webhook: the fields the generated event document starts from, or {@code null}
     * @param poll          poll: the call to ask with and when, or {@code null}
     * @param subscribe     webhook: the call that registers the event URL at the service, or {@code null}
     * @param unsubscribe   webhook: the call that removes the registration again, or {@code null}
     * @param payloadSchema JSON Schema of what arrives, or {@code null}
     * @param inputs        what the person has to say when switching it on; may be empty
     */
    public record Trigger(
            String name,
            Map<String, String> description,
            TriggerMode mode,
            @Nullable String handler,
            @Nullable Map<String, Object> event,
            @Nullable Poll poll,
            @Nullable Registration subscribe,
            @Nullable Registration unsubscribe,
            @Nullable Map<String, Object> payloadSchema,
            List<Input> inputs) {}

    /**
     * Something the person has to say when the trigger is switched on — a channel, a repository. It is available
     * as {@code ${input.<name>}} in the event template, in {@code subscribe}/{@code unsubscribe} and in the poll.
     *
     * @param name        key of the value
     * @param description per language
     * @param required    whether the trigger cannot be made without it (default true)
     */
    public record Input(String name, Map<String, String> description, boolean required) {}

    /**
     * A call of the service that registers or removes the event URL there.
     *
     * <p>In {@code params}, {@code ${url}} is the URL of the event, {@code ${secret}} the secret the event
     * checks, {@code ${event}} its name — and, for the removal, {@code ${subscription}} the id the registration
     * returned.
     *
     * @param tool   an operation of the service's pack, by its full tool name
     * @param params parameters with placeholders
     * @param idFrom dotted path into the result that holds the registration's id ({@code json.id}); registration
     *               only
     */
    public record Registration(
            String tool,
            Map<String, Object> params,
            @Nullable String idFrom) {}

    /**
     * The call a polling trigger asks with, and how often.
     *
     * <p>In {@code params}, {@code ${cursor}} is the cursor the last poll left (empty at the first).
     *
     * @param tool       an operation of the service's pack, by its full tool name
     * @param schedule   Quartz cron with a seconds field, as the scheduler reads it
     * @param params     parameters with placeholders
     * @param itemsFrom  dotted path to the list of entries in the result ({@code json} by default)
     * @param idFrom     dotted path to the id inside one entry ({@code id} by default); an entry whose id was
     *                   seen before is not delivered again
     * @param cursorFrom dotted path to a value in the result that the next poll receives as {@code ${cursor}}, or
     *                   {@code null}
     * @param initial    what the very first poll does with the entries it finds: {@code skip} remembers them
     *                   without delivering (default — a trigger must not replay a service's whole history),
     *                   {@code all} delivers them
     */
    public record Poll(
            String tool,
            String schedule,
            @Nullable Map<String, Object> params,
            String itemsFrom,
            String idFrom,
            @Nullable String cursorFrom,
            String initial) {}

    /** An operation worth pointing at. */
    public record Highlight(
            String tool,
            Map<String, String> description,
            @Nullable Map<String, Object> params) {}

    /**
     * Whether this entry describes the pack {@code service}: its name equals a {@code match} entry, or continues
     * it with an underscore ({@code jira_rest} for {@code jira}). Case-insensitive.
     */
    public boolean describes(String service) {
        String lower = service.toLowerCase(Locale.ROOT);
        for (String m : match) {
            String mm = m.toLowerCase(Locale.ROOT);
            if (lower.equals(mm) || lower.startsWith(mm + "_")) return true;
        }
        return false;
    }

    /**
     * Whether this entry describes a pack with this {@code service} name and these tool {@code labels}: by name
     * ({@link #describes(String)}), or — for an entry with a {@code packType} — because the pack is made from that
     * type of server tool. Such a pack carries the label {@code <type>:<pack name>} on each of its tools, so a
     * mailbox called {@code sales} is found as an {@code imap_mailbox} whatever it is called.
     *
     * @param service the pack's service name, or {@code null} for a tool that belongs to none
     */
    public boolean describes(@Nullable String service, Collection<String> labels) {
        if (service != null && describes(service)) return true;
        if (packType == null) return false;
        String prefix = packType + ":";
        for (String label : labels) {
            if (label.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The text in {@code lang}, else English, else any, else empty. */
    public static String localized(Map<String, String> texts, String lang) {
        if (texts.isEmpty()) return "";
        String exact = texts.get(lang);
        if (exact != null) return exact;
        String english = texts.get("en");
        return english != null ? english : texts.values().iterator().next();
    }
}

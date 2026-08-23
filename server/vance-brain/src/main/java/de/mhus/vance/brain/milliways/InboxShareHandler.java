package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Projects the subject into an inbox item: a <em>pointer</em> to whatever was
 * shared — document reference, link, snippet — plus the sharer's reason. A
 * referenced document's content is deliberately not copied in; the recipient
 * opens the original, or does not, if they may not read it.
 *
 * <p>This is the pull side of Milliways. The item is
 * {@code requiresAction = false} and {@code NORMAL}: it waits to be looked
 * at. No notification channel is involved, no bell, no criticality
 * escalation — that is what the notification subsystem is for.
 *
 * <p>The recipient list is not a user directory: it is exactly the set of
 * users the sharer may actually deliver to, filtered with the same
 * {@code InboxItem WRITE} check that guards the delivery itself. A form can
 * therefore not offer a recipient the share would then refuse.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxShareHandler implements ShareHandler {

    public static final String ID = "inbox";

    static final String FIELD_RECIPIENTS = "recipients";
    static final String FIELD_TEXT = "text";

    private final UserService userService;
    private final MaximegalonService inboxItemService;
    private final PermissionService permissionService;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, String> label() {
        return Map.of("en", "Inbox", "de", "Inbox");
    }

    @Override
    public ShareAvailability availability(ShareScope scope) {
        // A bounded probe, not the list. This runs on every
        // GET /share/handlers — merely opening a menu — and the question here
        // is "is there anybody", which the first hit answers. Building the
        // whole list meant a full user scan plus one authorization check per
        // user before the menu could be drawn, for an answer that is a boolean.
        if (recipients(scope, 1).isEmpty()) {
            return ShareAvailability.unavailable("Nobody else in this tenant to share with");
        }
        return ShareAvailability.ready();
    }

    @Override
    public List<FormFieldDto> form(ShareScope scope) {
        List<FormChoiceDto> choices = new ArrayList<>();
        for (UserDocument user : recipients(scope, Integer.MAX_VALUE)) {
            choices.add(FormChoiceDto.builder()
                    .value(user.getName())
                    .label(Map.of("en", displayName(user)))
                    .build());
        }
        return List.of(
                FormFieldDto.builder()
                        .name(FIELD_RECIPIENTS)
                        .type("multi_select")
                        .label(Map.of("en", "Share with", "de", "Teilen mit"))
                        .required(true)
                        .choices(choices)
                        .build(),
                FormFieldDto.builder()
                        .name(FIELD_TEXT)
                        .type("textarea")
                        .label(Map.of("en", "Why", "de", "Warum"))
                        .help(Map.of(
                                "en", "What should they look at, and why?",
                                "de", "Was sollen sie sich ansehen, und warum?"))
                        .required(true)
                        .rows(4)
                        .build());
    }

    @Override
    public ShareResult share(ShareRequest request) {
        ShareScope scope = request.scope();
        String text = request.string(FIELD_TEXT);
        if (text == null) {
            // The reason is the payload, not decoration: "look at this"
            // without one is noise in someone else's inbox.
            throw new ShareException("Say why you are sharing this");
        }
        List<String> requested = request.strings(FIELD_RECIPIENTS);
        if (requested.isEmpty()) {
            throw new ShareException("Pick at least one recipient");
        }

        Map<String, UserDocument> allowed = new LinkedHashMap<>();
        for (UserDocument user : recipients(scope, Integer.MAX_VALUE)) {
            allowed.put(user.getName(), user);
        }
        List<String> rejected = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (String name : requested) {
            if (!allowed.containsKey(name)) {
                rejected.add(name);
                continue;
            }
            inboxItemService.create(item(scope, name, text));
            delivered.add(name);
        }

        if (delivered.isEmpty()) {
            throw new ShareException(
                    "Cannot share with " + String.join(", ", rejected));
        }
        Map<String, Object> details = ShareResult.newDetails();
        details.put("recipients", List.copyOf(delivered));
        if (!rejected.isEmpty()) {
            details.put("rejected", List.copyOf(rejected));
            log.info("Inbox share skipped {} unreachable recipient(s) tenantId='{}'",
                    rejected.size(), scope.tenantId());
        }
        return new ShareResult(message(delivered, rejected), details);
    }

    // ──────────────────── internals ────────────────────

    private MaximegalonDocument item(ShareScope scope, String recipient, String text) {
        ShareSubject subject = scope.subject();
        Map<String, Object> payload = new LinkedHashMap<>();
        @Nullable DocumentDocument doc = scope.document();
        if (doc != null) {
            // Same payload shape inbox_post writes, so one renderer serves both.
            Map<String, Object> documentRef = new LinkedHashMap<>();
            documentRef.put("documentId", doc.getId());
            documentRef.put("projectId", doc.getProjectId());
            documentRef.put("path", doc.getPath());
            if (doc.getTitle() != null) documentRef.put("title", doc.getTitle());
            if (doc.getMimeType() != null) documentRef.put("mimeType", doc.getMimeType());
            payload.put("documentRef", documentRef);
        }
        if (subject.link() != null) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("url", subject.link());
            link.put("title", scope.displayTitle());
            payload.put("link", link);
        }
        // Its own key, never folded into the body: the body is the sharer's
        // sentence and renders as Markdown, while a snippet is foreign text
        // that must stay a quote.
        if (subject.snippet() != null) payload.put("snippet", subject.snippet());

        return MaximegalonDocument.builder()
                .tenantId(scope.tenantId())
                .originatorUserId(scope.sharer())
                .assignedToUserId(recipient)
                // The discriminator follows the payload. An OUTPUT_DOCUMENT
                // without a document would be a lie in the type.
                .type(doc == null ? MaximegalonType.OUTPUT_TEXT : MaximegalonType.OUTPUT_DOCUMENT)
                .criticality(Criticality.NORMAL)
                .tags(new ArrayList<>(List.of("share")))
                .title(sharerLabel(scope) + " shared: " + scope.displayTitle())
                .body(text)
                .payload(payload)
                .requiresAction(false)
                .build();
    }

    private static String message(List<String> delivered, List<String> rejected) {
        String head = delivered.size() == 1
                ? "Shared with " + delivered.get(0)
                : "Shared with " + delivered.size() + " users";
        if (rejected.isEmpty()) return head;
        return head + " — cannot reach " + String.join(", ", rejected);
    }

    /**
     * Users the sharer may deliver an inbox item to: active humans in the
     * tenant, minus the sharer, minus anyone the permission provider says
     * is off-limits. Service accounts are out because Milliways is a
     * human-to-human act.
     *
     * <p>{@code limit} stops the walk early. The permission check is per user,
     * so a caller that only needs to know whether the list is empty should not
     * pay for all of them — see {@link #availability(ShareScope)}.
     */
    private List<UserDocument> recipients(ShareScope scope, int limit) {
        List<UserDocument> out = new ArrayList<>();
        for (UserDocument user : userService.all(scope.tenantId())) {
            if (out.size() >= limit) break;
            if (user.isServiceAccount()) continue;
            if (user.getStatus() != UserStatus.ACTIVE) continue;
            if (user.getName().equals(scope.sharer())) continue;
            boolean mayDeliver = permissionService.check(
                    scope.ctx(),
                    new Resource.InboxItem(scope.tenantId(), null, user.getName()),
                    Action.WRITE);
            if (mayDeliver) out.add(user);
        }
        return out;
    }

    private String sharerLabel(ShareScope scope) {
        return userService.findByTenantAndName(scope.tenantId(), scope.sharer())
                .map(InboxShareHandler::shortName)
                .orElse(scope.sharer());
    }

    /** Title if set, else the username. For prose (the item title). */
    private static String shortName(UserDocument user) {
        String title = user.getTitle();
        return title == null || title.isBlank() ? user.getName() : title;
    }

    /**
     * Title <em>and</em> username. For the picker, where two colleagues
     * called "Mara" have to be told apart.
     */
    private static String displayName(UserDocument user) {
        String title = user.getTitle();
        if (title == null || title.isBlank()) return user.getName();
        return title + " (" + user.getName() + ")";
    }
}

package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.oauth.OAuthConfigRegistry;
import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.mail.MailMessage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends through the sharer's <b>own</b> Gmail account, over the Gmail API.
 *
 * <p>A second handler rather than a mode of {@link SmtpShareHandler},
 * because {@code id()} names the transport: this one has a different
 * configuration (a tenant OAuth app instead of a relay pack), a different
 * availability question (has <em>this user</em> connected their account),
 * different failures (a revoked consent, a missing scope) and a different
 * identity — the mail comes from the sharer and lands in their "Sent",
 * where the SMTP handler sends from a project relay address. An {@code if}
 * inside the SMTP handler would have had to fork on every one of those.
 *
 * <p><b>No second config surface.</b> The account is the existing OAuth
 * connection ({@code google} by default, configurable because a tenant may
 * name its provider differently): the tenant admin registers the app under
 * "OAuth Providers", the user connects once under "Connected Accounts", and
 * {@link OAuthTokenRefresher} keeps the token fresh. Nothing here reads or
 * writes a token itself.
 *
 * <p><b>The scope check is deliberate.</b> Google hands out exactly the
 * scopes that were consented to, and a Google connection made for Drive or
 * Calendar carries no permission to send mail. Without the check the handler
 * would look ready, take the sharer through the whole form, and then fail
 * with Google's 403 — so it is answered where a "not available" answer
 * belongs, with the sentence that says what to do about it.
 */
@Component
@Slf4j
public class GmailShareHandler implements ShareHandler {

    public static final String ID = "gmail";

    /**
     * Gmail's documented attachment limit. Exceeding it produces a 4xx from
     * the API, so the cap here only buys a named refusal before the upload —
     * which for a 25 MB document is worth having.
     */
    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private static final String USER_KEY_PREFIX = "oauth.";
    private static final String KEY_ACCESS_TOKEN = ".access_token";
    private static final String KEY_SCOPES = ".scopes";

    /**
     * Any one of these lets {@code users.messages.send} through. Listed
     * rather than reduced to a prefix match: {@code gmail.readonly} also
     * starts with the same prefix and does <em>not</em> permit sending.
     */
    private static final Set<String> SENDING_SCOPES = Set.of(
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/gmail.modify",
            "https://mail.google.com/");

    private final OAuthConfigRegistry configRegistry;
    private final OAuthTokenRefresher tokenRefresher;
    private final SettingService settingService;
    private final DocumentService documentService;
    private final GmailApiClient gmailApiClient;
    private final String providerId;
    private final long maxAttachmentBytes;

    public GmailShareHandler(
            OAuthConfigRegistry configRegistry,
            OAuthTokenRefresher tokenRefresher,
            SettingService settingService,
            DocumentService documentService,
            GmailApiClient gmailApiClient,
            @Value("${vance.milliways.gmail.provider-id:google}") String providerId,
            @Value("${vance.milliways.gmail.max-attachment-bytes:26214400}")
            long maxAttachmentBytes) {
        this.configRegistry = configRegistry;
        this.tokenRefresher = tokenRefresher;
        this.settingService = settingService;
        this.documentService = documentService;
        this.gmailApiClient = gmailApiClient;
        this.providerId = providerId == null || providerId.isBlank() ? "google" : providerId.trim();
        this.maxAttachmentBytes = maxAttachmentBytes > 0
                ? maxAttachmentBytes
                : DEFAULT_MAX_ATTACHMENT_BYTES;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, String> label() {
        return Map.of("en", "E-Mail (Gmail)", "de", "E-Mail (Gmail)");
    }

    @Override
    public ShareAvailability availability(ShareScope scope) {
        if (configRegistry.resolve(scope.tenantId(), providerId).isEmpty()) {
            // The tenant never registered the Google app. Phrased for the
            // admin who can fix it, not for the sharer who cannot.
            return ShareAvailability.unavailable(
                    "Google is not configured for this tenant — an admin adds it under "
                            + "OAuth Providers ('" + providerId + "')");
        }
        if (!isConnected(scope)) {
            return ShareAvailability.unavailable(
                    "Your Google account is not connected — connect it under Connected Accounts");
        }
        if (!maySend(scope)) {
            return ShareAvailability.unavailable(
                    "Your Google connection has no permission to send mail — "
                            + "reconnect it under Connected Accounts");
        }
        return ShareAvailability.ready();
    }

    @Override
    public List<FormFieldDto> form(ShareScope scope) {
        // No sender question: it is the sharer's own account, and there is
        // nothing to pick. The SMTP handler asks which relay because there
        // the answer changes who the mail appears to come from.
        return List.copyOf(MailShareSupport.mailFields(scope));
    }

    @Override
    public ShareResult share(ShareRequest request) {
        ShareScope scope = request.scope();
        List<String> recipients =
                MailShareSupport.parseRecipients(request.string(MailShareSupport.FIELD_TO));
        if (recipients.isEmpty()) {
            throw new ShareException("Name at least one mail address");
        }
        String subject = request.stringOr(MailShareSupport.FIELD_SUBJECT, scope.displayTitle());
        String body = MailShareSupport.bodyOf(
                scope, request.stringOr(MailShareSupport.FIELD_TEXT, ""));

        List<MailMessage.Attachment> attachments = scope.hasDocument()
                ? List.of(MailShareSupport.attachmentOf(scope, documentService, maxAttachmentBytes))
                : List.of();

        String accessToken;
        try {
            accessToken = tokenRefresher.resolveAccessToken(
                    scope.tenantId(), scope.sharer(), providerId);
        } catch (OAuthExpiredException e) {
            // The connection went away between the availability check and
            // now — revoked in Google's UI, or a refresh Google rejected.
            // Unavailable, not failed: nothing broke, the sharer has to
            // reconnect.
            throw new ShareUnavailableException(
                    "Your Google account has to be reconnected under Connected Accounts — "
                            + e.getMessage());
        }

        Map<String, Object> sendResult;
        try {
            sendResult = gmailApiClient.send(accessToken, new MailMessage(
                    recipients,
                    /*cc*/ null,
                    /*bcc*/ null,
                    subject,
                    body,
                    /*html*/ null,
                    // Left to Google: it fills in the account's own address,
                    // and an invented From is either redundant or rejected
                    // as an unregistered send-as alias.
                    /*from*/ null,
                    /*replyTo*/ null,
                    attachments));
        } catch (GmailApiClient.GmailException e) {
            if (e.authFailure()) {
                throw new ShareUnavailableException(
                        "Google rejected the token — reconnect your account under "
                                + "Connected Accounts (" + e.getMessage() + ")");
            }
            if (e.refusal()) {
                throw new ShareException(e.getMessage(), e);
            }
            throw new ShareTransportException(e.getMessage(), e);
        }

        Map<String, Object> details = ShareResult.newDetails();
        details.put("provider", providerId);
        details.put("recipientDomains", MailShareSupport.domainsOf(recipients));
        details.put("recipientCount", recipients.size());
        if (!attachments.isEmpty()) {
            MailMessage.Attachment attachment = attachments.get(0);
            details.put("attachment", attachment.filename());
            details.put("attachmentBytes", attachment.bytes().length);
        }
        Object messageId = sendResult.get("id");
        if (messageId != null) details.put("messageId", messageId);

        log.info("Milliways gmail share: {} recipient(s) as '{}', subject={}",
                recipients.size(), scope.sharer(), scope.subject().parts());
        String message = recipients.size() == 1
                ? "Sent to " + recipients.get(0)
                : "Sent to " + recipients.size() + " recipients";
        return new ShareResult(message, details);
    }

    // ──────────────────── internals ────────────────────

    /** Same question the Connected-Accounts page asks: is a token stored. */
    private boolean isConnected(ShareScope scope) {
        return settingService.getDecryptedUserPassword(
                scope.tenantId(), scope.sharer(),
                USER_KEY_PREFIX + providerId + KEY_ACCESS_TOKEN) != null;
    }

    /**
     * Whether the granted scopes cover sending.
     *
     * <p>An <em>unknown</em> scope list counts as permitted: the setting is
     * only written when the provider returns a {@code scope} claim, and a
     * connection made before that was recorded would otherwise be reported
     * as broken when it works. Guessing wrong in this direction costs one
     * failed send with Google's own explanation; guessing wrong in the other
     * hides a working account behind a sentence nobody can act on.
     */
    private boolean maySend(ShareScope scope) {
        String granted = settingService.getUserStringValue(
                scope.tenantId(), scope.sharer(),
                USER_KEY_PREFIX + providerId + KEY_SCOPES);
        if (granted == null || granted.isBlank()) return true;
        for (String s : granted.split("[\\s,]+")) {
            if (SENDING_SCOPES.contains(s.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

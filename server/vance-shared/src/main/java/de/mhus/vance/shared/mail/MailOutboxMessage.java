package de.mhus.vance.shared.mail;

/**
 * A claimed outbox entry with its body decrypted — what a sender needs to
 * hand the mail to SMTP. {@code attempts} counts this delivery attempt.
 */
public record MailOutboxMessage(String id, String tenantId, String to, String subject, String body, int attempts) {}

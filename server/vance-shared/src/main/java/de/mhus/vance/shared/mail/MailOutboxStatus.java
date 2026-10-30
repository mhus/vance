package de.mhus.vance.shared.mail;

/** Delivery state of one {@link MailOutboxDocument}. */
public enum MailOutboxStatus {
    /** Waiting for the sender; due once {@code nextAttemptAt} has passed. */
    PENDING,
    /** Claimed by a sender; reclaimable once {@code leaseUntil} has passed. */
    SENDING,
    /** Handed to the SMTP server. */
    SENT,
    /** Gave up after the last retry. */
    FAILED
}

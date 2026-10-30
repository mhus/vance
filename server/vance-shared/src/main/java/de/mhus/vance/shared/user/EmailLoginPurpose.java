package de.mhus.vance.shared.user;

/** What a confirmed e-mail code is good for. */
public enum EmailLoginPurpose {
    /** Signs the user in. */
    LOGIN,
    /** Confirms a new address for the account; the code went to that address. */
    EMAIL_CHANGE
}

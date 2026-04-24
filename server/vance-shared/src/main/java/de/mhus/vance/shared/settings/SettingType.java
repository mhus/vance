package de.mhus.vance.shared.settings;

/**
 * Value type of a {@link SettingDocument}. {@link #PASSWORD} is stored
 * encrypted; all others are plaintext.
 */
public enum SettingType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    PASSWORD
}

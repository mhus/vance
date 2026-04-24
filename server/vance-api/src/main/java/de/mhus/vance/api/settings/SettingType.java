package de.mhus.vance.api.settings;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Value type of a setting. {@link #PASSWORD} values are encrypted at rest and
 * masked on read through the public API; all other types round-trip as plain
 * text.
 */
@GenerateTypeScript("settings")
public enum SettingType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    PASSWORD
}

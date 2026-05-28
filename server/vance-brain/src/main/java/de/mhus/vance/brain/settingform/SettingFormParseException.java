package de.mhus.vance.brain.settingform;

/**
 * Thrown when a Setting Form YAML cannot be parsed — bad structure,
 * missing required fields, invalid Pebble templates, or unknown
 * setting types. The controller maps this to an HTTP 500 for direct
 * loads; listing operations log and skip malformed entries instead
 * of failing the whole call.
 */
public class SettingFormParseException extends RuntimeException {

    public SettingFormParseException(String message) {
        super(message);
    }

    public SettingFormParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

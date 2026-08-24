package de.example.models;

/**
 * Fixture for the record body. A record's constants used to be invisible to the
 * generator, so the same declaration shipped from a class and did not from a
 * record — one annotation, two rules.
 */
@GenerateTypeScript("models")
public record Coords(int x, int y) {

    /** Ships, like it would from a class. */
    public static final String ORIGIN = "0,0";

    /** Does not ship. */
    private static final int SCALE = 2;
}

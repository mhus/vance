package de.example.models;

/**
 * Fixture for the constant rules: a class with one constant that belongs in the
 * contract and one that is an implementation detail.
 */
@GenerateTypeScript("models")
public class Limits {

    /** Ships: a client that has to respect the bound needs to know it. */
    public static final int MAX_ITEMS = 25;

    /** Does not ship, and must not become an interface property either. */
    private static final String INTERNAL_PATTERN = "[a-z]+";

    /** A public constant the author explicitly keeps out. */
    @TypeScript(ignore=true)
    public static final String BUILD_TAG = "internal-only";

    private String name;
}

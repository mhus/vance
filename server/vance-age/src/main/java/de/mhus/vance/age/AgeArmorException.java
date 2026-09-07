package de.mhus.vance.age;

/**
 * The text is not a decodable age ASCII armor. Distinct from a wrong key:
 * the answer for the user is "not an age document", never "import the
 * right key".
 */
public class AgeArmorException extends AgeCryptoException {

    public AgeArmorException(String message, Throwable cause) {
        super(message, cause);
    }
}

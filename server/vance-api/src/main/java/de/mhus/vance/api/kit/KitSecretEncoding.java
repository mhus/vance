package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How the {@code value} of an encrypted setting travels inside a kit.
 *
 * <p>Only meaningful for the encrypted setting types ({@code PASSWORD},
 * {@code HIDDEN}) — a plain {@code STRING} has nothing to encode. Declaring
 * it on any other type is refused at parse time rather than ignored: the
 * author who wrote it meant something by it, and silently dropping the line
 * would hide the misunderstanding.
 *
 * <p>Written in {@code settings/<key>.yaml} as {@code encoding:}. Absent
 * means {@link #VAULT}, so every kit written before this field existed keeps
 * its meaning.
 */
@GenerateTypeScript("kit")
public enum KitSecretEncoding {

    /**
     * {@code value} is a vault blob — {@code AesEncryptionService.encryptWith(
     * plaintext, vaultPassword)} — and the install needs the matching vault
     * password to open it.
     *
     * <p>The default, and the only thing a kit that lives <em>at rest</em>
     * somewhere may ship: a git checkout can be cloned by anyone who reaches
     * the repository, and a library archive is a file on a server. The vault
     * password is what keeps the credential out of those copies, and it is
     * shared out of band precisely because the store cannot be trusted with
     * it.
     */
    VAULT,

    /**
     * {@code value} is the credential itself, and the install encrypts it
     * with the server key on arrival. No vault password is involved.
     *
     * <p>Permitted only from {@link KitSourceType#ODE}, and that restriction
     * is the whole security statement — see {@code KitPlaintextSecretGate}.
     * An Ode bundle is assembled per request and handed to a caller the host
     * authenticated, over TLS: there is no at-rest copy for a vault password
     * to protect, and no out-of-band channel to agree one over. It is the
     * same argument already written down for signatures on
     * {@link KitSourceType#ODE}.
     *
     * <p>Encrypting with a password shipped alongside the blob was the
     * alternative, and it was rejected for being ceremony: the key next to
     * the ciphertext protects nothing, and dressing plaintext up as
     * encryption is worse than admitting what it is.
     */
    PLAIN;

    // The next value, named here so it arrives as an addition rather than a
    // redesign: `SEALED` — the host encrypts to the reader's public key
    // (hybrid: an ephemeral AES key wrapped with it), and the reader unwraps
    // with a private key that never leaves it.
    //
    // What it would buy over PLAIN is real but narrow: the bundle is
    // unpacked into a temporary directory during the install, a
    // TLS-terminating proxy in front of the reader sees the body, and a
    // debug dump of a bundle would carry the credential. SEALED closes all
    // three. It does NOT close an active man-in-the-middle if the public key
    // travels in the build request — whoever can read the request can swap
    // the key, decrypt, re-encrypt and pass it on, which leaves TLS as the
    // only thing holding. That is the same mistake as shipping a vault
    // password beside its blob, and it is why the key belongs in the
    // registration that issues the host token, not in the call.
    //
    // Additive by construction: an unknown encoding is already refused at
    // parse time, `KitPlaintextSecretGate` grows one case, and no kit
    // written today changes meaning.

    /** What an absent {@code encoding:} line means. */
    public static final KitSecretEncoding DEFAULT = VAULT;
}

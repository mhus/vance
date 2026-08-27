package de.mhus.vance.shared.user.maintenance;

/**
 * The name a deleted user's <em>historical</em> references are rewritten to:
 * {@code mhus} becomes {@code _deleted_mhus}.
 *
 * <h2>Why a tombstone rather than leaving it or blanking it</h2>
 *
 * <p>Not so the UI has something to show — that would be a weak reason and a
 * renderer could handle it. The reason is <b>name reuse</b>. A login is the
 * business key and it comes back: humans get their predecessor's username,
 * service accounts follow a scheme. Leave {@code mhus} standing in a year's
 * worth of chat messages, {@code createdBy} fields and feed rows, and the next
 * person to hold that login silently inherits all of it — every one of those
 * rows now reads as being about them. That is a misattribution nobody will ever
 * notice, and the tombstone is what prevents it: the history detaches from the
 * name at the moment the account goes.
 *
 * <p>Blanking to something like {@code _unknown} prevents the same
 * misattribution, and is strictly worse: it also destroys <em>which</em>
 * account it was, which is the only thing the field was there for. There is no
 * case where it wins.
 *
 * <h2>Two honest weaknesses</h2>
 *
 * <ul>
 *   <li><b>Not collision-free across generations.</b> Delete {@code mhus},
 *       create it again, delete it again — both generations collapse into
 *       {@code _deleted_mhus}. That is a merge of two people's history, and it
 *       is accepted: it is still strictly better than merging with a
 *       <em>live</em> third person, and a discriminator (a timestamp) can be
 *       added if it ever bites.</li>
 *   <li><b>It looks like a service account,</b> which is what a leading
 *       {@code _} means elsewhere. Harmless in fact — a tombstone never creates
 *       a {@code UserDocument}, it is only a string in a foreign field — but a
 *       reader that infers "service account" from the prefix will mislabel it.
 *       {@code UserDocument.serviceAccount} is a field; nothing may infer that
 *       from the name.</li>
 * </ul>
 *
 * <p><b>Idempotent:</b> tombstoning an already-tombstoned name is a no-op, so
 * a re-run of an interrupted delete does not produce
 * {@code _deleted__deleted_mhus}.
 */
public final class UserTombstone {

    private UserTombstone() {}

    /** Prefix marking a name that no longer belongs to an account. */
    public static final String PREFIX = "_deleted_";

    /** {@code mhus} → {@code _deleted_mhus}; already-tombstoned names unchanged. */
    public static String of(String userName) {
        return isTombstone(userName) ? userName : PREFIX + userName;
    }

    /** Whether this name is already a tombstone. */
    public static boolean isTombstone(String userName) {
        return userName.startsWith(PREFIX);
    }
}

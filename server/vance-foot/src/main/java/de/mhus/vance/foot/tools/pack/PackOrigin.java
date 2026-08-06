package de.mhus.vance.foot.tools.pack;

/**
 * Which layer a tool-pack definition came from. Drives the trust
 * decision: a {@link #GLOBAL} pack lives in the user's own home
 * directory and is theirs by definition, a {@link #PROJECT} pack is
 * content of the working directory — for a cloned repository that means
 * somebody else authored the command foot would spawn.
 */
public enum PackOrigin {

    /** {@code $VANCE_HOME}/{@code ~/.vancetope/foot-tools/} — the user's own. */
    GLOBAL,

    /** {@code ./.vancetope/foot-tools/} — comes with the working directory. */
    PROJECT;

    public boolean isProject() {
        return this == PROJECT;
    }
}

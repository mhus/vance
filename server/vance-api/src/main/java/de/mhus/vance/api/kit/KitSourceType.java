package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** How a kit source is reached. Selects the loader; see {@code KitSourceDto}. */
@GenerateTypeScript("kit")
public enum KitSourceType {

    /** Git repository over HTTPS. */
    GIT,

    /** Local directory — {@code file://} or an absolute path. Mostly tests and development. */
    FOLDER,

    /**
     * A tenant's kit library — an endpoint that hands out what this
     * tenant is entitled to. Unlike git, it is not something anyone can
     * clone: what it returns depends on who is asking.
     *
     * <p>Named for what the user sees ("your kit library"), not for the
     * shop that sold the entitlement. Buying and delivering are separate
     * concerns, and a library may well hold kits nobody paid for.
     */
    LIBRARY;

    /**
     * Best guess for a url that no configured source claims.
     *
     * <p>Only git and folder are guessable — they are addressed by where
     * they are. A library has to be configured, because reaching it needs
     * more than its url: a public key and a signature policy at minimum.
     */
    public static KitSourceType guessFrom(String url) {
        String u = url.trim();
        return u.startsWith("file:") || u.startsWith("/") ? FOLDER : GIT;
    }
}

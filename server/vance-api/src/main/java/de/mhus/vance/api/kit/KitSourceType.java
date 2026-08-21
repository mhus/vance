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
    LIBRARY,

    /**
     * An application that hosts its own kit — an Ode endpoint that says
     * which kits a project should have and hands them out on request.
     *
     * <p>Fetching is close to {@link #LIBRARY} (http, a zip, a bearer
     * token); what differs sits one level up, in the provisioning
     * mechanism of the same name. The kit may be assembled per request,
     * which is why nothing here assumes two calls return the same bytes.
     *
     * <p>Signatures are meaningless for this type by construction: the
     * host that writes the kit is the host that delivers it, so a
     * signature proves nothing that the token and TLS do not already
     * say. {@link KitSignaturePolicy#defaultFor} therefore leaves it at
     * {@code OFF}, and that is a decision rather than an oversight —
     * see {@code planning/kit-ode-provisioning.md} §5.
     */
    ODE;

    /**
     * Best guess for a url that no configured source claims.
     *
     * <p>Only git and folder are guessable — they are addressed by where
     * they are. A library and an Ode host have to be configured, because
     * reaching them needs more than a url: a token, and for a library a
     * public key and a signature policy. Guessing {@code ODE} would also
     * mean a plain https url could turn into „post to this host and run
     * what comes back", which no heuristic should be allowed to decide.
     */
    public static KitSourceType guessFrom(String url) {
        String u = url.trim();
        return u.startsWith("file:") || u.startsWith("/") ? FOLDER : GIT;
    }
}

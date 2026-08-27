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
    ODE,

    /**
     * Another project of the same tenant that is itself a kit source — one
     * carrying {@code _vance/kits/manifest.yaml}. Addressed as
     * {@code project:<name>}.
     *
     * <p>The only type that lives <b>inside</b> this deployment, and that is
     * what makes it different rather than just cheaper. The other four are
     * reached over a network and authorized with a credential; this one is
     * read straight out of the database, so the question „may this happen"
     * is about a <em>person</em> and their access to the source project. See
     * {@code ProjectKitSourceLoader}.
     *
     * <p>Consequences of being inside, all of them deliberate: no token, no
     * signature (there is no transport and no third party to authenticate —
     * we would be verifying our own database), and credentials travel as
     * {@link KitSecretEncoding#SERVER}, because both ends read the same
     * server key and the tree never comes to rest anywhere.
     */
    PROJECT;

    /** Scheme that addresses a project of the same tenant. */
    public static final String PROJECT_SCHEME = "project:";

    /**
     * Best guess for a url that no configured source claims.
     *
     * <p>Git, folder and project are guessable — they are addressed by where
     * they are, and nothing beyond the url is needed to reach them. A library
     * and an Ode host have to be configured, because reaching them needs more:
     * a token, and for a library a public key and a signature policy. Guessing
     * {@code ODE} would also mean a plain https url could turn into „post to
     * this host and run what comes back", which no heuristic should be allowed
     * to decide.
     *
     * <p>{@code PROJECT} is guessed rather than configured for the same reason
     * git is — and it <b>must</b> be recognised here rather than left to fall
     * through, or {@code project:alpha} would be handed to the git loader as a
     * remote to clone. A distinct scheme is what makes that decidable; a bare
     * project name could not be told apart from a relative folder path.
     */
    public static KitSourceType guessFrom(String url) {
        String u = url.trim();
        if (u.startsWith(PROJECT_SCHEME)) return PROJECT;
        return u.startsWith("file:") || u.startsWith("/") ? FOLDER : GIT;
    }
}

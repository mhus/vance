package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitAccess;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitStoreCredentials;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.store.brain.StoreClient.CatalogueEntry;
import de.mhus.vance.store.brain.StoreClient.Due;
import de.mhus.vance.store.brain.StoreClient.Fees;
import de.mhus.vance.store.brain.StoreClient.Release;
import de.mhus.vance.store.brain.StoreClient.ReleaseRequest;
import de.mhus.vance.store.brain.StoreClient.Vendor;
import de.mhus.vance.store.brain.StoreClient.VendorTerms;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The store addon's surface.
 *
 * <p>Everything a store credential touches happens on this side of the
 * wire. The browser posts an email and a password once and never sees a
 * token in return — what comes back is an account id, which is not a
 * secret and is what the screen needs to say who is signed in.
 *
 * <p>Spec: {@code planning/kit-store.md} §7 Phase S3.
 */
@RestController
@RequestMapping("/brain/{tenant}/addon/store")
@RequiredArgsConstructor
@Slf4j
public class StoreAddonController {

    private final KitSourceRegistry sources;
    private final StoreOverviewService overview;
    private final StoreConnectionService connections;
    private final KitService kitService;
    private final KitRecordStore recordStore;
    private final StoreClient storeClient;
    private final KitStoreCredentials credentials;
    private final RequestAuthority authority;
    private final StoreDeveloperService developerService;


    public record ConnectRequest(
            String sourceId, String email, String password, @Nullable String label) {}

    public record DisconnectRequest(String sourceId) {}

    public record InstallRequest(String sourceId, String path) {}

    public record ReviewRequest(
            String sourceId, String vendor, String kitId,
            int stars, @Nullable String text) {}

    public record BuyRequest(
            String sourceId, String vendor, String kitId,
            String email, String password,
            /** Where the buyer is — the store taxes by it and refuses without it. */
            String billingCountry,
            /** For a business buyer, as given. */
            @Nullable String vatId,
            @Nullable String withdrawalNoticeVersion) {}

    /** The four lists, per configured library. */
    @GetMapping("/{projectId}/overview")
    public List<StoreOverviewService.SourceView> overview(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        return overview.overview(tenant, projectId, actor(request));
    }

    /**
     * Sign in to a store.
     *
     * <p>A POST with a password in the body, which is why it is not a
     * query anywhere. The password is used once against the store and
     * discarded; what is kept is the link token it produced.
     */
    @PostMapping("/{projectId}/connect")
    public StoreConnectionService.Connection connect(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ConnectRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return connections.connect(
                    tenant, actor(request), library(tenant, body.sourceId()),
                    body.email(), body.password(), body.label(), projectId);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Forget the credential here. The link at the store survives. */
    @PostMapping("/{projectId}/disconnect")
    public StoreConnectionService.Connection disconnect(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody DisconnectRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        connections.disconnect(tenant, actor(request), source);
        return new StoreConnectionService.Connection(source.getId(), null);
    }

    /**
     * Install or update one kit from a library.
     *
     * <p>Delegates to the ordinary kit path — the same install a person
     * would run from the scopes screen, with the same policy, the same
     * signature check and the same licence gate. This addon adds a button,
     * not a second way in.
     *
     * <p>{@code UPDATE} when it is already installed, {@code INSTALL}
     * otherwise: the install path refuses to install over an existing
     * record on purpose, and deciding here saves the screen from having to
     * know which verb applies.
     */
    @PostMapping("/{projectId}/install")
    public KitOperationResultDto install(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody InstallRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        // Asked locally. It used to be answered by re-running the whole
        // overview — two HTTP calls to decide a verb — and StoreOverviewService
        // swallows a library failure into an empty list, so a hiccup at the
        // delivery service made an installed kit look uninstalled and turned
        // the request into an INSTALL that the installer then refused. The
        // record is here, it is authoritative, and KitService looks it up the
        // same way one line later.
        boolean installed = recordStore.findByOrigin(
                tenant, projectId, source.getUrl(), body.path()) != null;

        KitImportRequestDto importRequest = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitInheritDto.builder()
                        .url(source.getUrl())
                        .path(body.path())
                        .build())
                .mode(installed ? KitImportMode.UPDATE : KitImportMode.INSTALL)
                .build();
        try {
            return kitService.importKit(
                    tenant, importRequest, actor(request), SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * Buy a kit.
     *
     * <p>Asks for the store password, unlike everything else here — the
     * store accepts a link token for reviewing and for nothing that spends
     * money. The password is used once and discarded, exactly as when
     * signing in, and the session is closed straight afterwards.
     */
    @PostMapping("/{projectId}/buy")
    public StoreClient.Order buy(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody BuyRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            StoreClient.Session session =
                    storeClient.login(source, body.email(), body.password());
            try {
                return storeClient.order(source, session, body.vendor(), body.kitId(),
                        body.withdrawalNoticeVersion(), body.billingCountry(), body.vatId());
            } finally {
                storeClient.logout(source, session);
            }
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * The withdrawal notice a store currently requires.
     *
     * <p>Asked before the buy form is shown, so the version the buyer
     * confirms is the one the store will accept. A stale one is refused
     * rather than silently recorded under newer wording.
     */
    @GetMapping("/{projectId}/withdrawal-notice")
    public StoreClient.WithdrawalNotice withdrawalNotice(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return storeClient.withdrawalNotice(library(tenant, sourceId));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** The reviews of one kit whose text an operator has cleared. */
    @GetMapping("/{projectId}/reviews")
    public List<StoreClient.Review> reviews(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("vendor") String vendor,
            @RequestParam("kitId") String kitId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return storeClient.reviews(library(tenant, sourceId), vendor, kitId);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * Leave or change a review of a kit.
     *
     * <p>Authenticated at the store by this installation's link token,
     * which the browser never sees. Reviewing from the brain where the kit
     * is actually used beats reviewing from a store front nobody has
     * opened — see {@code StoreActorResolver} for why a link is allowed to
     * do this and nothing else.
     */
    @PostMapping("/{projectId}/review")
    public StoreClient.Review review(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ReviewRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        KitAccess access = credentials.resolve(
                tenant, projectId, actor(request), source.getUrl(), null);
        if (access.token() == null || access.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sign in to this store before reviewing");
        }
        try {
            // Which version this opinion is about: the install record knows,
            // and the store deliberately cannot — it learns nothing about
            // what runs on an installation. Not installed here means no
            // claim, and the store stamps its newest published instead.
            KitInstalledRecordDto record = recordStore.findByOrigin(
                    tenant, projectId, source.getUrl(), body.vendor() + "/" + body.kitId());
            String version = record == null || record.getOrigin() == null
                    ? null
                    : stripLibraryPrefix(record.getOrigin().getCommit());
            return storeClient.review(source, access.token(),
                    body.vendor(), body.kitId(), body.stars(), body.text(), version);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    // ──────────────────── developer ────────────────────

    public record ApplyVendorRequest(
            String sourceId, String email, String password,
            String name, String displayName, @Nullable String homepage,
            String termsVersion) {}

    public record CreateKitRequest(
            String sourceId, String vendor, String kitId, String displayName,
            @Nullable String description, long priceCents, @Nullable String currency,
            @Nullable List<String> topics) {}

    public record PublishRequest(
            String sourceId, String vendor, String kitId, String version,
            @Nullable String vaultPassword) {}

    public record OperatorRequest(
            String sourceId,
            @Nullable String vendor, @Nullable String kitId,
            @Nullable String version, @Nullable String reason) {}

    /** Everything the developer screen needs in one answer. */
    public record DeveloperView(
            String sourceId,
            boolean connected,
            @Nullable VendorTerms terms,
            @Nullable Fees fees,
            List<Vendor> vendors,
            List<CatalogueEntry> kits,
            List<ReleaseRequest> requests,
            /** One entry per handle — the right is bought per shop front. */
            List<StoreClient.Publishing> publishing,
            @Nullable String problem) {}

    /**
     * The developer's own view of a store.
     *
     * <p>The terms and the fees are readable without being signed in —
     * somebody deciding whether to sell here should not have to sign up to
     * find out what it costs.
     */
    @GetMapping("/{projectId}/developer")
    public DeveloperView developer(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        String token = credentials.resolve(
                tenant, projectId, actor(request), source.getUrl(), null).token();
        try {
            VendorTerms terms = storeClient.vendorTerms(source);
            Fees fees = storeClient.fees(source);
            if (token == null || token.isBlank()) {
                return new DeveloperView(sourceId, false, terms, fees,
                        List.of(), List.of(), List.of(), List.of(), null);
            }
            return new DeveloperView(sourceId, true, terms, fees,
                    storeClient.myVendors(source, token),
                    storeClient.myKits(source, token),
                    storeClient.myRequests(source, token),
                    storeClient.publishing(source, token),
                    null);
        } catch (KitException e) {
            // A store that could not be asked is not a store with nothing
            // to say — the screen has to be able to tell those apart.
            return new DeveloperView(sourceId, token != null && !token.isBlank(),
                    null, null, List.of(), List.of(), List.of(), List.of(), e.getMessage());
        }
    }

    /**
     * Buy one more publishing period.
     *
     * <p>The store password, as with any line that spends money — and the
     * same shape as buying a kit, because it is the same order going
     * through the same provider.
     */
    @PostMapping("/{projectId}/developer/renew")
    public StoreClient.Order renewPublishing(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody RenewRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            StoreClient.Session session =
                    storeClient.login(source, body.email(), body.password());
            try {
                return storeClient.renewPublishing(source, session, body.vendorName());
            } finally {
                storeClient.logout(source, session);
            }
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** What the renew form sends. */
    public record RenewRequest(
            String sourceId, String vendorName, String email, String password) {}

    /**
     * Apply to be a vendor.
     *
     * <p>Asks for the store password, like buying does: accepting terms is
     * a decision by a person, and this installation's link token must not
     * be able to enter an agreement on their behalf.
     */
    @PostMapping("/{projectId}/developer/apply")
    public Vendor apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ApplyVendorRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return withSession(source, body.email(), body.password(), session ->
                    storeClient.applyVendor(source, session, body.name(),
                            body.displayName(), body.homepage(), body.termsVersion()));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Add a catalogue entry under one's own vendor. */
    @PostMapping("/{projectId}/developer/kits")
    public CatalogueEntry createKit(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody CreateKitRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.createKit(source, requireToken(tenant, projectId, source, request),
                    body.vendor(), body.kitId(), body.displayName(),
                    body.description(), body.priceCents(), body.currency(), body.topics());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * Export this project and submit it as a version.
     *
     * <p>The same export that writes to a git remote, pointed at a
     * directory and packed. Which means a project has to be a kit source
     * for this to work, and the export says so in its own words when it is
     * not.
     */
    @PostMapping("/{projectId}/developer/publish")
    public ReleaseRequest publish(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody PublishRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return developerService.publish(tenant, projectId, actor(request), source,
                    requireToken(tenant, projectId, source, request),
                    body.vendor(), body.kitId(), body.version(), body.vaultPassword());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    // ──────────────────── which surfaces this user gets ────────────────────

    /**
     * Which stores this user is set up to operate.
     *
     * <p><b>Why hide the surface at all.</b> It grants nothing — the store
     * answers 404 to an account that is not in {@code vance.store.operators},
     * and that property lives in the operator's own config where nothing in
     * a database can reach it. But a visible button is an invitation:
     * it puzzles everyone it does not belong to, and invites the rest to
     * try. Showing it only where it applies is not security, it is not
     * putting a door in a wall that has no room behind it.
     *
     * <p>Per source, because operating one store says nothing about
     * another — same shape as {@code store.account.<id>} and
     * {@code store.token.<id>}.
     */
    public record Surfaces(List<String> operatorSources, List<String> developerSources) {}

    /**
     * One store as the profile screen shows it.
     *
     * <p>Everything about the connection lives here: which store, where it
     * is, whether it answered, who this installation is signed in as and
     * what that account may do. The store area itself shows none of it —
     * a person picks a kit by its name, not by the host it came from, and
     * an address they cannot act on is furniture.
     */
    public record Connection(
            String sourceId,
            String title,
            String url,
            boolean reachable,
            @Nullable String problem,
            @Nullable String accountId,
            boolean operator,
            boolean developer) {}

    /** Every configured library, for the profile. */
    @GetMapping("/{projectId}/connections")
    public List<Connection> connections(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        String actor = actor(request);
        List<Connection> out = new ArrayList<>();
        for (KitSourceDto source : sources.configuredSources(tenant)) {
            if (source.getType() != KitSourceType.LIBRARY) continue;
            String token = credentials.resolve(tenant, projectId, actor, source.getUrl(), null)
                    .token();
            if (token == null || token.isBlank()) {
                // Not signed in is not a failure — and asking an
                // unauthenticated store who we are would answer nothing.
                out.add(new Connection(source.getId(), titleOf(source), source.getUrl(),
                        true, null, null, false, false));
                continue;
            }
            try {
                StoreClient.Identity identity = storeClient.identity(source, token);
                out.add(new Connection(source.getId(), titleOf(source), source.getUrl(),
                        true, null, identity.accountId(),
                        identity.operator(), identity.vendor()));
            } catch (KitException e) {
                // This is the one screen where the address and the reason
                // belong: somebody here can act on them.
                out.add(new Connection(source.getId(), titleOf(source), source.getUrl(),
                        false, e.getMessage(), null, false, false));
            }
        }
        return out;
    }

    /** What a person calls this store — its configured title, else its id. */
    private static String titleOf(KitSourceDto source) {
        return source.getTitle() == null || source.getTitle().isBlank()
                ? source.getId()
                : source.getTitle();
    }

    @GetMapping("/{projectId}/surfaces")
    public Surfaces surfaces(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        String actor = actor(request);
        List<String> operated = new ArrayList<>();
        List<String> developed = new ArrayList<>();
        for (KitSourceDto source : sources.configuredSources(tenant)) {
            if (source.getType() != KitSourceType.LIBRARY) continue;
            String token = credentials.resolve(tenant, projectId, actor, source.getUrl(), null)
                    .token();
            if (token == null || token.isBlank()) continue;
            try {
                StoreClient.Identity identity = storeClient.identity(source, token);
                if (identity.operator()) operated.add(source.getId());
                if (identity.vendor()) developed.add(source.getId());
            } catch (KitException e) {
                // An unreachable store is no role, and it is not worth an
                // error banner over a tab strip. The profile screen is where
                // that failure is shown, because that is where it can be
                // acted on.
                log.debug("StoreAddonController: could not ask '{}' who we are: {}",
                        source.getId(), e.getMessage());
            }
        }
        return new Surfaces(operated, developed);
    }

    // ──────────────────── operator ────────────────────

    /** What is waiting for the switch: vendors, then releases. */
    public record OperatorView(
            List<Vendor> pendingVendors,
            List<Release> submittedReleases) {}

    /**
     * The operator's queues.
     *
     * <p>Authenticated by this installation's link, like everything else
     * here. Whether this account may operate is the store's answer — it
     * refuses anyone not in its own operator configuration, and a sign-in
     * on this screen would establish nothing it does not already know.
     */
    // ──────────────────── money out, for the operator ────────────────────

    /**
     * Everything the operator's money screen needs, in one answer.
     *
     * <p>One call rather than five: the screen shows them together, and five
     * round trips would give it five chances to be half-loaded.
     */
    @GetMapping("/{projectId}/operator/money")
    public MoneyView money(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        String token = requireToken(tenant, projectId, source, request);
        try {
            return new MoneyView(
                    storeClient.payoutsDue(source, token),
                    storeClient.openPayouts(source, token),
                    storeClient.orders(source, token, null),
                    null);
        } catch (KitException e) {
            return new MoneyView(List.of(), List.of(), List.of(), e.getMessage());
        }
    }

    /** What the operator's money screen shows. */
    public record MoneyView(
            List<StoreClient.Due> due,
            List<StoreClient.Payout> open,
            List<StoreClient.SaleRow> orders,
            @Nullable String problem) {}

    /** Pay one vendor what they are owed. */
    @PostMapping("/{projectId}/operator/payouts/{vendorName}")
    public StoreClient.Payout pay(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("vendorName") String vendorName,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.payVendor(
                    source, requireToken(tenant, projectId, source, request), vendorName);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Give a failed payout's orders back so a new one can carry them. */
    @PostMapping("/{projectId}/operator/payouts/{payoutName}/release")
    public StoreClient.Payout releasePayout(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("payoutName") String payoutName,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.releasePayout(
                    source, requireToken(tenant, projectId, source, request), payoutName);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Ask the rail about everything in flight, now. */
    @PostMapping("/{projectId}/operator/payouts-reconcile")
    public StoreClient.ReconcileResult reconcile(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.reconcilePayouts(
                    source, requireToken(tenant, projectId, source, request));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Give a sale back. */
    @PostMapping("/{projectId}/operator/refund")
    public StoreClient.RefundResult refund(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody RefundRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.refund(source,
                    requireToken(tenant, projectId, source, request),
                    body.orderName(), body.reason(), body.alreadyReturned());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** What the refund form sends. */
    public record RefundRequest(
            String sourceId, String orderName,
            @Nullable String reason, boolean alreadyReturned) {}

    /**
     * Sales and notes carrying no usable classification.
     *
     * <p>The report counts them; this is where somebody can act on them.
     */
    @GetMapping("/{projectId}/operator/unclassified")
    public StoreClient.Unclassified unclassified(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.unclassified(
                    source, requireToken(tenant, projectId, source, request));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Supply the buyer's country; the rate follows from it. */
    @PostMapping("/{projectId}/operator/classify")
    public StoreClient.SaleRow classify(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ClassifyRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.classify(source,
                    requireToken(tenant, projectId, source, request),
                    body.orderName(), body.billingCountry(), body.vatId());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** What the classification form sends. */
    public record ClassifyRequest(
            String sourceId, String orderName, String billingCountry, @Nullable String vatId) {}

    /** Reverse an unclassifiable note and write it again. */
    @PostMapping("/{projectId}/operator/credit-notes/reissue")
    public StoreClient.CreditNote reissueCreditNote(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ReissueRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.reissueCreditNote(source,
                    requireToken(tenant, projectId, source, request), body.payoutName());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Which settlement to write again. */
    public record ReissueRequest(String sourceId, String payoutName) {}

    /** Every receipt this account holds at one store. */
    @GetMapping("/{projectId}/documents/invoices")
    public List<StoreClient.Receipt> receipts(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.receipts(
                    source, requireToken(tenant, projectId, source, request));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * The three documents, as the thing somebody files.
     *
     * <p>Proxied rather than linked: the store is reachable with a link
     * token this browser does not have, and handing the browser one so it
     * could fetch a PDF directly would put a credential in a URL.
     */
    @GetMapping(value = "/{projectId}/documents/invoice", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> invoicePdf(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("orderName") String orderName,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return paper(storeClient.invoicePdf(
                    source, requireToken(tenant, projectId, source, request), orderName));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    @GetMapping(value = "/{projectId}/documents/credit-note",
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> creditNotePdf(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("vendorName") String vendorName,
            @RequestParam("number") String number,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return paper(storeClient.creditNotePdf(source,
                    requireToken(tenant, projectId, source, request), vendorName, number));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    @GetMapping(value = "/{projectId}/documents/tax-report",
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> taxReportPdf(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return paper(storeClient.taxReportPdf(
                    source, requireToken(tenant, projectId, source, request), from, to));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    private static ResponseEntity<byte[]> paper(StoreClient.Paper paper) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + paper.filename() + "\"")
                .body(paper.bytes());
    }

    /** What is owed for a period. */
    @GetMapping("/{projectId}/operator/tax-report")
    public StoreClient.TaxReport taxReport(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        try {
            return storeClient.taxReport(
                    source, requireToken(tenant, projectId, source, request), from, to);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    // ──────────────────── money in, for the vendor ────────────────────

    /** Claim a domain as a badge — never as the handle. */
    @PostMapping("/{projectId}/developer/domain")
    public StoreClient.Vendor claimDomain(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody DomainRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.claimDomain(source,
                    requireToken(tenant, projectId, source, request),
                    body.vendorName(), body.domain());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Ask the store to look for the record now. */
    @PostMapping("/{projectId}/developer/domain/verify")
    public StoreClient.Vendor verifyDomain(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody DomainRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        try {
            return storeClient.verifyDomain(source,
                    requireToken(tenant, projectId, source, request), body.vendorName());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** What the domain form sends; the domain is absent when verifying. */
    public record DomainRequest(
            String sourceId, String vendorName, @Nullable String domain) {}

    /** What this vendor is owed, was paid, and can book against. */
    @GetMapping("/{projectId}/developer/money")
    public VendorMoneyView vendorMoney(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("vendorName") String vendorName,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        String token = credentials.resolve(
                tenant, projectId, actor(request), source.getUrl(), null).token();
        try {
            return new VendorMoneyView(
                    storeClient.myDue(source, token, vendorName),
                    storeClient.myPayouts(source, token, vendorName),
                    storeClient.myCreditNotes(source, token, vendorName),
                    null);
        } catch (KitException e) {
            return new VendorMoneyView(null, List.of(), List.of(), e.getMessage());
        }
    }

    /** What a vendor sees about their own money. */
    public record VendorMoneyView(
            @Nullable Due due,
            List<StoreClient.Payout> payouts,
            List<StoreClient.CreditNote> creditNotes,
            @Nullable String problem) {}

    /** Say where the money should go. */
    @PostMapping("/{projectId}/developer/payout-account")
    public Vendor setPayoutAccount(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody PayoutAccountRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        String token = credentials.resolve(
                tenant, projectId, actor(request), source.getUrl(), null).token();
        try {
            return storeClient.setPayoutAccount(source, token, body.vendorName(),
                    body.type(), body.handle(), body.holderName(),
                    body.country(), body.vatId());
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** What the payout-account form sends. */
    public record PayoutAccountRequest(
            String sourceId, String vendorName, String type, String handle,
            @Nullable String holderName, @Nullable String country, @Nullable String vatId) {}

    @GetMapping("/{projectId}/operator/queue")
    public OperatorView operatorQueue(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam("sourceId") String sourceId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, sourceId);
        String token = requireToken(tenant, projectId, source, request);
        try {
            return new OperatorView(
                    storeClient.pendingVendors(source, token),
                    storeClient.submittedReleases(source, token));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** The switch itself — approve or refuse a vendor or a release. */
    @PostMapping("/{projectId}/operator/{decision}")
    public OperatorView decide(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("decision") String decision,
            @RequestBody OperatorRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        String token = requireToken(tenant, projectId, source, request);
        try {
            String reason = body.reason() == null ? "" : body.reason();
            switch (decision) {
                case "approve-vendor" ->
                        storeClient.approveVendor(source, token, required(body.vendor()));
                case "reject-vendor" ->
                        storeClient.rejectVendor(source, token, required(body.vendor()), reason);
                case "approve-release" ->
                        storeClient.approveRelease(source, token, required(body.vendor()),
                                required(body.kitId()), required(body.version()));
                case "reject-release" ->
                        storeClient.rejectRelease(source, token, required(body.vendor()),
                                required(body.kitId()), required(body.version()), reason);
                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unknown decision: " + decision);
            }
            // The queues are what the screen shows next.
            return new OperatorView(
                    storeClient.pendingVendors(source, token),
                    storeClient.submittedReleases(source, token));
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * Sign in, do one thing, sign out.
     *
     * <p>A brain is not a person: leaving a session open would be a live
     * credential for an account that nobody is holding on purpose. The
     * same shape {@code StoreConnectionService} uses when linking.
     */
    private <T> T withSession(
            KitSourceDto source, String email, String password,
            java.util.function.Function<StoreClient.Session, T> work) {

        StoreClient.Session session = storeClient.login(source, email, password);
        try {
            return work.apply(session);
        } finally {
            storeClient.logout(source, session);
        }
    }

    private static String required(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "this decision needs a vendor, kit and version");
        }
        return value;
    }

    /** This installation's link token, or a clear refusal. */
    private String requireToken(
            String tenant, String projectId, KitSourceDto source, HttpServletRequest request) {

        KitAccess access = credentials.resolve(
                tenant, projectId, actor(request), source.getUrl(), null);
        if (access.token() == null || access.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sign in to this store first");
        }
        return access.token();
    }

    /**
     * Resolve a configured library by id.
     *
     * <p>By id and not by url from the body: a url out of a request would
     * let a caller point this at a host of their choosing and have the
     * brain sign in there with credentials a person typed for a different
     * one.
     */
    private KitSourceDto library(String tenantId, String sourceId) {
        return sources.configuredSources(tenantId).stream()
                .filter(source -> source.getId().equals(sourceId))
                .filter(source -> source.getType() == KitSourceType.LIBRARY)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no library source '" + sourceId + "'"));
    }

    /** {@code library:1.2.0} → {@code 1.2.0}; a git commit hash is no version. */
    private static @Nullable String stripLibraryPrefix(@Nullable String commit) {
        if (commit == null || !commit.startsWith("library:")) return null;
        String version = commit.substring("library:".length());
        return version.isBlank() || "unversioned".equals(version) ? null : version;
    }

    private static ResponseStatusException storeError(KitException e) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
    }

    /**
     * The signed-in brain user, whose settings the credential belongs to.
     *
     * <p>Required rather than optional here, unlike in the ordinary kit
     * controller where it only labels a write: a store credential belongs
     * to a person, and there is nowhere to put one that belongs to nobody.
     */
    private static String actor(HttpServletRequest request) {
        Object user = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String actor = user == null ? null : user.toString();
        if (actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no user in this request");
        }
        return actor;
    }
}

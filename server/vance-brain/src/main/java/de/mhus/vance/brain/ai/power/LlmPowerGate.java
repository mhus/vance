package de.mhus.vance.brain.ai.power;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.UsageGate;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The {@link UsageGate} that enforces the LLM power switches of
 * {@link LlmPowerSwitch}.
 *
 * <h2>No exceptions</h2>
 *
 * Unlike the budget gate, nothing passes a switch that is off: not compaction
 * or triage, not free local models. The switch is an emergency stop, and an
 * emergency stop with a list of exemptions is a cost control.
 *
 * <h2>Who the call is for</h2>
 *
 * The tenant switch needs only the tenant. A user switch needs the user, which
 * only a few callers name directly ({@link CallAttribution#userId()}); calls
 * inside a session or process belong to that session's owner and are resolved
 * from there. That lookup happens only while at least one user of the tenant
 * is switched off, and its answers are remembered for {@link #OWNER_TTL}: the
 * owner of a session does not change during its life, but a user rename
 * rewrites it to the new login, and a remembered old login would no longer
 * match the switch that the rename carried over. A call that has neither user,
 * session nor process (scheduler ticks, hooks, background services) is subject
 * to the tenant switch only.
 *
 * <p>In a multi-user session the owner stands for every participant: a call is
 * refused when the owner is switched off, not when only the participant who
 * sent the message is — the model call does not carry its sender.
 *
 * <p>Beyond chat and embeddings, which reach this gate through the decorator
 * chain, the image, audio and decision services ask {@link #refusal} at their
 * own entry and report a refusal through their own error type.
 */
@Component
public class LlmPowerGate implements UsageGate {

    /** Bound on remembered owners per cache; entries are cheap to rebuild. */
    static final int MAX_OWNERS = 10_000;

    /**
     * How long a resolved owner is trusted. Bounds how long a user rename can
     * leave a stale login here — the switch moves with the rename at once.
     */
    static final Duration OWNER_TTL = Duration.ofMinutes(5);

    private final LlmPowerSwitch powerSwitch;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;

    private final Cache<String, String> userBySession;
    private final Cache<String, String> userByProcess;

    @Autowired
    public LlmPowerGate(
            LlmPowerSwitch powerSwitch, SessionService sessionService, ThinkProcessService thinkProcessService) {
        this(powerSwitch, sessionService, thinkProcessService, Ticker.systemTicker());
    }

    LlmPowerGate(
            LlmPowerSwitch powerSwitch,
            SessionService sessionService,
            ThinkProcessService thinkProcessService,
            Ticker ticker) {
        this.powerSwitch = powerSwitch;
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
        this.userBySession = ownerCache(ticker);
        this.userByProcess = ownerCache(ticker);
    }

    @Override
    public void admit(CallAttribution attribution, ModelInfo model) {
        String refusal =
                refusal(attribution.tenantId(), attribution.userId(), attribution.sessionId(), attribution.processId());
        if (refusal != null) {
            throw new LlmPowerOffException(refusal);
        }
    }

    /**
     * Why a call for this tenant and user may not go out, or {@code null} when
     * it may. {@code sessionId} / {@code processId} are only read when
     * {@code userId} is not given and a user switch is off.
     */
    public @Nullable String refusal(
            String tenantId, @Nullable String userId, @Nullable String sessionId, @Nullable String processId) {
        LlmPowerState state = powerSwitch.state(tenantId);
        if (!state.tenantOn()) {
            return "LLM access is switched off for this tenant. A tenant admin can switch it back on"
                    + " (Insights > Power).";
        }
        if (state.usersOff().isEmpty()) {
            return null;
        }
        String user = StringUtils.isNotBlank(userId) ? userId : ownerOf(sessionId, processId);
        if (user != null && !state.userOn(user)) {
            return "LLM access is switched off for user '" + user + "'. A tenant admin can switch it back on"
                    + " (Insights > Power).";
        }
        return null;
    }

    private @Nullable String ownerOf(@Nullable String sessionId, @Nullable String processId) {
        if (StringUtils.isNotBlank(sessionId)) {
            return sessionOwner(sessionId);
        }
        if (StringUtils.isNotBlank(processId)) {
            String cached = userByProcess.getIfPresent(processId);
            if (cached != null) {
                return cached;
            }
            String owner = thinkProcessService
                    .findById(processId)
                    .map(ThinkProcessDocument::getSessionId)
                    .filter(StringUtils::isNotBlank)
                    .map(this::sessionOwner)
                    .orElse(null);
            remember(userByProcess, processId, owner);
            return owner;
        }
        return null;
    }

    private @Nullable String sessionOwner(String sessionId) {
        String cached = userBySession.getIfPresent(sessionId);
        if (cached != null) {
            return cached;
        }
        String owner = sessionService
                .findBySessionId(sessionId)
                .map(SessionDocument::getUserId)
                .filter(StringUtils::isNotBlank)
                .orElse(null);
        remember(userBySession, sessionId, owner);
        return owner;
    }

    /** A miss is not remembered: the session may simply not be written yet. */
    private static void remember(Cache<String, String> cache, String key, @Nullable String owner) {
        if (owner != null) {
            cache.put(key, owner);
        }
    }

    private static Cache<String, String> ownerCache(Ticker ticker) {
        return Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(MAX_OWNERS)
                .expireAfterWrite(OWNER_TTL)
                .build();
    }
}

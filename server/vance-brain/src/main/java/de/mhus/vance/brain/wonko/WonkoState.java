package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionSetDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Run-local state of one Wonko process, persisted in
 * {@code engineParams.wonkoState} (Benjy/Zaphod form). What must outlive the
 * run lives in the {@link WonkoRegister}, not here.
 */
@Data
@NoArgsConstructor
public class WonkoState {

    private String subjectKey = "";

    private List<String> subjectRefs = new ArrayList<>();

    /** Whether Wonko already asked the chat for a subject (no {@code subject} param). */
    private boolean subjectRequested;

    /** Goal taken from the chat message that named the subject; the process goal wins. */
    private @Nullable String goal;

    /** Rounds asked in this run. */
    private int round;

    /** The question set waiting for an answer, {@code null} when none is out. */
    private @Nullable QuestionSetDto pendingSet;

    /** Mandate-loosening proposals inside {@link #pendingSet}, by question key. */
    private List<PendingProposal> pendingProposals = new ArrayList<>();

    /** Topic keys the user explicitly put off in this run — not asked again before the next run. */
    private List<String> postponedKeys = new ArrayList<>();

    /** Free-text statements of the user, handed to every following check. */
    private List<String> userStatements = new ArrayList<>();

    private List<RoundStat> stats = new ArrayList<>();

    // ─── check queue (plan §4a) ───

    /** Reference documents the subject is measured against (resolved paths). */
    private List<String> referencePaths = new ArrayList<>();

    private List<WonkoItem> queue = new ArrayList<>();

    private List<WonkoClaim> claims = new ArrayList<>();

    private List<WonkoPassage> passages = new ArrayList<>();

    /** Findings waiting to be asked (the question pool, §4a.5). */
    private List<WonkoFinding> pendingFindings = new ArrayList<>();

    /** Topic key → claim id, for rechecking the claim after an answer. */
    private Map<String, String> topicClaims = new LinkedHashMap<>();

    /** Escape outcomes and discards with their reasons. */
    private List<WonkoDecision> decisions = new ArrayList<>();

    /** Items per type, for the progress view. */
    private Map<WonkoItemType, ItemCount> itemCounts = new LinkedHashMap<>();

    private int nextId;

    /** Model calls used in this run. */
    private int calls;

    /** Model calls allowed before the budget checkpoint asks to go on. */
    private int callBudget;

    /** A budget checkpoint question is part of the pending set. */
    private boolean checkpointAsked;

    /** Mandate-loosening proposals waiting for the next question set. */
    private List<PendingProposal> proposalPool = new ArrayList<>();

    /** The caller's mandate was laid over the remembered one (once per run). */
    private boolean mandateApplied;

    /** Reference documents a coverage item was already queued for. */
    private List<String> coveredRefs = new ArrayList<>();

    private boolean finished;

    private @Nullable String finalReport;

    /**
     * Follow-ups the user asked for along with the review ("…and work it
     * in"). At the end of a run the first one is offered as a question; a
     * confirmed one goes back into the intake like a new message.
     */
    private List<String> followUps = new ArrayList<>();

    /** Confirmed follow-ups waiting to enter the intake — drained in the same turn. */
    private List<String> followUpInputs = new ArrayList<>();

    /**
     * Free-text answers to an offered follow-up — not a confirmation: the
     * offer is closed, the text enters the intake as a free message with the
     * closed offer in view. Drained in the same turn.
     */
    private List<FollowUpReply> followUpReplies = new ArrayList<>();

    /** The worker the router's {@code work} started; {@code null} when none runs. */
    private @Nullable String workerName;

    private @Nullable String workerId;

    /**
     * The worker ended its turn with a reply and waits for its next input —
     * a question to the user or a result, the route judge tells which by the
     * user's next message. A tool loop signals this by its reply alone (a
     * natural stop leaves the worker IDLE; no lifecycle event follows).
     */
    private boolean workerWaiting;

    /** The worker's last reply while it waits — what the route judge reads the next message against. */
    private @Nullable String workerLastReply;

    /** The worker's answer of this exchange reached the chat already (a reply came). */
    private boolean workerReplied;

    /** The subject's text fingerprint when the work started — a change triggers one recheck. */
    private @Nullable String subjectFingerprint;

    /**
     * The worker changed the subject while a question set was open — the
     * recheck waits until the set is answered, so the answers are not lost
     * to a reset run.
     */
    private boolean recheckDeferred;

    /**
     * Starts a fresh run on the same subject — the subject changed, so
     * claims, passages and findings of the last run are stale. Kept: the
     * subject, the goal, what the user said and whether the mandate is
     * applied, the follow-ups and a running worker; everything durable lives
     * in the register anyway.
     */
    public void resetRun() {
        round = 0;
        pendingSet = null;
        pendingProposals = new ArrayList<>();
        postponedKeys = new ArrayList<>();
        stats = new ArrayList<>();
        referencePaths = new ArrayList<>();
        queue = new ArrayList<>();
        claims = new ArrayList<>();
        passages = new ArrayList<>();
        pendingFindings = new ArrayList<>();
        topicClaims = new LinkedHashMap<>();
        decisions = new ArrayList<>();
        itemCounts = new LinkedHashMap<>();
        nextId = 0;
        calls = 0;
        callBudget = 0;
        checkpointAsked = false;
        proposalPool = new ArrayList<>();
        coveredRefs = new ArrayList<>();
        finished = false;
        finalReport = null;
        // A fresh run is the recheck a worker's change was waiting for.
        recheckDeferred = false;
    }

    /** A free-text answer to an offered follow-up: the closed offer and what the user wrote instead. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpReply {
        private String offer = "";
        private String text = "";
    }

    /** A mandate proposal awaiting the user's yes/no. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingProposal {
        private String key = "";
        private @Nullable WonkoCategoryChange change;
    }

    /** What one round asked and got back — the input of the convergence checks. */
    @Data
    @NoArgsConstructor
    public static class RoundStat {
        private int round;
        private int asked;
        private int answered;
        private int settled;
        private int later;
        private int unknown;
        /** Open (non-minor) topics after the round was planned. */
        private int openAfter;
    }

    /** Enqueued and finished items of one type. */
    @Data
    @NoArgsConstructor
    public static class ItemCount {
        private int done;
        private int total;
    }
}

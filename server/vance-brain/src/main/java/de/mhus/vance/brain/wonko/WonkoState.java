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

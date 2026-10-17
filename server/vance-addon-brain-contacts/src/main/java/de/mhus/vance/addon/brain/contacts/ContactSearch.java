package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactAddress;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Matching and ranking for the address book. Pure functions — the service
 * hands in the merged entries, this decides which match and in what order.
 *
 * <p>Ranking is deliberately simple: per field an exact hit beats a prefix
 * beats a substring, and a name hit beats a mail or phone hit beats an
 * organisation, a category or the note. Every query word must hit some field
 * ("anna berlin" finds Anna in Berlin, not every Anna).
 */
public final class ContactSearch {

    /** Filter criteria; every non-null criterion must hold. */
    public record Criteria(
            @Nullable String query,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String org,
            @Nullable String category,
            @Nullable ContactKind kind) {

        public static Criteria all() {
            return new Criteria(null, null, null, null, null, null);
        }

        public static Criteria query(@Nullable String q) {
            return new Criteria(q, null, null, null, null, null);
        }
    }

    private static final int W_NAME = 100;
    private static final int W_CONTACT = 70;
    private static final int W_ORG = 50;
    private static final int W_CATEGORY = 40;
    private static final int W_OTHER = 25;
    private static final int W_NOTE = 10;

    private ContactSearch() {}

    public static List<ContactEntry> search(List<ContactEntry> entries, Criteria c) {
        record Scored(ContactEntry entry, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (ContactEntry e : entries) {
            if (!passesFilters(e.card(), c)) continue;
            int score = c.query() == null || c.query().isBlank() ? 1 : score(e.card(), c.query());
            if (score > 0) hits.add(new Scored(e, score));
        }
        hits.sort(Comparator.comparingInt(Scored::score)
                .reversed()
                .thenComparing(s -> fold(s.entry().card().displayName())));
        return hits.stream().map(Scored::entry).toList();
    }

    static boolean passesFilters(ContactCard card, Criteria c) {
        if (c.kind() != null && card.kind() != c.kind()) return false;
        if (c.email() != null && !c.email().isBlank()) {
            String q = fold(c.email());
            if (card.emails().stream().noneMatch(v -> fold(v.value()).contains(q))) return false;
        }
        if (c.phone() != null && !c.phone().isBlank()) {
            if (card.phones().stream().noneMatch(v -> phoneMatches(v.value(), c.phone()))) return false;
        }
        if (c.org() != null && !c.org().isBlank()) {
            String q = fold(c.org());
            String org = card.org() == null ? "" : fold(String.join(" ", orgParts(card)));
            if (!org.contains(q)) return false;
        }
        if (c.category() != null && !c.category().isBlank()) {
            String q = fold(c.category());
            if (card.categories().stream().noneMatch(cat -> fold(cat).equals(q))) return false;
        }
        return true;
    }

    /** Sum over query words of the best field hit; 0 when any word misses. */
    static int score(ContactCard card, String query) {
        int total = 0;
        for (String word : fold(query).split("\\s+")) {
            if (word.isEmpty()) continue;
            int best = bestHit(card, word);
            if (best == 0) return 0;
            total += best;
        }
        return total;
    }

    private static int bestHit(ContactCard card, String word) {
        int best = 0;
        best = Math.max(best, hit(card.displayName(), word, W_NAME));
        if (card.name() != null) {
            best = Math.max(best, hit(card.name().display(), word, W_NAME));
        }
        for (String n : card.nicknames()) best = Math.max(best, hit(n, word, W_NAME));
        for (ContactValue v : card.emails()) best = Math.max(best, hit(v.value(), word, W_CONTACT));
        String digits = normalizePhone(word);
        if (digits.length() >= 3 && digits.length() * 2 >= word.replace("+", "").length()) {
            for (ContactValue v : card.phones()) {
                if (phoneMatches(v.value(), word)) best = Math.max(best, W_CONTACT);
            }
        }
        for (String o : orgParts(card)) best = Math.max(best, hit(o, word, W_ORG));
        best = Math.max(best, hit(card.title(), word, W_ORG));
        for (String cat : card.categories()) best = Math.max(best, hit(cat, word, W_CATEGORY));
        for (ContactAddress a : card.addresses()) best = Math.max(best, hit(a.oneLine(), word, W_OTHER));
        for (ContactValue v : card.urls()) best = Math.max(best, hit(v.value(), word, W_OTHER));
        best = Math.max(best, hit(card.role(), word, W_OTHER));
        if (best == 0 && card.note() != null && fold(card.note()).contains(word)) best = W_NOTE;
        return best;
    }

    /** weight for an exact field match, 3/4 for a word prefix, 1/2 for a substring. */
    private static int hit(@Nullable String field, String word, int weight) {
        if (field == null || field.isEmpty()) return 0;
        String f = fold(field);
        if (f.equals(word)) return weight;
        for (String token : f.split("[\\s,.@/()-]+")) {
            if (token.equals(word)) return weight * 9 / 10;
            if (token.startsWith(word)) return weight * 3 / 4;
        }
        return f.contains(word) ? weight / 2 : 0;
    }

    private static List<String> orgParts(ContactCard card) {
        List<String> out = new ArrayList<>();
        if (card.org() != null) {
            if (card.org().name() != null) out.add(card.org().name());
            out.addAll(card.org().units());
        }
        return out;
    }

    /**
     * Digits only, with a leading {@code +} kept — "+49 (30) 123-45" →
     * "+493012345". Enough to compare numbers as people type them; no
     * country-code inference (that would need a numbering-plan library).
     */
    public static String normalizePhone(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String s = raw.strip();
        if (s.startsWith("+")) sb.append('+');
        else if (s.startsWith("00")) {
            sb.append('+');
            s = s.substring(2);
        }
        for (char ch : s.toCharArray()) {
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Whether two numbers are the same as far as their trailing digits go:
     * "0170 7654321" and "+49 170 7654321" match, because the national form
     * drops the trunk prefix the international form replaces.
     */
    public static boolean phoneMatches(String stored, String query) {
        String a = stripTrunk(normalizePhone(stored));
        String b = stripTrunk(normalizePhone(query));
        if (b.length() < 3 || a.isEmpty()) return false;
        if (b.length() >= 6) return a.endsWith(b) || b.endsWith(a);
        return a.contains(b);
    }

    private static String stripTrunk(String normalized) {
        String s = normalized.startsWith("+") ? normalized.substring(1) : normalized;
        while (s.startsWith("0")) s = s.substring(1);
        return s;
    }

    /** Lower case without diacritics: "Müller" and "muller" meet. */
    public static String fold(@Nullable String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).replace("ß", "ss");
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}

package de.mhus.vance.brain.wonko;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Candidate retrieval for {@code LOCATE} — code, not model (plan §4a.3): the
 * reference documents are cut into paragraphs (each carrying its heading
 * path, so "Retention > § 7 Period" stays attached to its text), every
 * paragraph is scored by the search terms it contains, and the best ones go
 * to the model, which only selects. The model never has to search 120 files;
 * it gets a short list and says which of it applies.
 */
final class WonkoPassageSearch {

    /** A paragraph offered to the model; {@code id} is local to one locate call. */
    record Candidate(String id, String ref, String text, int score) {}

    private static final int MAX_PARAGRAPH_CHARS = 1200;

    private static final int PHRASE_IN_TEXT = 3;
    private static final int PHRASE_IN_PATH = 2;
    private static final int MIN_WORD = 4;
    private static final int STEM = 5;

    private WonkoPassageSearch() {}

    static List<Candidate> search(Map<String, String> docs, List<String> terms, int limit) {
        Set<String> phrases = new LinkedHashSet<>();
        for (String t : terms) {
            String n = normalise(t);
            if (n.length() >= 2) {
                phrases.add(n);
            }
        }
        if (phrases.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, String> doc : docs.entrySet()) {
            String refText = normalise(doc.getKey());
            for (String paragraph : paragraphs(doc.getValue())) {
                int score = score(normalise(paragraph), refText, phrases);
                if (score > 0) {
                    scored.add(new Scored(doc.getKey(), paragraph, score, order++));
                }
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        List<Candidate> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new Candidate("k" + (out.size() + 1), s.ref(), s.text(), s.score()));
        }
        return out;
    }

    /**
     * A whole phrase in the paragraph counts most, in the document path less;
     * failing both, each word of the phrase whose stem starts a word of the
     * paragraph counts once ("storage" finds "stored"). Recall over precision —
     * the model selects afterwards.
     */
    static int score(String paragraph, String refText, Set<String> phrases) {
        int score = 0;
        Set<String> paragraphStems = stems(paragraph);
        Set<String> wordHits = new LinkedHashSet<>();
        for (String phrase : phrases) {
            if (paragraph.contains(phrase)) {
                score += PHRASE_IN_TEXT;
            } else if (refText.contains(phrase)) {
                score += PHRASE_IN_PATH;
            } else {
                for (String stem : stems(phrase)) {
                    if (paragraphStems.contains(stem)) {
                        wordHits.add(stem);
                    }
                }
            }
        }
        return score + wordHits.size();
    }

    /** Stems of the words long enough to mean something: the first letters of each word. */
    private static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String word : text.split("[^\\p{L}\\p{N}§]+")) {
            if (word.length() >= MIN_WORD) {
                out.add(word.length() > STEM ? word.substring(0, STEM) : word);
            }
        }
        return out;
    }

    /** Lowercase, path separators and dashes as blanks — "data-residency.md" matches "data residency". */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[-_/]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Paragraphs separated by blank lines, each prefixed with its heading
     * path ("Data residency > § 1 Location of customer data") — a rule's
     * subject often stands only in the top heading. Heading-only blocks are
     * not paragraphs of their own; overlong paragraphs are cut so one
     * candidate stays readable.
     */
    static List<String> paragraphs(String content) {
        List<String> out = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        for (String block : content.split("\\R\\s*\\R")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>(List.of(trimmed.split("\\R")));
            while (!lines.isEmpty() && lines.get(0).startsWith("#")) {
                String line = lines.remove(0);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                while (headings.size() >= level) {
                    headings.remove(headings.size() - 1);
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(line.substring(level).strip());
            }
            if (lines.isEmpty()) {
                continue;
            }
            String body = String.join("\n", lines);
            if (body.length() > MAX_PARAGRAPH_CHARS) {
                body = body.substring(0, MAX_PARAGRAPH_CHARS) + "…";
            }
            String path = String.join(
                    " > ", headings.stream().filter(h -> !h.isEmpty()).toList());
            out.add(path.isEmpty() ? body : path + "\n" + body);
        }
        return out;
    }

    private record Scored(String ref, String text, int score, int order) {}
}

package de.mhus.vance.brain.trillian.nature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The character a fresh adam Trillian starts with: a given name, its
 * gender, and one line of temperament.
 *
 * <p><b>Why a name at all.</b> A worker addressed as
 * {@code _trillian-adam-4711} is a process; one called Ada is someone a
 * human can talk about ("ask Ada to check that"). The account name stays
 * the technical identity — it keys grants, documents and logs — and this
 * is the identity the conversation uses.
 *
 * <p><b>A-names, because the Nature is adam.</b> Arbitrary, but it makes
 * the generation legible: seeing "Ansel" somewhere, one can tell which
 * Nature minted it without a lookup.
 *
 * <p><b>Gender is curated per name, not derived.</b> Andrea is female in
 * German and male in Italian, Alex is either; a derivation guesses, and
 * guessing wrong here is a small avoidable insult. One extra column in a
 * list costs nothing.
 *
 * <p>Everything lands in the ordinary attribute map, so a human can
 * change all of it with {@code //trillian attr set} — a generated
 * character is a starting point, not a fact about the Trillian.
 */
final class TrillianCharacter {

    /** Attribute keys. Plain words: they are rendered into a prompt. */
    static final String ATTR_NAME = "name";
    static final String ATTR_GENDER = "gender";
    static final String ATTR_CHARACTER = "character";

    private record Person(String name, String gender) {
    }

    private static final List<Person> NAMES = List.of(
            new Person("Ada", "female"),
            new Person("Aiden", "male"),
            new Person("Alina", "female"),
            new Person("Anton", "male"),
            new Person("Amara", "female"),
            new Person("Arne", "male"),
            new Person("Astrid", "female"),
            new Person("Amir", "male"),
            new Person("Anouk", "female"),
            new Person("Aron", "male"),
            new Person("Alma", "female"),
            new Person("Ansel", "male"));

    /**
     * Temperaments, phrased as how the Trillian works rather than how it
     * feels. A trait that cannot be acted on is decoration and costs
     * prompt on every turn.
     */
    private static final List<String> TRAITS = List.of(
            "Terse. States the result and stops; no preamble, no summary of what was asked.",
            "Methodical. Says in one line what it is about to do before it does it.",
            "Sceptical. Names the assumption it is least sure about instead of hiding it.",
            "Direct. Says plainly and early when something cannot be done, and why.",
            "Curious. Mentions in one line what it noticed on the way, if it matters later.",
            "Careful. Prefers the smaller change and says what it deliberately left alone.");

    private TrillianCharacter() {
    }

    /**
     * A fresh character. Not guaranteed unique across Trillians — two
     * Adas in one project are possible and harmless, since everything
     * technical keys on the account name.
     */
    static Map<String, Object> generate(Random random) {
        Person person = NAMES.get(random.nextInt(NAMES.size()));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_NAME, person.name());
        attributes.put(ATTR_GENDER, person.gender());
        attributes.put(ATTR_CHARACTER, TRAITS.get(random.nextInt(TRAITS.size())));
        return attributes;
    }
}

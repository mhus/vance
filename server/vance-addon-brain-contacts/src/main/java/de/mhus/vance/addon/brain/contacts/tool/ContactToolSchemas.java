package de.mhus.vance.addon.brain.contacts.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-schema fragments for the card fields. The parameter names are the
 * on-disk field names ({@code ContactCodec}), so the model learns one grammar
 * for tools and files.
 */
final class ContactToolSchemas {

    private ContactToolSchemas() {}

    private static final Map<String, Object> STRING_LIST = Map.of("type", "array", "items", Map.of("type", "string"));

    private static Map<String, Object> valueList(String what) {
        return Map.of(
                "type",
                "array",
                "description",
                what + " Each item: {value, types?, pref?} — types from vCard (work, home, cell, voice, fax, …), "
                        + "pref 1 = preferred. A plain string is taken as the value.",
                "items",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "value", Map.of("type", "string"),
                                "types", STRING_LIST,
                                "pref", Map.of("type", "integer"))));
    }

    /** Every editable card field. {@code photo} and {@code extras} are not among them. */
    static Map<String, Object> cardFields() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "kind",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("individual", "org", "group"),
                        "description",
                        "individual (default), org for a company, group for a named set of contacts."));
        p.put("fullName", Map.of("type", "string", "description", "Display name, e.g. 'Dr. Anna Berger'."));
        p.put(
                "name",
                Map.of(
                        "type",
                        "object",
                        "description",
                        "Structured name.",
                        "properties",
                        Map.of(
                                "given", Map.of("type", "string"),
                                "family", Map.of("type", "string"),
                                "additional", STRING_LIST,
                                "prefixes", STRING_LIST,
                                "suffixes", STRING_LIST)));
        p.put("nicknames", STRING_LIST);
        p.put("emails", valueList("E-mail addresses."));
        p.put("phones", valueList("Phone numbers — write them international: '+49 30 1234567'."));
        p.put(
                "addresses",
                Map.of(
                        "type",
                        "array",
                        "description",
                        "Postal addresses: {types?, street, locality (city), region, postalCode, country, poBox?, extended?, label?}.",
                        "items",
                        Map.of("type", "object")));
        p.put(
                "org",
                Map.of(
                        "type",
                        "object",
                        "description",
                        "Organisation: {name, units? (departments), ref? (id of an org contact)}.",
                        "properties",
                        Map.of(
                                "name", Map.of("type", "string"),
                                "units", STRING_LIST,
                                "ref", Map.of("type", "string"))));
        p.put("title", Map.of("type", "string", "description", "Job title."));
        p.put("role", Map.of("type", "string", "description", "Role or function."));
        p.put("birthday", Map.of("type", "string", "description", "yyyy-MM-dd, or --MM-dd without a year."));
        p.put("anniversary", Map.of("type", "string", "description", "yyyy-MM-dd."));
        p.put("urls", valueList("Web addresses."));
        p.put("impps", valueList("Messenger URIs (xmpp:…, sip:…)."));
        p.put("languages", Map.of("type", "array", "items", Map.of("type", "string"), "description", "e.g. de, en."));
        p.put("timezone", Map.of("type", "string", "description", "e.g. Europe/Berlin."));
        p.put("categories", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Labels."));
        p.put("note", Map.of("type", "string", "description", "Free text."));
        p.put("related", valueList("Relations: value = id of another contact or a URI; types e.g. colleague, spouse."));
        p.put(
                "members",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "kind group only: contact ids, or URIs like mailto:x@y for people without a card."));
        p.put("vanceUser", Map.of("type", "string", "description", "Login of a Vancetope user this contact is."));
        return p;
    }
}

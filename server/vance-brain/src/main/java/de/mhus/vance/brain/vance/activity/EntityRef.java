package de.mhus.vance.brain.vance.activity;

import org.jspecify.annotations.Nullable;

/**
 * Pointer to an entity an Activity-Log entry refers to. Lightweight —
 * Vance reads names/ids and uses them to format peer recaps; the
 * authoritative records live in their own services.
 */
public record EntityRef(
        Kind kind,
        @Nullable String id,
        @Nullable String name,
        @Nullable String label) {

    public enum Kind {
        PROJECT,
        SESSION,
        PROCESS,
        DOCUMENT,
        INBOX_ITEM,
        OTHER
    }

    public static EntityRef project(String name) {
        return new EntityRef(Kind.PROJECT, null, name, null);
    }

    public static EntityRef process(String id, @Nullable String name) {
        return new EntityRef(Kind.PROCESS, id, name, null);
    }

    public static EntityRef document(String id, @Nullable String path) {
        return new EntityRef(Kind.DOCUMENT, id, path, null);
    }

    public static EntityRef inboxItem(String id, @Nullable String title) {
        return new EntityRef(Kind.INBOX_ITEM, id, null, title);
    }
}

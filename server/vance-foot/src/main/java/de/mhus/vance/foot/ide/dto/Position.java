package de.mhus.vance.foot.ide.dto;

/** 0-based line/character position as delivered by the IDE plugin. */
public record Position(int line, int character) {
}

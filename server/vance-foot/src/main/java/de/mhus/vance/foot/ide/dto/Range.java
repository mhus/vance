package de.mhus.vance.foot.ide.dto;

/** 0-based half-open range as delivered by the IDE plugin. */
public record Range(Position start, Position end) {
}

import { HighlightStyle } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

/**
 * Syntax colours for {@link CodeEditor}.
 *
 * CodeMirror's `defaultHighlightStyle` hard-codes light-theme colours and
 * leaves `propertyName` / plain content unstyled — which is exactly what
 * YAML keys and JSON keys are, so those documents rendered as plain text
 * (and unreadably so in dark mode). This style maps every tag onto the
 * theme tokens from `style/tokens.css`, so it follows light/dark
 * automatically and never needs a second palette.
 */
export const vanceHighlightStyle = HighlightStyle.define([
  // Structure of the document.
  { tag: [t.heading1, t.heading2, t.heading3, t.heading4, t.heading5, t.heading6, t.heading],
    color: 'var(--v-accent-text)', fontWeight: '600' },
  { tag: t.strong, fontWeight: '700' },
  { tag: t.emphasis, fontStyle: 'italic' },
  { tag: t.strikethrough, textDecoration: 'line-through' },
  { tag: [t.link, t.url], color: 'var(--v-accent-text)', textDecoration: 'underline' },
  { tag: t.quote, color: 'var(--v-label-secondary)', fontStyle: 'italic' },
  { tag: t.monospace, color: 'var(--v-content-teal-fg)' },

  // Keys / names (YAML keys, JSON keys, object properties, attributes).
  { tag: [t.propertyName, t.attributeName], color: 'var(--v-accent-text)' },
  { tag: [t.definition(t.variableName), t.function(t.variableName), t.function(t.propertyName)],
    color: 'var(--v-accent-text)' },
  { tag: [t.variableName, t.name], color: 'var(--v-label)' },
  { tag: [t.typeName, t.className, t.namespace, t.tagName],
    color: 'var(--v-content-teal-fg)' },
  { tag: [t.labelName, t.macroName], color: 'var(--v-content-pink-fg)' },

  // Keywords and literals.
  { tag: [t.keyword, t.controlKeyword, t.definitionKeyword, t.moduleKeyword, t.operatorKeyword, t.modifier, t.self],
    color: 'var(--v-content-purple-fg)' },
  { tag: [t.atom, t.bool, t.null, t.number, t.integer, t.float, t.unit],
    color: 'var(--v-content-orange-fg)' },
  { tag: [t.string, t.special(t.string), t.character, t.inserted],
    color: 'var(--v-content-green-fg)' },
  { tag: [t.regexp, t.escape], color: 'var(--v-content-orange-fg)' },

  // Quiet parts: punctuation, operators, comments, meta lines (`---`).
  { tag: [t.operator, t.separator, t.punctuation, t.bracket, t.squareBracket, t.brace, t.paren, t.angleBracket],
    color: 'var(--v-label-secondary)' },
  { tag: [t.meta, t.documentMeta, t.annotation, t.processingInstruction, t.contentSeparator],
    color: 'var(--v-label-tertiary)' },
  { tag: [t.comment, t.lineComment, t.blockComment, t.docComment],
    color: 'var(--v-label-tertiary)', fontStyle: 'italic' },

  // Diff-ish and error states.
  { tag: t.deleted, color: 'var(--v-danger)' },
  { tag: t.invalid, color: 'var(--v-danger)' },
]);

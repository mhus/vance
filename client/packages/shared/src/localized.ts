/**
 * Resolution rule for the `Map<lang, text>` shape the brain uses for
 * anything a human reads but the server cannot pre-translate:
 * `FormFieldDto.label`/`help`, `FormChoiceDto.label`,
 * `ShareHandlerDto.label`.
 *
 * Order: the requested language, then English, then any non-blank entry,
 * then the empty string. The last two steps matter — a form authored in
 * one language must still render for a user in another, showing the text
 * that exists rather than a blank field label.
 *
 * Platform-neutral on purpose: it takes the language as an argument
 * instead of reaching for `useI18n()`, so mobile and web share the rule.
 */
export function pickLocalized(
  map: Record<string, string> | undefined | null,
  lang: string,
): string {
  if (!map) return '';
  const preferred = map[lang];
  if (preferred && preferred.trim()) return preferred;
  if (map.en && map.en.trim()) return map.en;
  for (const value of Object.values(map)) {
    if (value && value.trim()) return value;
  }
  return '';
}

/**
 * Strip the obvious Markdown tokens before reading a string aloud —
 * code fences, inline code marks, emphasis markers, link syntax,
 * headings. Reading raw asterisks and brackets is jarring; we don't
 * try to preserve every nuance. For richer rendering, plug in a
 * proper markdown-to-text strip later.
 *
 * Pure string transformation — no DOM, no storage, no platform
 * dependency. Safe to call from any client (Web TTS, Mobile
 * `expo-speech`, future server-side preview).
 */
export function stripMarkdown(text: string): string {
  return text
    // fenced code blocks → just the inner text
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/```[a-zA-Z0-9]*\n?|```/g, ''))
    // inline code
    .replace(/`([^`]+)`/g, '$1')
    // images and links — keep the alt / link text only
    .replace(/!?\[([^\]]+)\]\([^)]+\)/g, '$1')
    // headings
    .replace(/^#{1,6}\s+/gm, '')
    // emphasis markers
    .replace(/[*_~]+/g, '')
    // collapse whitespace
    .replace(/[ \t]+\n/g, '\n')
    .trim();
}

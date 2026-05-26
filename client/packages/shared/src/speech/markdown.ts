/**
 * @deprecated Use {@code markdownToSpeech} from
 * {@code ./markdownToSpeech} instead. The old stripper here keeps
 * fenced-code-block bodies in the output and the TTS engine reads
 * them out loud — which defeats the "speak-but-show" pattern voice
 * mode relies on (see specification/voice-mode.md §5). The new
 * `markdownToSpeech` is a faithful TS port of the Java
 * {@code MarkdownToSpeech} and replaces fences / tables with short
 * hints ("Code-Block mit N Zeilen").
 *
 * Kept as a thin wrapper around the new implementation so older
 * tests / call-sites don't break. Will be removed once nothing
 * imports this name.
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

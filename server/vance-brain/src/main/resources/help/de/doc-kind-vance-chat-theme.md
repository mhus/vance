# Chat-Theme

Eine CSS-Datei unter `_vance/chat-themes/<name>.css` — das Styling
des Chat-Transkripts für das Recipe mit `webTheme: <name>`. Der Name
ist gleichzeitig der Schlüssel im Recipe — nur Kleinbuchstaben,
Ziffern, Bindestriche.

## Aufbau

Plain CSS, geschichtet über das Default-Styling des Transkripts. Du
überschreibst, was du anders willst; alles Ungenannte bleibt. Die
Dark-Mode-Umschaltung der Shell greift auch hier — schreibe deine
Farben so, dass sie in beiden Modi funktionieren (oder überschreibe
die Dark-Klassen explizit).

## Verhalten

- **Fail-open:** invalides CSS fällt still auf `default.css` zurück —
  kein Validator, keine Fehlermeldung, nur fehlendes Styling. Im
  Zweifel im Browser gegenprüfen.
- Projekt überschreibt Mandant überschreibt gebündelte Themes:
  gleicher Name gewinnt von innen.

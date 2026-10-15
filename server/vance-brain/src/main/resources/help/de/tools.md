# Server-Tools

Tools, die der Brain selbst ausführt — MCP-Server, REST-APIs,
Mailkonten, Skripte. Links ein Projekt wählen, oder **_tenant** für die
Vorgaben des ganzen Mandanten. Ein Projekt-Tool mit gleichem Namen
überschreibt das des Mandanten.

## MCP-Server anbinden

1. **+** → **MCP-Server**: ein Name, die Server-URL (`https://…/mcp`)
   und wie er sich anmeldet.
   - **OAuth-Login** — Server wie Notion, Linear oder Atlassian, bei
     denen man sich anmeldet. Vancetope findet den Login-Server und
     registriert sich dort selbst; eine Client-ID braucht es nur für
     Server, die keine Selbstregistrierung erlauben.
   - **Keine** Anmeldung oder ein **Bearer-Token** für alles andere.
2. Das Tool öffnen und das Formular anpassen: Transport (HTTP oder ein
   lokaler Prozess), Anmeldung, TLS, Timeouts, Standard-Argumente.
   **JSON** zeigt die rohen Parameter, auch Schlüssel, die das Formular
   nicht kennt.
3. **Anmelden** neben dem Tool in der Liste. Der Browser geht zum Login
   des Anbieters und kommt hierher zurück. Ab dem nächsten Chat-Turn
   stehen die Tools des Servers zur Verfügung.
4. **Testen** listet die Tools des Servers, wie du sie siehst. Tools, die
   das Modell nicht nutzen soll, hier abschalten.

## Wessen Konto

Bei OAuth-Servern legt **Wessen Konto** fest, wer sich anmeldet:

- **Persönlich** — jeder meldet sich mit dem eigenen Konto an (hier oder
  unter *Verbundene Konten*). Wer nicht angemeldet ist, sieht die Tools
  des Servers nicht.
- **Eins pro Projekt** — ein Projekt-Admin meldet sich einmal an; alle im
  Projekt arbeiten mit diesem Konto.
- **Eins für den Mandanten** — ein Mandanten-Admin meldet sich einmal für
  alle an.

Bei einem geteilten Konto sieht der Anbieter nicht, welcher User eine
Aktion ausgelöst hat.

## Secrets

Tokens und Passwörter nicht ins Formular tippen — als Setting ablegen und
darauf verweisen, z. B. `{{secret:tenant:example.token}}`. OAuth-Tokens
werden verschlüsselt gespeichert und automatisch erneuert.

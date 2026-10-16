# Server-Tool

Ein Tool, das der Brain selbst ausführt — ein MCP-Server, eine REST-API,
ein Mailkonto, ein Skript. Das geöffnete Dokument *ist* das Tool: eine
Datei unter `_vance/server-tools/<name>.yaml`, der Dateiname ist der
Tool-Name. Ein Dokument anderswo ist ein Entwurf; das Banner oben sagt es.

## MCP-Server anbinden

1. **Typ** `mcp_server`, dann das Server-Formular: Transport (HTTP oder
   ein lokaler Prozess), die Server-URL (`https://…/mcp`) und wie er sich
   anmeldet.
   - **OAuth-Login** — Server wie Notion, Linear oder Atlassian, bei
     denen man sich anmeldet. Vancetope findet den Login-Server und
     registriert sich dort selbst; eine Client-ID braucht es nur für
     Server, die keine Selbstregistrierung erlauben.
   - **Keine** Anmeldung oder ein **Bearer-Token** für alles andere.
   **JSON** zeigt die rohen Parameter, auch Schlüssel, die das Formular
   nicht kennt.
2. **Aktiv** einschalten und **Anwenden**. Ein neues Tool startet
   abgeschaltet, damit ein halb konfigurierter Server nie beim Modell
   ankommt.
3. **Anmelden** im Block Anmeldung. Der Browser geht zum Login des
   Anbieters und kommt hierher zurück. Ab dem nächsten Chat-Turn stehen
   die Tools des Servers zur Verfügung.
4. **Testen** listet die Tools des Servers, wie du sie siehst. Tools, die
   das Modell nicht nutzen soll, abschalten und erneut anwenden.

Anmelden und Testen arbeiten mit dem **gespeicherten** Tool — Änderungen
vorher anwenden.

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

## Andere Typen

Alle anderen Typen nehmen ihre Parameter als JSON; das Parameter-Schema
des Typs steht unter dem Editor. **Pack** setzt Vorgaben für Tools, die
sich in viele Sub-Tools auffächern (REST-API, MCP-Server): verzögertes
Laden und abgeschaltete Sub-Tools. Über **Labels** sprechen Recipes eine
Gruppe von Tools an; der **Prompt-Hinweis** kommt in den System-Prompt
der Turns, in denen das Tool erreichbar ist.

## Secrets

Tokens und Passwörter nicht ins Formular tippen — als Setting ablegen und
darauf verweisen, z. B. `{{secret:tenant:example.token}}`. OAuth-Tokens
werden verschlüsselt gespeichert und automatisch erneuert.

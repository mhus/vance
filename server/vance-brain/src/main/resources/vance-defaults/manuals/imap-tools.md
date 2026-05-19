# IMAP mailbox tools

How to wire a read-only IMAP-mailbox tool — for inbox processing,
support-queue triage, mailing-list digest building, or whatever else
needs „read recent mail" as a tool surface. Auth lives in user- or
project-settings; the LLM never sees the credentials.

## Scope of an `imap_mailbox` pack

Three sub-tools per pack, all read-only:

| Tool | What |
|---|---|
| `<name>__list_folders` | Folder tree of the configured mailbox. No args. |
| `<name>__list_messages` | Header summaries (subject/from/to/date/seen). Filterable: folder, limit, unread_only, since (ISO-8601). |
| `<name>__get_message` | Full envelope + body for one message. Args: `messageRef` (folder index or Message-ID), optional folder. |

Body extraction is text-first: a multipart/alternative message yields
its `text/plain` part; HTML is the fallback. Attachments are ignored
(v1 — explicit attachment-fetch is a v2 feature once the file-blob
plumbing is settled).

Write operations (mark-read, move, delete) are **deliberately not in
v1**. The security model around AI-driven mailbox mutation needs more
thought (confirmation flow, undo, audit log). For now this pack is a
read surface only.

## Per-user vs. per-project credentials

Common case: **per-project**. Project owns the shared mailbox
(support@, sales@, monitoring@). Scheduler-driven polling works
naturally — the scheduler's project-scoped context resolves the
credentials.

Per-user is fine for personal inbox tools but breaks down for
schedulers (they have no user identity) and for delegated work
(a project task delegated to another user wouldn't see the original
user's credentials).

Use the resolver-syntax prefix to choose:

```yaml
# Shared project mailbox
parameters:
  host:     "{{secret:project:imap.host}}"
  user:     "{{secret:project:imap.user}}"
  password: "{{secret:project:imap.password}}"

# Personal mailbox (chat-only, won't work in schedulers)
parameters:
  host:     "{{secret:user:imap.host}}"
  user:     "{{secret:user:imap.user}}"
  password: "{{secret:user:imap.password}}"
```

## Minimal config

```yaml
type: imap_mailbox
description: "Sales mailbox (sales@example.com) — read recent threads."
labels: [mail, sales]
primary: false
defaultDeferred: false
parameters:
  host:          "imap.example.com"
  port:          993                 # default 993 (implicit-TLS)
  tls:           true                # default true
  user:          "{{secret:project:sales.imap.user}}"
  password:      "{{secret:project:sales.imap.password}}"
  defaultFolder: "INBOX"
  timeoutSeconds: 30
promptHint: |
  ## Sales mailbox
  Read-only IMAP into the sales@ inbox. Use `list_messages` with
  `unread_only: true` and `since` to find recent unprocessed threads.
  `get_message` returns the plain-text body; attachments aren't
  exposed in v1.
```

## STARTTLS vs implicit-TLS

- **Implicit-TLS (port 993, default)** — TLS from the first byte.
  `tls: true`, `starttls: false`, `port: 993` (the default).
- **STARTTLS (port 143)** — plain connect, upgrade-to-TLS via the
  `STARTTLS` command. `tls: false`, `starttls: true`, `port: 143`.
  Required by some on-prem servers.
- **Plain (port 143, no TLS)** — only for localhost test mailboxes.
  `tls: false`, `starttls: false`. Never in production.

The Atlassian / Google / Outlook hosted servers all support
implicit-TLS on 993; that's the default and almost always right.

## Auth modes

V1 supports plain user+password (the dominant pattern). XOAUTH2 is
not in v1 — for Gmail/Outlook, use an **app password** (set up by the
mailbox owner in their account security UI) and store it as a
project-PASSWORD setting. Same shape as a regular password; the user
just doesn't reuse their login credential.

OAuth XOAUTH2 lands as a v2 feature once the OAuth-provider stack
gets a generic "service account" extension.

## Diagnostics

Common failure modes:

| Error | Likely cause |
|---|---|
| `IMAP connect/close failed: AuthenticationFailedException` | user/password wrong, or app-password needed for an account with 2FA |
| `Folder not found: <name>` | Folder names are case-sensitive on most servers; `Sent` vs `SENT` matters |
| `IMAP operation failed: BYE` | Server rate-limit or session-timeout; reduce poll frequency |
| Empty `body` field | Multipart with attachment but no text part — current v1 limitation |

## Recipe: scheduled polling

A scheduler-job that runs every 10 min on a project, calling
`list_messages` with `unread_only: true` + `since: <last-poll>`, then
spawning a think-process per new thread. Credentials live in
project-settings; the scheduler has the project's
ToolInvocationContext, no user-impersonation needed. See
`scheduler-jobs.md` (TBD) for the wiring.

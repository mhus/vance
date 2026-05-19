# SMTP sender tools

How to wire an outbound-email tool — confirmation mails, ticket
acknowledgements, scheduled digests, „forward this to the team".
Auth lives in user- or project-settings; the LLM only ever sees
recipient/subject/body, never the SMTP credentials.

## Scope of an `smtp_sender` pack

One sub-tool per pack: `<name>__send_message`. Single operation
because SMTP is fundamentally one verb (you send mail).

| Arg | Required | Notes |
|---|---|---|
| `to` | yes | Array of addresses |
| `subject` | yes | UTF-8 |
| `body` | yes | Plain-text body |
| `cc` | no | Array |
| `bcc` | no | Array |
| `html` | no | Sends as multipart/alternative when set (plain `body` + this HTML) |
| `from` | no | Overrides the pack's default From: |
| `replyTo` | no | Single address |

Attachments are **not in v1** — the file-blob handoff between
workspace/documents and the SMTP layer needs explicit design. Comes
in v2.

## Per-user vs. per-project credentials

For SMTP **per-project is almost always right**: shared sender
address (noreply@, sales@), reusable across all users with project
access, works from schedulers. Per-user only makes sense for
„send-as-Alice"-tools where the personal identity matters — uncommon
in business contexts.

Resolver-syntax prefix:

```yaml
parameters:
  host:     "smtp.example.com"
  user:     "{{secret:project:smtp.user}}"
  password: "{{secret:project:smtp.password}}"
  from:     "noreply@example.com"
```

## Minimal config

```yaml
type: smtp_sender
description: "Project notifications — send mail from noreply@example.com."
labels: [mail, notifications]
primary: false                       # discovery-only by default — sending mail is opt-in
defaultDeferred: false
parameters:
  host:           "smtp.example.com"
  port:           587                # default 587 (STARTTLS) / 465 (TLS) / 25 (plain)
  starttls:       true               # default true when tls=false
  user:           "{{secret:project:smtp.user}}"
  password:       "{{secret:project:smtp.password}}"
  from:           "noreply@example.com"
  timeoutSeconds: 30
promptHint: |
  ## Notifications
  Send transactional email from `noreply@example.com`. Use a clear
  Subject (max 80 chars) and a plain-text body. HTML is supported via
  the `html` argument but plain-text remains required as the
  fallback for non-rich clients.
```

## Port + TLS guide

| Port | tls | starttls | When to use |
|---|---|---|---|
| 587 | false | true (default) | Modern SMTP submission — most providers (Gmail, Office365, AWS SES) |
| 465 | true | false | Implicit-TLS submission — also widespread |
| 25 | false | false | Server-to-server relay; almost never for client submission. Many ISPs block outbound :25. |

If you're not sure, **587 + STARTTLS** is the right default.

## Auth modes

V1: plain user+password. App-passwords (Gmail with 2FA, Outlook with
MFA) work transparently — store the app-password as the
project-PASSWORD setting.

XOAUTH2 not in v1 — for Gmail with OAuth, the cleanest interim is
an app-password. Generic OAuth XOAUTH2 lands together with the IMAP
counterpart in a future iteration.

## Why `primary: false` is the default

Sending mail is a write/side-effect tool. Conversational engines
(Eddie) defer `@side-effect` to the discovery block by default — so
the model has to deliberately call `find_tools(query="send")` and
then `invoke_tool` to use it. That's intentional: the model doesn't
accidentally send mail just because it's chatty.

For notification-recipes that **should** send mail directly (e.g.
"daily-digest" worker), the recipe overrides with `allowedToolsAdd:
["@mail"]` to promote the pack to primary for that turn.

## Diagnostics

| Error | Likely cause |
|---|---|
| `SMTP send failed: AuthenticationFailedException` | wrong credentials, or 2FA without app-password |
| `... 550-5.7.1 ... bulk sender ... Gmail` | Gmail anti-spam rejected the send — usually needs SPF/DKIM on the sender domain |
| `... 530 5.7.0 Authentication required` | provider expects STARTTLS but you set `tls=true` (or vice versa) |
| `... 421 too many connections` | upstream rate-limit — schedule polling more conservatively |
| `'from' address — set parameters.from on the pack` | no default From: configured and the call didn't pass one either |

## Recipe: send a summary mail

```yaml
# Recipe-side: a daily-digest recipe that uses the smtp pack
profiles:
  default:
    allowedToolsAdd:
      - "@mail"                  # promote the smtp pack to primary
```

```python
# Conceptual flow inside the engine turn:
result = invoke("notify_smtp__send_message", {
    "to": ["team@example.com"],
    "subject": "Daily Vance digest 2026-05-19",
    "body": "...plain text summary...",
    "html": "<p>...rich version...</p>"
})
# result: { messageId, from, to, subject }
```

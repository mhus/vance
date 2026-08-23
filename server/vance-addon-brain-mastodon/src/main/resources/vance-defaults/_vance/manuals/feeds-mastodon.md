---
triggers: mastodon, fediverse, toot, hashtag stream, mastodon feed, mastodon quelle, mastodon source, public timeline, tröt, föderiert
summary: The selector grammar of a Mastodon feed source (hashtag:<tag>, public:all|local|remote) and why the largest instance serves hashtag streams but refuses the public one.
requires-tools: feed_sources
---
# Mastodon as a feed source

A Mastodon source is one **server**. Several servers in one feed make a federated stream; the merge
mixes them by the time entries appeared. Configuration is the operator's (see
`manual_read('feeds-sources')` for the keys and who may write them) — this manual is about the half
you cannot read off `feed_sources()`: **which streams exist and how to name them.**

## The selector grammar

`feed_sources()` reports `selectorMode: FREEFORM` and `selectorKinds: [hashtag, public]`. That says
the selector is typed, not chosen from a list. It does not say what to type. Two forms, both
prefixed:

| Selector | Stream |
|---|---|
| `hashtag:<tag>` | everything on that server tagged `<tag>` — e.g. `hashtag:opensource` |
| `public:all` | the whole public timeline, local posts and federated ones |
| `public:local` | only posts written on that server |
| `public:remote` | only posts that arrived from elsewhere |

Rules that are enforced, so worth knowing before you type:

- **No `#`.** Write `hashtag:linux`, not `hashtag:#linux` and not `#linux`.
- **A tag is letters, digits and underscore** — any script (`hashtag:Grüße` is fine), but no spaces
  and at least one letter. `hashtag:2026` is refused because Mastodon does not index digit-only tags.
- **The prefix is not optional.** A bare `news` is refused rather than guessed: it is a plausible
  hashtag *and* a plausible slip for `public:local`, and guessing would show a stranger's timeline
  without saying so.
- **`account:` does not exist yet.** Asking for one gets you a refusal naming it, not an empty
  stream.

A refused selector is reported — as a rejection when a feed configuration is saved, and as a note on
the page when it arrives in a one-off request. So a mistyped stream tells you what is wrong; it does
not come back empty.

## The trap: access depends on the endpoint, not the server

The two stream families are gated separately, and the largest server is the awkward case:

| Server | `public:*` | `hashtag:*` |
|---|---|---|
| mastodon.social, mastodon.online | refused (HTTP 422) | works |
| chaos.social, infosec.exchange | refused | refused |
| fosstodon.org, hachyderm.io, mstdn.social, troet.cafe | works | works |

So **a source can be half usable**, and `feed_sources()` cannot tell you which half — it reports the
grammar, not the permission. Two consequences for what you say to a user:

- If a `public:` stream comes back with a note about a missing token, **suggest a `hashtag:` stream
  on the same server** before suggesting anything else. It is the cheaper fix and it usually works.
- The real fix is an **app token** for that server in the source document's `apiKey:` field. An app token
  is not a person and no account is logged in — but writing it is operator work, so say what to set
  and do not claim to have set it.

## Two things about the entries

- **`publishedAt` is when the entry appeared in that timeline, not when its author wrote it.** For a
  bridged account (Flipboard, brid.gy, RSS bridges) those differ, sometimes by more than a day. When
  they differ by more than a minute, the author's own timestamp travels as `extras.authoredAt` — use
  it when the age of the *content* matters, and `publishedAt` when the order of the *stream* does.
- **A toot has no title.** What you get as `title` is derived: the content warning if the author
  wrote one, else the opening of the text, else the attachment, else the handle. Do not quote it as
  if it were a headline the author chose; the body is the post.

## Unwanted content in a federated stream

`public:all` and `public:remote` pull in posts from foreign instances, and the moderation of *this*
server does not reach them. Explicit material turns up that way. Three levers, in order of how well
they work:

1. **The selector.** `public:local` on a moderated server, or a `hashtag:` stream, never pulls foreign
   instances in at all. This is the only lever that also works against content nobody has labelled
   and nobody has put on a list yet — and it needs no configuration beyond the selector itself.
2. **`hideSensitive: true`** in the source document honours Mastodon's own `sensitive` flag: author
   and instance label their own posts, so nothing has to be guessed. Set by default in the template.
3. **`blockedHosts: a.example,b.example`** in the source document drops entries whose address is on
   the list, subdomains included.

**None of this is a parental control**, and the second and third are the weaker two on purpose. A
blocklist blocks what is on it; new instances keep appearing. A `sensitive` flag is only as good as
the author who sets it. If unwanted material must stay out reliably, the answer is (1) — the other two
narrow what gets through, they do not guarantee it.

Where the fields live and how they behave for other protocols:
`specification/public/centauri-service.md`.

## What this source will not do

No posting, no boosting, no favouriting, no reporting — not "not implemented" but out of scope by
design: Centauri reads streams and never acts under anyone's name. There is no tool for it and
asking for one has no answer other than this paragraph.

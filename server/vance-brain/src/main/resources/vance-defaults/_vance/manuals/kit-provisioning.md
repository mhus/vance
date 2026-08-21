---
triggers: kit provisioning, provisioning.yaml, provision kits, install kits automatically, kits from our server, kit soll liste, welche kits soll das projekt haben, ode kit, kit source ode, company kit, kit host, authority notify update manage, kit.token, why was this kit not installed, kit did not update, kit has changed at its source, kit provisioning failed, provisioning entry, kits automatisch installieren, projekt-defaults ausrollen
summary: How a project gets its kits pulled in automatically from a host that serves them — the _vance/kits/provisioning.yaml declaration, the three authority levels, the params channel, and why a change was or was not applied. Read this before telling anyone that kits have to be installed by hand, and before diagnosing "the kit did not update" as a bug.
---
# Kit provisioning — a source says which kits belong here

A project can declare where its kits come from, and then they arrive without
anyone running `kit install`. Used for company defaults (project structure,
house recipes) and for applications that ship the kit describing themselves.

## The declaration

One document in the project, `_vance/kits/provisioning.yaml`:

```yaml
provisioning:
  - type: ode                                # the mechanism; ode = an application that serves its own kit
    url: https://crm.intern.example          # the application's base url, not an endpoint path
    token: "{{secret:project:kit.token.crm}}"  # a reference, never the value
    authority: notify                        # notify | update | manage
    params:                                  # optional; what you want from that source
      lang: de
      modules: [crm, invoicing]
```

**You cannot write this file.** `_vance/**` requires ADMIN, and `kit.token.*` is
on the agent write deny-list. Both are deliberate: whoever can write them decides
where a project gets its tool definitions from. Tell the operator what to put
there; do not try to write it and do not report the refusal as a bug.

**The token is a reference.** Put the value in a `hidden`/`password` setting
(`kit.token.<id>`) and reference it. A literal token in this document would sit
in plaintext in the database and travel into exports.

**`params` are not secret-resolved.** They are sent to the far end. A
`{{secret:…}}` there would hand a vault value to a third party by convenience.

## Authority — what may happen unattended

| Level | The source may |
|---|---|
| `notify` (default) | nothing unattended; divergence becomes an inbox item |
| `update` | refresh kits that are already installed here |
| `manage` | additionally pull in kits that are not here yet |

Graded rather than a flag because the two are different sizes: a new revision
changes the *content* of what is installed, a new kit changes the project's
*tool surface*. **Uninstalling is in no level** — removing a line removes
nothing.

## When it runs

1. **Project start** — a new or long-dormant project gets its kits.
2. **The document changes** — someone added a source and expects it to arrive.
3. **Every four hours** — only *checks*, and only reports.

The first two react to a change on this side and may install. The third reacts
to a change at the host and reports unless the entry granted more.

## Diagnosing "nothing happened"

Work down this list before calling anything broken:

- **`authority` too low.** A missing kit at `notify` or `update` is *withheld*,
  not failed. At `notify` a changed kit is reported, not refreshed.
- **An inbox item is already open** for that kit — a second one is suppressed on
  purpose. Answer or archive the first.
- **The host is unreachable.** That is logged, not reported: an outage is not a
  divergence.
- **The source states no revision**, or the kit was installed by hand. Then
  change detection is off for it — nothing is guessed.
- **A reserved setting was skipped.** A kit cannot set `ai.provider.*` or
  `vault.*`; the install result carries a warning saying so.
- **An existing credential was kept.** A delivered `password` value is written
  once and then never replaced, even under an `overwrite` policy — a run that
  resets a rotated key would be an outage.

`params` count as a change: editing them makes the next run refetch, even when
the source's revision stayed the same.

## What the host is told

Instance label, tenant, project name, the id of any previous installation, the
url it was reached at, and the `params`. **No person** — no user id, no display
name. If someone asks to include who triggered it, say the field does not exist
by design.

## Writing a host

The application side is the `vance-ode-kit` module: implement `KitSource`,
publish it as a bean, get two endpoints. A directory of files is three lines
(`StaticKitSource.fromClasspath`); assembling per project is the same interface.
Placeholders like `{{ accessUrl }}` are listed under `render:` in the kit's
`kit.yaml` and filled in on arrival — the host must not substitute them itself.

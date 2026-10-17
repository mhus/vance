---
name: contacts
summary: The address book — people, organisations and groups, searched and edited through the contact_* tools.
triggers: user mentions a person's e-mail, phone number or address, asks "who is …", "add … to my contacts", "Kontakt anlegen", "Adressbuch", "Telefonnummer von …", wants a distribution list / group of people, imports or exports a .vcf / vCard file, or wants a contact picture / Gravatar
---

# Contacts

The address book holds **cards**: a person (`individual`), a company
(`org`) or a named set of people (`group`). The fields follow vCard 4.0 —
names, e-mails, phones, addresses, organisation, title, birthday, URLs,
messengers, languages, categories, a note, relations.

**Always use the `contact_*` tools.** The cards are stored as files under
`_vance/contacts/`, but never search or edit those with `doc_*` — the tools
return typed results, enforce the scope rules and keep pictures in sync.

## Three scopes

| scope | lives in | who sees it |
|---|---|---|
| `user` | the user's own hub | only the user — **the default for new cards** |
| `project` | the current project | everyone in the project |
| `tenant` | `_tenant` | everyone in the tenant (writing needs tenant admin) |

- Searching and reading look at **all three** at once; every hit tells its
  `scope`. Pass that scope to `contact_update`, `contact_delete` and friends —
  a write never falls through to another scope.
- Create in `project` or `tenant` only when the user wants to share the
  contact. To share an existing one: `contact_move(id, to: "project")`.

## Working with cards

- **Find first**: `contact_search(query: "anna berger")` before creating —
  avoid duplicates. Free text matches names, mails, phone numbers in any
  notation, organisation, categories and notes.
- Read everything: `contact_get(id)`.
- Create: `contact_create(fullName, emails: [{value, types: [work]}], phones: [...], org: {name}, ...)`.
  Write phone numbers international (`+49 30 1234567`).
- Change: `contact_update(id, scope, …)`. A passed field **replaces** the
  stored one, `""`/`[]` clears it. To add or drop a single phone, mail or
  category use `addPhones`/`removePhones`, `addEmails`/`removeEmails`,
  `addCategories`/`removeCategories` — do not resend the whole list.
- Delete: `contact_delete(id, scope)` (goes to the trash).

## Groups

A group (`kind: group`) has `members`: contact ids, or `mailto:` URIs for
people without a card. Manage them with `contact_group_members(id, add, remove)`.
A group may only hold contacts of **its own or a broader scope** — a tenant
group only tenant contacts, a project group project and tenant contacts — so
nobody meets a member they cannot see. Groups do not nest.

## Pictures

- `contact_photo_set(id, photoPath)` takes an image document of the project;
  every picture is cropped to a square and scaled down.
- `contact_photo_gravatar(id)` fetches the public Gravatar avatar of the
  contact's e-mail. It asks an outside service — use it when the user wants a
  picture, not automatically.
- **Never invent a picture URL.** There is no URL parameter on purpose.

## vCard files

- `contact_import_vcf(path)` imports a `.vcf` document (Google, Apple,
  Outlook; vCard 2.1/3.0/4.0) with pictures. Re-importing the same file
  updates instead of duplicating.
- `contact_export_vcf(path, ids | group | category | scope)` writes vCard 4.0.

## Showing a contact in a document

A document of kind `contact` renders as a card with picture. It either holds
its own card (`contact:` block) or points to the address book:

```yaml
$meta:
  kind: contact
ref:
  uuid: <contact id>
  scope: tenant    # optional
```

## Contact boards

A **contact board** (`kind: contact-board`) is a document with pinned
contacts for one topic — "who is who" on a project or a deal. Each pin is a
reference to an address-book card plus the board's own **heading** (`label`)
and **Markdown note**. The card always shows the current address-book data.

- Read: `contact_board_get(path)`. Create: `contact_board_create(path, title)`.
- Pin: `contact_board_pin(path, id, label?, note?, position?)` — find the id
  with `contact_search` first.
- Change heading, note or position: `contact_board_update(path, id, …)`; the
  note is replaced whole, so read it first when adding to it.
- Remove: `contact_board_unpin(path, id)` — the contact stays in the address book.
- A private (`user`) contact on a project board shows only heading and note to
  the other readers — the tool says so; move the contact to share it.

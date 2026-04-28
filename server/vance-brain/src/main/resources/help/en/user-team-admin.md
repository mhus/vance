# User & team administration

Manage the people who can log into the tenant and the team groupings
that drive feature visibility (team-inbox, project access, …).

## Authorisation

Any authenticated user inside the tenant can read and write user and
team records. Cross-tenant probing is blocked by the brain's access
filter — a JWT for tenant `acme` cannot reach `/brain/foo/...`.

Two self-protect rules are enforced on user records:

- A user cannot **delete** their own account.
- A user cannot set their own status to **DISABLED**.

Both prevent accidental lock-outs. Other admins in the tenant can
still do it on someone else's account.

A finer role-based authorisation (e.g. tenant-admin vs. plain user)
is not in v1 — when it lands, the access filter and these endpoints
will tighten without the editor needing to change.

## Users

### Fields

- **`name`** *(immutable)* — login name. Lower-case alphanumerics
  with optional `.`, `-`, `_`. Set at creation; rename = create new
  + delete old.
- **`title`** — display name shown in selectors and chat headers.
- **`email`** — informational; used for future email integrations.
- **`status`**:
  - `ACTIVE` — normal, can log in.
  - `DISABLED` — admin-disabled. Login refuses; existing tokens
    expire on next refresh.
  - `PENDING` — created but awaiting something (e.g. email
    verification). Cannot log in.
- **`password`** *(write-only)* — set at creation or via the
  separate "Set password" action. Plaintext is sent over HTTPS
  and hashed server-side; the hash never leaves the server.

### Common flows

- **Create**: choose a name, fill title/email, optionally set a
  password (skip → user is created passwordless and cannot log in
  until you set one).
- **Reset password**: open the user, click *Set password*, type a
  new plaintext password.
- **Disable temporarily**: set status to `DISABLED`. To re-enable,
  switch back to `ACTIVE`.
- **Delete**: hard delete. Memberships in teams are not auto-cleaned
  — remove the user from any teams first if you care about
  referential cleanliness.

## Teams

### Fields

- **`name`** *(immutable)* — kebab-case identifier inside the
  tenant.
- **`title`** — display label.
- **`enabled`** — whether the team is currently active. Disabled
  teams stop showing in the team-inbox switcher.
- **`members`** — list of usernames (`UserDocument.name`) inside the
  same tenant. The editor offers add / remove against the current
  user list.

### What teams drive

- **Team inbox** — items assigned to one team member appear in the
  team's combined inbox view for everyone else in the team.
- **Project membership** — projects can list `teamIds`; everyone in
  those teams sees the project in the selector. (See the Scopes
  editor for project-side configuration.)

### Create

- Choose a name, optional title, pick initial members.

### Update / delete

- Add or remove members on the team's detail form.
- Delete is hard — the team document is removed; the project's
  `teamIds` list is *not* auto-cleaned. If a project still
  references a deleted team, the entry there resolves to nothing
  but does not break.

## What this view is not

- Not a place to manage **roles or permissions** — the role concept
  doesn't exist yet.
- Not a place to manage **identity providers / SSO** — that lives
  elsewhere when it arrives.
- Not a place to **invite** users — there is no email flow yet;
  admins create the account and hand the password over by other
  channels.

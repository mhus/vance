---
triggers: encrypt, age, verschlüssele, verschlüsseln, age-encrypted, public key, age1, secret document, geheimes dokument, doc_encrypt
summary: Encrypt documents with age via doc_encrypt — plaintext in, armored .age out, decrypted only by the listed recipients. Encrypted documents are unreadable to every tool; the user unlocks them in the web UI.
---

# Age Encryption — `doc_encrypt` and the unreadable documents

Some content must not live in plaintext on the server: personal notes,
credentials drafts, anything the user labels sensitive. Vance supports
age-encrypted documents for exactly that — armored ciphertext that the
server stores but never understands.

## The rule that governs everything

**The private key never leaves the user's hands.** It exists only in the
user's browser session (web UI) or their identity file. Consequences:

- You can **encrypt** — `doc_encrypt` works with public keys.
- You **cannot read or edit** an encrypted document. `doc_read` and friends
  refuse with a named message. That is not an obstacle to work around —
  it is the feature. Ask the user to open or decrypt it in the web UI.
- **Never ask for a passphrase.** A passphrase typed into the chat would
  end up in the conversation history and session memory — broken secret.
  Public keys (`age1...`) are safe to share; passphrases are not.

## Where the recipient comes from

`doc_encrypt` needs at least one **recipient** — an age public key
(`age1...`). There is no user directory resolving names to keys in v1, so:

> Ask the user: "Please paste your age public key (starts with `age1` —
> it is safe to share)." Then pass it through.

If the user has no key yet, the web UI generates one (Actions → Encrypt…
→ Generate a new key) — or the user runs `age-keygen` locally.

## The tool

```
doc_encrypt(fromPath, recipients)              — encrypt an existing plaintext document
doc_encrypt(content, toPath, recipients)       — encrypt generated text directly
```

- `toPath` defaults to `<fromPath>.age`; a missing `.age` suffix is appended.
- The **source stays untouched**; the target is **never overwritten** — you
  cannot review what you would destroy, so the tool refuses instead.
- Every listed recipient can decrypt; nobody else (including the server).
- The result is a normal document: it appears in the tree, can be renamed,
  moved, versioned, starred — only its *content* is opaque.

## What NOT to do

- Do not "helpfully" store a sensitive plaintext as a normal document
  when encryption fails — surface the error, let the user decide.
- Do not rename `.age` documents' inner type hint (`bericht.md.age` keeps
  the `md`); the extension pair is how the web UI picks the editor after
  decryption.
- Do not treat the ciphertext in `doc_info`/listings as content to quote
  — it is noise, not text.

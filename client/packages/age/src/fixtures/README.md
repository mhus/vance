# age Interop Fixtures

These fixtures were produced with the **reference Go `age` CLI** (homebrew
`age`, v1 format). They exist to prove that the Vance client decrypts
real-world, externally produced armored files — not just files written by
`age-encryption` itself. `markdown-source.md` is the committed plaintext that
`markdown-x25519.age` must decrypt to.

**Passphrase** of `hello-passphrase.age`: `vance-fixture-passphrase-2026`
(test data only — it guards nothing).

**`test-identity.txt`** is a throw-away `age-keygen` pair. Its identity decrypts
the two `*-x25519.age` fixtures.

## Regenerating

```sh
cd repos/vance/client/packages/age/src/fixtures

# One-time: the throw-away identity (changes all *-x25519 fixtures)
age-keygen -o test-identity.txt
RECIPIENT=$(grep -o 'age1[a-z0-9]*' test-identity.txt | head -1)

# X25519 fixtures from committed plaintexts
printf 'Hello, age fixture!\n' | age -a -r "$RECIPIENT" > hello-x25519.age
age -a -r "$RECIPIENT" markdown-source.md > markdown-x25519.age

# Passphrase fixture (the CLI prompts on the tty — script it)
printf 'Passphrase fixture for Vance — decrypt with the passphrase from the README.\n' \
  > /tmp/hello-passphrase.txt
expect -c '
set timeout 20
spawn age -a -p -o hello-passphrase.age /tmp/hello-passphrase.txt
expect -re "passphrase"
send "vance-fixture-passphrase-2026\r"
expect -re "passphrase"
send "vance-fixture-passphrase-2026\r"
expect eof
'
```

Cross-check in the other direction (our encryption must be readable by the
reference CLI):

```sh
# from a test that just wrote ./roundtrip.age with the typage Encrypter:
age -d -i test-identity.txt roundtrip.age
```

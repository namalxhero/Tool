# NXA Archiver

A zArchiver-style Android app that only speaks the **.nxa** format:
AES-256-GCM encryption + XZ/LZMA2 compression, with an optional
auto-generated `manifest.json` when you bundle a set of tools
(for packaging your own CTF/pentest scripts).

## Build (no Android Studio needed — GitHub Actions)

1. Push this folder to a new GitHub repo (GitHub mobile app or git in Termux).
2. Actions tab → the `Build APK` workflow runs automatically on push to `main`
   (or trigger manually with "Run workflow").
3. Download `nxa-archiver-debug` from the finished run's **Artifacts** —
   that's your installable APK.

## Editing on-device

Open this folder in **Acode**. The two files that matter:
- `app/src/main/java/com/nipuna/nxaarchiver/NxaFormat.kt` — the format itself
  (crypto, compression, packing)
- `app/src/main/java/com/nipuna/nxaarchiver/MainActivity.kt` — the UI

## Notes / honest limitations

- The container (how multiple files are packed before compression) is a
  simple custom length-prefixed blob, **not** POSIX tar — so archives made
  by this app are not byte-compatible with the earlier `nxa.py` Python
  prototype (which used `tarfile`). Both share the same outer
  magic/flags/crypto header shape, so aligning them later is a small job
  if you want cross-device compatibility — just say the word.
- Key derivation here is PBKDF2-HmacSHA256 (200k iterations), not scrypt
  like the Python version — Android has PBKDF2 built in with zero extra
  native dependencies, which matters since you're building headless via CI.
- "Select files" currently picks individual files only (not whole folders)
  to keep the Storage Access Framework code simple. Folder recursion can
  be added on top if you need it for bigger tool packs.

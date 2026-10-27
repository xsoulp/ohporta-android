APT Access for Android 11 and newer, created by Luis Pinto - 2026.

- **Fingerprint APK:** confirms door operations with a strong biometric, with device PIN, pattern, or password as a fallback.
- **PIN APK:** confirms door operations with the device PIN, pattern, or password; no fingerprint prompt.
- English and European Portuguese, selected from the phone's language.
- A shorter building image keeps the home controls and authorship visible on the Pixel 8a.
- No server address is built in. Configure your own compatible backend and token in Settings.
- Recent history shows up to five rows, with scrolling within the list. Includes user administration and weekly access schedules.

See the repository README for setup and `docs/BACKEND.md` for the API contract. SHA-256 checksums are included alongside the APKs.

These are sideload APKs using Android debug signing. Both variants in this release share the same signing key and application ID, so one replaces the other. A different signing key from a previous installation requires uninstalling that installation first, which removes its saved settings. The release assets use the existing project signing key so compatible installed versions can be updated without removing saved settings.

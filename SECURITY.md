# Security Policy

## Supported code

Security fixes target the latest release and the current `main` branch.

## Reporting

Do not publish secrets, private keys, precise private location histories, or other
sensitive material in a public issue. If GitHub private vulnerability reporting is
available for this repository, use it. Otherwise open a minimal public issue asking
for a private contact channel without including exploit details or sensitive data.

## Android signing-key reset

Development builds up to and including 0.4.0 were signed with a debug keystore that
was accidentally committed to the public repository. That certificate must be
considered compromised and must never be used to sign official releases.

The repository now rejects tracked keystore files and the release workflow requires
a new signing key supplied only through protected GitHub Actions secrets.

Because Android normally requires updates to carry the same signing certificate,
users of those old development builds may need to:

1. Export their Roamglyph history.
2. Uninstall the old development build.
3. Install the first official securely signed release.
4. Import the exported history.

Never send or commit the new private release key.

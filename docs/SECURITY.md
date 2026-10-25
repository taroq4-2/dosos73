# Security review

Threat model: untrusted URL/share text, remote metadata, remote file contents and unreliable storage providers. No local network access is intended. Only public HTTP/HTTPS direct URLs and HTTPS extractor URLs are accepted. Credentials in URLs, arbitrary ports, local DNS answers and common reserved ranges are rejected. Redirects are bounded; HTTPS downgrade is rejected for direct requests. Native HTTPS requests use public-only CONNECT tunnels authenticated with random per-session credentials. Handshakes have bounded size and duration.

No shell command string is constructed from user input. Native requests use structured wrapper options, fixed output paths and allowlisted format IDs. No cookies, credentials, DRM bypass, Accessibility, overlays, all-files permission or background clipboard monitoring.

Cleartext is enabled to support explicitly supplied public HTTP files and the loopback proxy. Do not transmit secrets in HTTP URLs. No trust-all TLS verifier is installed. Media downloads have no fixed size cap. Metadata parsing remains limited to 4 MiB / 1000 formats; these are metadata protections, not video-size limits.

## Remaining hardening and verification work

- Wrapper stdout buffering and metadata file production occur upstream before application size validation; malicious extractor responses may still exhaust resources.
- Confirm that every extractor/helper and FFmpeg request honors the configured proxy; current proxy code alone is not proof of full native isolation.
- Native cancellation race between process registration and cancellation requires stress tests.
- Native/transitive advisory scan, exact binary license inventory and 16 KB verification are pending.
- SAF partial documents and MediaStore pending rows can survive process death; transaction recovery needs a durable journal.
- Android service lifecycle and OS stop behavior require tests beyond normal cancellation.

These limitations remain documented for release 1.0.0. The release is not security certified or fully MASVS compliant. No raw URLs or native error strings are logged by first-party code.

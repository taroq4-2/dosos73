# Security model and verification scope

Untrusted inputs include shared URLs, DNS answers, redirects, headers, filenames, extractor metadata, remote bytes and unreliable storage providers. Direct transport defaults to HTTPS; public HTTP requires explicit opt-in. Credential-bearing URLs, arbitrary ports, ambiguous numeric addresses and non-public targets are rejected before connection. Redirects retain target checks and reject HTTPS downgrade.

Native HTTPS uses a randomly authenticated loopback CONNECT proxy. Every tunnel connects to a checked public IP; TLS remains end-to-end. Headers have an absolute deadline and origin inactivity is bounded. No trust-all verifier, cookie/login import or DRM bypass is installed. Native requests use structured arguments and fixed output paths rather than a user-built shell command.

Direct append requires a matching validated representation, range and strong validator. Conflicting encoding/length/range/ETag headers are rejected. Media files have no fixed application size ceiling; metadata has separate allocation guards. Storage reserve calculations use Long counters and checked arithmetic.

Private queue, failure, batch, publication and timeline records use device-bound AES-GCM. Only the two journals that historically existed as plaintext can migrate once, with authenticated pending/sealed markers bound to the filename. Established records reject plaintext replacement. Initial migration and simultaneous deletion/replay of both private data and marker are local trust boundaries, not hardware antirollback protection. Reads and encoded writes are bounded.

Publication verifies bytes and records a plan before changing destinations. Library rename recovery preserves evidence and refuses ambiguous identities. Filename/path policies exclude separators, controls and dangerous path components. Debug fault providers/hooks are excluded from minified Release. Backup is disabled; the app requests no all-files permission, accessibility service or clipboard monitor.

## Evidence and limits

See [the30-item audit](audit/REVIEW.md), [review dispositions](launch/REVIEW_DISPOSITIONS.md) and [verification](VERIFICATION.md). Current Maven coordinates are queried against OSV; this is narrower than native/Python advisory coverage or a proof of no vulnerabilities. SBOM engine correspondence does not make Maven input hashes independent DEX attestation.

Scoped deterministic tests cover network boundaries, resume, cancellation, queue durability, storage fault recovery and journal downgrade. Native helper isolation, every extractor/provider/codec, physical DNS/network changes, real ENOSPC and every OEM power policy remain broader acceptance areas. A compromised private app directory can invalidate local persistence assumptions. No security certification or all-device guarantee is claimed.

# MultiMini security review â€” November 14, 2026

## Overview
Scope: current working tree at a403f4b, including pre-existing uncommitted UI/policy work. Actual project is MultiMini; the chat's MultMini directory is empty. Windows Java/Python/C++ local capture utility. No deployed service, account database, payment flow or AI feature found.

Threat model: private screen pixels, input routing, workspace paths and update executable integrity. Boundaries: Java/Python stdin, loopback sockets, shared mappings, filesystem, GitHub releases, OS hooks. Consider other local processes and a compromised release account; this review is not a penetration test.

## Scorecard
FAILED: 2 / 70
PASS: 11; UNKNOWN: 15; NOT APPLICABLE: 42.
NOT PASSED / UNVERIFIED: 17.
PASS + FAIL + UNKNOWN + N/A = 70. NOT PASSED = FAIL + UNKNOWN.

All 70 individual checks appear below and in security-checks.csv. UNKNOWN means missing verification, not a proven vulnerability.

## Changes and verification
- PythonBridge.java and windows_input.py: explicit MULTIMINI_RECORDING_DEMO=1 bypasses only MultiMini capture exclusion for OBS demonstrations. Normal startup retains recursion protection. No third-party protection bypass.
- scripts/run-recording-demo.ps1: opt-in launcher restores previous environment on exit.
- frame_stream.py and game_ingest.py: bound concurrent clients (16/8); FrameStream rejects oversized frames.
- updater.ps1: silence expensive download progress rendering.
- Java experimental build compiled; 43 Python tests passed; updater discovery/checksum/rejection/staging and dummy ZIP replacement/restart tests passed.
- pip-audit unavailable. No full Git history secret scan or account MFA verification completed.

## Separate privacy/legal/accessibility/platform review
Privacy: UNKNOWN. Local screenshot expiry, keep, clipboard and workspace storage inspected; existing docs/PRIVACY_ACCESSIBILITY_REVIEW.md lists unresolved retention/support issues. No legal policy was changed by this audit. Prior jurisdiction statements and license/ownership assertions require owner confirmation and professional review where appropriate.
Cookies/payments/marketing: NOT APPLICABLE to observed functionality.
Accessibility: UNKNOWN pending Windows screen-reader, keyboard-only and custom-accent checks. Existing contrast/document automated check can supplement but cannot certify compliance.
Licensing/IP: UNKNOWN. LICENSE, licenses/ and THIRD_PARTY_NOTICES.md exist; redistribution eligibility of actual JDK and all transitive components requires verification.
Release signing: FAIL (separate platform check). Unsigned executables can be blocked by Windows. ZIP packaging does not resolve signing.
OBS: source-level exclusion fix implemented; live OBS recording and DWM thumbnail inclusion remain UNVERIFIED. Start recording demo, use OBS Display Capture, and prefer window sources for the demo. Display capture of the same monitor can create recursive feedback when exclusion is disabled.

## Readiness
NOT READY for an all-checks-pass release claim. A runnable development build is provided for review. No portable package or GitHub release created. Outstanding: authenticated frame transport, pinned/reviewed build inputs, dependency/history audits, signing, account protections, legal/licensing review and live GUI/OBS verification.

## All 70 individual checks
| ID | Name | Status | Evidence | Fix |
|---|---|---|---|---|
| 01 | Exposed database credentials | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 02 | Public .env files | PASS | .gitignore excludes .env, key/certificate files and user data. |  |
| 03 | Hardcoded API keys or secrets | UNKNOWN | No dedicated secret scanner completed; do not infer clean from ignore rules. |  |
| 04 | Weak or missing authentication | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 05 | Missing server-side authorization | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 06 | Cross-user data access | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 07 | Open database permissions | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 08 | Misconfigured Firebase / Supabase / S3 | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 09 | Unprotected admin routes | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 10 | Production debug tools exposed | UNKNOWN | Controlled DX11 utility intentionally available in development build; release exclusions require final package review. |  |
| 11 | Build logs leaking secrets | UNKNOWN | Build scripts reviewed locally; remote CI logs not reviewed. |  |
| 12 | Verbose production errors | UNKNOWN | Service forwards raw error strings to UI; disclosure scope and log retention not fully assessed. |  |
| 13 | Secrets in Git history | UNKNOWN | Complete Git history secret scan not performed. |  |
| 14 | Secrets shipped in frontend JavaScript | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 15 | Client-side-only security checks | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 16 | Missing input validation | PASS | WorkspaceStore bounds schema, file size, view/source counts; WindowFrameReader bounds pixel sizes. |  |
| 17 | SQL injection | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 18 | NoSQL injection | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 19 | Cross-site scripting (XSS) | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 20 | Cross-site request forgery (CSRF) | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 21 | Insecure file uploads | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 22 | Path traversal | PASS | updater.ps1 rejects ZIP traversal, colon paths and symlink entries before extraction; linked install directories not exercised. |  |
| 23 | Server-side request forgery (SSRF) | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 24 | Broken password-reset flows | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 25 | Weak session management | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 26 | Weak or incorrectly validated JWTs | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 27 | Overly permissive CORS | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 28 | Missing rate limits | PASS | FrameStream and GameIngest bounded client semaphores and socket timeouts added. |  |
| 29 | Unprotected staging or test environments | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 30 | Default credentials left unchanged | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 31 | Webhook signatures not verified | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 32 | Frontend-only payment checks | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 33 | IDOR / BOLA | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 34 | APIs trusting user-controlled roles or IDs | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 35 | Sensitive data in logs | UNKNOWN | Window titles/local paths may appear in workspace/status/errors; comprehensive redaction review pending. |  |
| 36 | Sensitive source maps or build artifacts | PASS | Packaging copies explicit source modules and legal files; outputs ignored by Git; final package deferred. |  |
| 37 | Vulnerable or abandoned dependencies | UNKNOWN | pip-audit not installed; vulnerability database audit not completed. |  |
| 38 | Malicious or compromised packages | UNKNOWN | PyPI dependencies downloaded without pinned hashes; publisher/provenance verification incomplete. |  |
| 39 | Prompt injection | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 40 | AI tools bypassing user permissions | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 41 | Excess database privileges | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 42 | Missing audit logs | UNKNOWN | No full security-event audit log test; local agreement records exist. |  |
| 43 | No security monitoring or alerts | UNKNOWN | No operational incident monitoring verified for release account. |  |
| 44 | No tested backup / restore plan | UNKNOWN | Workspace backup implemented; full crash/update rollback restore matrix not exercised. |  |
| 45 | Public internal dashboards | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 46 | Missing security headers | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 47 | Unsafe cookie settings | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 48 | Sensitive data unprotected in transit / at rest | UNKNOWN | Updates use HTTPS; screenshots/workspace are plaintext protected by OS account permissions; Windows ACL/encryption not verified. |  |
| 49 | Poor tenant isolation | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 50 | Over-trusting AI-generated code | PASS | Manually reviewed capture exclusions, frame transport, updater and existing uncommitted changes; regression tests executed. |  |
| 51 | Mass assignment / over-posting | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 52 | Command / OS injection | PASS | Python service uses subprocess argument lists; Java ProcessBuilder uses argument lists; updater uses fixed commands. |  |
| 53 | Unsafe deserialization | PASS | Workspace uses Properties schema; service uses JSON; no pickle/eval deserialization found. |  |
| 54 | Misconfigured OAuth / OIDC / social login | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 55 | No MFA on privileged accounts | UNKNOWN | GitHub MFA and branch protection cannot be established from checkout. |  |
| 56 | Account enumeration | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 57 | Business-logic abuse | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 58 | Race conditions | PASS | Workspace atomic replacement and screenshot cross-process lock; tests passed. |  |
| 59 | Webhook replay / duplicate processing | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 60 | Overpowered CI/CD credentials | UNKNOWN | Workflow gives contents:write at workflow scope; release credentials/account settings not fully reviewed. |  |
| 61 | Untrusted build actions or scripts | UNKNOWN | Workflow reviewed; upstream action integrity and execution not independently verified. |  |
| 62 | Unpinned build dependencies | FAIL | requirements.txt uses version ranges without hashes; workflow uses mutable action tags and broad contents:write job permissions. | Pin reviewed build inputs and scope release permissions before publishing. |
| 63 | Security checks fail open | PASS | Updater requires checksum; legal consent fails closed if documents absent; checksum failure test passed. |  |
| 64 | Missing resource limits | PASS | Frame size ceiling 256MiB and client limits; socket timeouts; 43 tests passed. |  |
| 65 | AI sensitive-information disclosure | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 66 | Unsafe use of AI output | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 67 | AI agents have excessive agency | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 68 | Sensitive browser storage | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 69 | Open redirects | NOT APPLICABLE | Local desktop utility has no corresponding web/account/database/payment/AI/tenant feature. |  |
| 70 | Unsecured GraphQL / WebSocket / realtime endpoints | FAIL | FrameStream loopback receiver has no authentication; local processes could read frames if source IDs are discovered. | Add a per-launch authentication capability to Java/Python frame protocol and reject unauthenticated clients; regression coverage required. |

## UI and EULA follow-up
Settings now uses a left navigation rail; Help has a more readable document layout. Shared buttons use consistent typography and outlines. Main capture layout and routing architecture were not redesigned in this pass.

TERMS.md is explicitly the EULA and Terms, with an MIT software-license section preserving copying/modification/distribution permissions and mandatory rights. EULA.md points to the canonical agreement; the build embeds both. Agreement version changed to 2026-11-14-eula so existing users review the changed terms. No acceptance was recorded by the audit.

Evidence: https://opensource.org/license/mit and https://www.priv.gc.ca/en/privacy-topics/privacy-for-businesses/appropriate-handling-of-personal-information/collecting-personal-information-and-consent/consent/gl_omc_201805/ . The MIT license covers permissions, notice retention and warranty disclaimer; a separate EULA does not guarantee immunity from liability. Canadian privacy consent must match actual practices; policy text is not a substitute for safeguards.

Unresolved legal/IP items: confidential privacy contact; applicable-jurisdiction review; full runtime/transitive dependency redistribution review; logo/name provenance; manual accessibility verification. No address, compliance certification or legal protection guarantee was invented.

Verification: experimental Java compilation; AppearancePolicyCheck (eight preset accents, both themes, >=4.5:1 text and >=3:1 control cues, offline Help pages); all 43 Python tests passed. No package or publication performed. Original 70-check totals unchanged: FAILED 2/70; PASS 11; UNKNOWN 15; NOT APPLICABLE 42; NOT PASSED/UNVERIFIED 17. Final visual review on the user's Windows setup remains required.

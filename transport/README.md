# :transport — the bridge seam (SMS-1)

Pure Kotlin/JVM module holding the InkStax SMS layer's one
outbound seam, extracted in slice SMS-1 (fork-side
transport-interface extraction, refactor-only).

## What lives here

- `MessageTransport` — the single interface every transport
  binding of the bridge seam implements (director round 4: ONE
  bridge seam, three transport bindings — local / X52 /
  cloud-as-design-intent). Only the interface and its plain value
  types live here in this slice.
- `outboundChannel` — the SMS-vs-MMS routing decision, extracted
  unchanged from the app's send entry point so local mode uses
  the same decision the seam exposes.

## Invariants

- **GMS-free, Android-free.** This module depends on the Kotlin
  standard library only (JUnit for its tests). The local-mode
  build's GMS-free invariant is structural here.
- **No bridge protocol.** No wire format, no ack/heartbeat state,
  no network transport, no endpoint or credential material.
  Protocol work waits for the ink-stax contract surface it plugs
  into (SMS-2 and later slices).
- **V1 is SMS text only.** MMS sending stays on the existing
  app-side path; this seam carries SMS text.

## App-side binding

The local binding lives in the app under
`org.fossify.messages.transport` (`LocalMessageTransport`), which
delegates to the fork's existing SMS persistence/sending code —
behaviour is unchanged, including the sending error codes that
`TransportOutcome.Failed` carries.

## Upstream touchpoint registry (SMS-1, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/kotlin/org/fossify/messages/messaging/Messaging.kt` | Send entry point routes via `outboundChannel` and sends SMS via `MessageTransport`; file shrinks under the code-shape ratchet |
| 2 | `settings.gradle.kts` | `include(":transport")` |
| 3 | `app/build.gradle.kts` | `implementation(project(":transport"))` |
| 4 | `gradle/libs.versions.toml` | `kotlinJvm` plugin alias + JUnit test dependency |
| 5 | `build.gradle.kts` (root) | `kotlinJvm` plugin declared `apply(false)` |

Everything else in this slice is new files (this module and the
app-side `org.fossify.messages.transport` package) — no other
upstream file is modified.

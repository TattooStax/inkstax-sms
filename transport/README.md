# :transport — the bridge seam (SMS-1, SMS-2, SMS-3)

Pure Kotlin/JVM module holding the InkStax SMS layer's one
bridge seam. SMS-1 extracted the outbound side; SMS-2 adds the
inbound side with its local binding first.

## What lives here

- `MessageTransport` — the single outbound interface every
  transport binding of the bridge seam implements (director
  round 4: ONE bridge seam, three transport bindings — local /
  host / cloud-as-design-intent). Plain value types only.
- `outboundChannel` — the SMS-vs-MMS routing decision, extracted
  unchanged from the app's send entry point so local mode uses
  the same decision the seam exposes.
- `RolePosture` + `rolePostureForDedicatedBusinessPhone` +
  `rolePostureFromStoredName` — the role posture bound at setup by
  device class (SMS-3): the one question "Is this phone dedicated
  to the business line?" maps to DEFAULT_ROLE (dedicated) or
  PERMISSIONS_ONLY (personal). UNSET is the fail-safe: no default
  role may be requested until the question is answered, and an
  unrecognised stored value reads back as UNSET.
- `InboundSink` + `InboundSms` + `inboundDispositionFor` — the
  inbound side of the seam (SMS-2). The disposition rule is
  shared by every binding: SMS text is handled, MMS is
  NOT_FORWARDED in V1 (surfaced, never silently dropped), and a
  blank sender is deferred. `InboundSms.sourceId` renders the
  provider row id in the bridge contract's shape
  ("sms:<id>" / "mms:<id>").

## Invariants

- **GMS-free, Android-free.** This module depends on the Kotlin
  standard library only (JUnit for its tests). The local-mode
  build's GMS-free invariant is structural here.
- **No bridge protocol yet.** No wire format, no ack/heartbeat
  state, no network transport, no endpoint or credential
  material. The local binding is in-process; host/cloud bindings
  stay design-intent until their own slices open.
- **No second intake, store, or scheduler.** Receivers persist
  through the existing provider/app store first; the seam only
  classifies and hands off.
- **V1 is SMS text only.** MMS sending stays on the existing
  app-side path; inbound MMS is classified NOT_FORWARDED.

## App-side binding

The local bindings live in the app under
`org.fossify.messages.transport`: `LocalMessageTransport`
(outbound, delegates to the fork's existing SMS
persistence/sending code, error codes unchanged) and
`LocalInboundSink` (inbound, applies the shared disposition
rule after local persistence).

## Upstream touchpoint registry (SMS-1, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/kotlin/org/fossify/messages/messaging/Messaging.kt` | Send entry point routes via `outboundChannel` and sends SMS via `MessageTransport`; file shrinks under the code-shape ratchet |
| 2 | `settings.gradle.kts` | `include(":transport")` |
| 3 | `app/build.gradle.kts` | `implementation(project(":transport"))` |
| 4 | `gradle/libs.versions.toml` | `kotlinJvm` plugin alias + JUnit test dependency |
| 5 | `build.gradle.kts` (root) | `kotlinJvm` plugin declared `apply(false)` |

## Upstream touchpoint registry (SMS-2, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/kotlin/org/fossify/messages/receivers/SmsReceiver.kt` | After local persistence + notification, inbound SMS passes through `LocalInboundSink` with its provider row id |
| 2 | `app/src/main/kotlin/org/fossify/messages/receivers/MmsReceiver.kt` | MMS arrivals pass through `LocalInboundSink` so V1 classifies them NOT_FORWARDED; a NOT_FORWARDED outcome is surfaced to the user with the `mms_not_forwarded` toast |
| 3 | `app/src/main/res/values/strings.xml` | One new string, `mms_not_forwarded`, for that surfacing |

Everything else in these slices is new files (this module and
the app-side `org.fossify.messages.transport` package) — no
other upstream file is modified.

## Upstream touchpoint registry (SMS-3, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/kotlin/org/fossify/messages/helpers/Config.kt` | Stores the posture answer as the posture name string (`rolePostureName`) |
| 2 | `app/src/main/kotlin/org/fossify/messages/activities/MainActivity.kt` | Startup runs the one posture question (via `ensureRolePosture` in the app-side transport package) before the message flow; `loadMessages` requests the default SMS role only when the posture allows it and otherwise goes straight to runtime permissions |
| 3 | `app/src/main/res/values/strings.xml` | The posture question string and its two answer strings |
| 4 | `app/src/main/AndroidManifest.xml` | Registers `SmsReceivedReceiver` for SMS_RECEIVED, the permissions-only posture's inbound copy |
| 5 | `app/src/main/kotlin/org/fossify/messages/extensions/Context.kt` | `deleteConversation` refuses when this app does not hold the default SMS role and directs the user to their default messenger - only the default app may write the provider, so the deletion would otherwise clear only this app's cache and the conversation would be imported again |

SMS-3 note: the dedicated posture keeps the upstream startup flow
unchanged (default role requested, declining it exits). The
personal posture is new behaviour end to end, because a non-default
app lives under different platform rules: the default-role request
is skipped entirely so the owner's own messenger stays default;
inbound arrives through a new SMS_RECEIVED receiver that hands a
copy to the seam and writes nothing (the default app owns the
provider row and the notification); outbound sends through
SmsManager directly from the local binding, because the existing
send path persists to the provider before sending and that write
is denied to non-default apps. The posture answer is per-device
consent: it is stored with a token in no-backup storage, so an
answer restored from backup onto another phone reads back as
UNSET and the question is asked again there. Subscription pinning
for the personal posture is policy in `:transport`
(`requiresPinnedSubscription`); its enforcement at send selection
is a later slice, not claimed here.

# :transport — the bridge seam (SMS-1, SMS-2, SMS-3, SMS-4, SMS-5, SMS-6, SMS-7, SMS-8, SMS-9, SMS-10)

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
- `FailureHonesty.kt` — the failure-honesty states (SMS-4):
  heartbeat health (15-min heartbeat, one missed beat OVERDUE,
  stale-alert at 30 min of silence, never-heartbeated reads stale),
  the dual-ack transitions (inbound HELD until the brain acks;
  outbound QUEUED until the phone acks sent, terminal states never
  move backwards), and the received-late rule. Pure definitions
  only: no timer, scheduler, or alert sending lives here — a
  binding supplies timestamps/events and acts on the states.
- `Pairing.kt` — the onboarding pairing definitions (SMS-5):
  the QR payload shape (`https://<endpoint>/companion/start?t=<token>`,
  read strictly by `pairingOfferFromQrPayload` — anything else is
  no offer), the pairing token's life (single-use, 10 minutes,
  spent outranks expired, clock skew never expires a fresh token),
  the pairing state transitions (an exchange succeeds only from a
  scanned offer, PAIRED is terminal, a fresh scan after expiry is
  the recovery), and the 6-digit typed fallback code's format.
  Pure definitions only: no scanner, no network exchange, and no
  credential storage lives here — a binding supplies the scanned
  text and exchange outcomes and acts on the states.
- `PairingExchange.kt` — the pairing exchange and device-credential
  definitions (SMS-6): what a successful exchange mints (a
  per-device credential for exactly the scanned endpoint, never
  the pairing token itself), which exchange failures end the
  attempt (a spent or lapsed token — a fresh scan is the recovery)
  versus leave it open (transient failures retry the same offer),
  when a stored credential counts as this device's (its fields
  are well formed and its on-device no-backup token names the
  same device id, so a backup-restored credential re-pairs), the
  per-endpoint usability check, and disconnect as the only way
  out of the paired state. Pure definitions only: no network
  exchange runs here — a binding performs the exchange, persists
  what these definitions accept (the app-side storage lands in
  SMS-7), and discards the credential on disconnect.
- `PairingExchangeWire.kt` — the pairing exchange wire
  definitions (SMS-8): the one request shape every binding POSTs
  (only the token, as JSON, to `/companion/exchange` on the
  scanned offer's endpoint, escaped exactly) and the one reading
  of the answer (a flat JSON object of string values on a success
  status, judged by `exchangedCredentialFor`; Gone names the
  token expired, Conflict names it already used, other client
  errors are the endpoint refusing, no answer is unreachable, and
  any other answer is malformed). Pure definitions only: no
  network call runs here — a binding performs the POST, supplies
  the status and body, stores a success through SMS-7's storage,
  and maps a failure through SMS-6's attempt rule.
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
- **No network transport in this module.** The pairing exchange
  itself now runs in the app-side binding (SMS-9), which performs
  the POST SMS-8 defines and feeds SMS-7's storage; this module
  still holds definitions only, and no endpoint or credential
  material lives here. SMS-4 defines the ack/heartbeat *states*
  every binding shares, but no binding sends a heartbeat or an
  ack yet. SMS-5 defines the pairing payload and token *states*,
  and the scanner that reads a QR lives app-side (SMS-10).
  The local binding is
  in-process; host/cloud bindings stay design-intent until their
  own slices open.
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
rule after local persistence). `PairingExchangeBinding.kt`
performs the pairing exchange over HTTPS and stores the minted
credential through `CredentialStorage.kt`. `PairingSetup.kt`
(SMS-10) is the exchange binding's caller: an embedded ZXing
scanner (Apache-2.0, no GMS) launched from Settings reads a QR,
only a payload `pairingOfferFromQrPayload` accepts starts an
exchange, and a minted-but-unstored credential is retried
through the storage once from the credential the result carries.

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

## Upstream touchpoint registry (SMS-7, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The whole
slice is one new file (`CredentialStorage.kt` in the app-side
`org.fossify.messages.transport` package): it stores a credential
the exchange minted in the app's shared preferences as one
checked, synchronous batch (a credential written as three
separately applied Config writes could be left mixed or lost
unreported), together with its on-device token in no-backup
storage written last as the commit marker; it reads the
credential back only through `deviceCredentialFromStored`,
reports a store as successful only after a verifying read-back,
and clears token and fields on disconnect. SMS-7 stores and
reads a credential; it does not run the network exchange and no
scanner reads a QR yet — those act on this storage in a later
slice.

## Upstream touchpoint registry (SMS-8, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The whole
slice is new files inside `:transport` (`PairingExchangeWire.kt`
and its test) plus this README: it defines the exchange request
shape and the strict reading of its answer (status first, then a
flat JSON object of string values judged by SMS-6's
`exchangedCredentialFor`), so a malformed or mismatched answer
can never leave the phone half-paired. SMS-8 runs no network
call and reads no QR yet — a binding performing the POST and
feeding SMS-7's storage, and the scanner UI, act on these
definitions in later slices.

## Upstream touchpoint registry (SMS-9, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/AndroidManifest.xml` | Adds the INTERNET permission — the pairing exchange binding talks to the brain's endpoint over HTTPS |

Everything else in this slice is one new file
(`PairingExchangeBinding.kt` in the app-side
`org.fossify.messages.transport` package) plus this README. The
binding performs the exchange SMS-8 defines: it POSTs the one
request shape to the scanned offer's endpoint, reads the answer
through `pairingExchangeOutcomeFor`, and hands a minted
credential to SMS-7's storage. Its result keeps the three
outcomes distinct — stored, exchange-failed, and
minted-but-storage-failed, where the credential travels with the
result so a caller can retry storing it rather than losing a
pairing whose single-use token is already spent. A transport
fault reads as no answer (unreachable), so the attempt stays
open for a retry inside the token's lifetime. No scanner reads a
QR yet — the scanner UI acts on this binding in a later slice.

## Upstream touchpoint registry (SMS-10, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/build.gradle.kts` | Adds the embedded ZXing scanner dependency (pinned `com.journeyapps:zxing-android-embedded:4.3.0`, Apache-2.0, no GMS) |
| 2 | `app/src/main/res/values/strings.xml` | The pairing entry label, scan prompt, and outcome messages |
| 3 | `app/src/main/res/layout/activity_settings.xml` | The "Pair with your business system" row in Settings |
| 4 | `app/src/main/kotlin/org/fossify/messages/activities/SettingsActivity.kt` | Registers the scanner launcher and wires the Settings row to launch it |

Everything else in this slice is one new file
(`PairingSetup.kt` in the app-side
`org.fossify.messages.transport` package) plus this README. The
scanner is the exchange binding's first caller: a scanned
payload starts an exchange only when it reads as a pairing
offer, a cancelled scan does nothing, and every exchange
outcome is reported in the definitions' failure vocabulary.
The library's own manifest supplies the camera permission and
its capture activity, so this slice adds no manifest entry.
Still not claimed: the download/App-Link serving of the QR is
brain-side work, and no binding sends a heartbeat or an ack yet.

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

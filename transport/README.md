# :transport — the bridge seam (SMS-1, SMS-2, SMS-3, SMS-4, SMS-5, SMS-6, SMS-7, SMS-8, SMS-9, SMS-10, SMS-11), SMS-12, SMS-14, SMS-15, SMS-18, SMS-19, SMS-20, SMS-21, SMS-22, SMS-26, SMS-27, SMS-28

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
  The token's percent escapes are read as ASCII hex only
  (SMS-12): a non-ASCII digit spelling is malformed, never a
  different byte, and the decoder checks that itself instead of
  relying on the URI parse in front of it. The fallback code's
  format is likewise ASCII digits only: the brain issues codes
  in ASCII, so another script's digits are no code it issued.
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
- `HeartbeatWire.kt` — the heartbeat wire definitions (SMS-14):
  the one request shape every binding beats with (the device id
  and the phone's send time as a flat JSON string object, POSTed
  to `/companion/heartbeat` on the stored credential's endpoint,
  the secret carried only in the authorization value, in its
  base64url-encoded spelling, never in the body) and the one reading of the answer (only No Content
  is a recorded beat — the ack carries no body and no message
  state, so a healthy link can never be mistaken for a delivered
  message; a credential refusal is its own failure, distinct
  from an unreachable endpoint). A credential the seam does not
  accept for its own endpoint builds no request at all. Pure
  definitions only: no binding performs the POST yet, and no
  timer or scheduler lives here.
- `MessageDeliveryWire.kt` — the inbound delivery wire
  definitions (SMS-18): the one request shape every binding
  uses to hand an inbound message to the brain (the message
  under its `sourceId`, its sender, body, receipt time, and
  the received-late verdict, as a flat JSON string object,
  POSTed to `/companion/inbound` on the stored credential's
  endpoint, the secret carried only in the authorization
  value, in its base64url-encoded spelling, never in the body)
  and the one reading of the answer (only No Content is the
  brain's ack, releasing the held message through the dual-ack
  transition; a credential refusal is its own failure, distinct
  from an unreachable endpoint). Only a message the shared
  disposition rule handles, carrying a source id, builds a
  request at all. Pure definitions only in this module: the
  binding that performs the POST lives app-side (SMS-20).
- `OutboundAckWire.kt` — the outbound ack wire definitions
  (SMS-19): the one report shape every binding uses to tell the
  brain a reply actually sent or failed (the reply under its
  source id, the outcome, the sending error code on a failure,
  and the report time, as a flat JSON string object, POSTed to
  `/companion/outbound-ack` on the stored credential's endpoint,
  the secret carried only in the authorization value, in its
  base64url-encoded spelling, never in the body) and the one
  reading of the answer (only No Content is the brain recording
  the report; a credential refusal is its own failure, distinct
  from an unreachable endpoint). The body carries no message
  content — the brain already holds the reply it dispatched.
  The report is built from the phone's ack event, never from
  the transport's submission outcome: a returned send call
  means the message was queued, and the verdict arrives
  asynchronously after it. The phone's send outcome, not the
  brain's answer, moves the
  ack state, through SMS-4's transition; a failure's free-text
  detail stays on the phone. A credential the seam does not
  accept for its own endpoint, and an identity that is blank or
  carries whitespace, build no report at all. Pure definitions
  only in this module: the binding that performs the POST lives
  app-side (SMS-21).
- `PendingReportQueue.kt` — the pending outbound report queue
  definitions (SMS-26): the one queue shape every binding holds
  outbound ack reports that have not landed yet in — a plain
  immutable list where only a verdict the report wire itself
  would carry may be held (a source id that is not blank and
  carries no whitespace, a non-negative report time, and an
  error code that agrees with the verdict); a re-seen source id
  is the pending report itself, never a second filing, because
  the phone's verdict on a send is terminal and the first held
  verdict is the one owed; the head is next in reached order;
  and an entry leaves only when the brain records the report,
  every failed outcome leaving it in place for a re-report
  against the same source id. Pure definitions only: no
  storage, no caller, and no drain loop lives here — the
  app-side queue storage and the caller that reports verdicts
  act on these definitions in later slices.
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
  every binding shares, and SMS-14 defines the heartbeat's wire
  shape; the heartbeat binding now performs that beat app-side
  (SMS-15), but no timer or scheduler calls it yet and no
  message ack travels yet. SMS-5 defines the pairing payload and token *states*,
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
credential through `CredentialStorage.kt`. `InboundDeliveryBinding.kt`
(SMS-20) performs the inbound delivery SMS-18 defines: it
delivers with the stored credential, reads the answer by status
alone (the ack carries no body, so none is read), and keeps the
no-usable-credential and not-deliverable outcomes distinct from
every wire outcome, because a broken pairing must not read as a
link that merely went quiet and an undeliverable message is not
a pairing fault. No caller delivers on arrival yet — the
held-message queue that drains through this binding is a later
slice. `OutboundAckBinding.kt` (SMS-21) performs the outbound
ack report SMS-19 defines: it reports the phone's send verdict
with the stored credential, reads the answer by status alone
(the recording answer carries no body, so none is read), and
keeps the no-usable-credential and not-reportable outcomes
distinct from every wire outcome, because a broken pairing must
not read as a link that merely went quiet and an unreportable
verdict is not a pairing fault. No caller reports a verdict yet
— the send-result caller that hands verdicts to this binding is
a later slice. `HeldQueue.kt` (SMS-22) defines the held
inbound message queue the delivery binding drains: a plain
immutable list where only a message the shared disposition
rule handles, carrying a source id and a non-negative
receipt time, may be held; a re-seen
source id is the held message itself, never a second entry;
the head is next in held order; and an entry leaves only when
its delivery is acknowledged — removal is the release the
dual-ack transition defines, and every failed outcome leaves
the entry in place for a redelivery against the same source
id. Pure definitions only: no storage, no caller, and no
drain loop lives here — the app-side queue storage and the
caller that delivers on arrival act on these definitions in
later slices. `HeartbeatBinding.kt`
(SMS-15) performs the heartbeat SMS-14 defines: it beats with
the stored credential, reads the answer by status alone (the
ack carries no body, so none is read), and keeps the
no-usable-credential outcome distinct from an unreachable
endpoint, because a broken pairing must not read as a link
that merely went quiet. No caller beats on a schedule yet —
the timer, scheduler, and foreground notification are later
slices. `PairingSetup.kt`
(SMS-10) is the exchange binding's caller: an embedded ZXing
scanner (Apache-2.0, no GMS) launched from Settings reads a QR,
only a payload `pairingOfferFromQrPayload` accepts starts an
exchange, and a minted-but-unstored credential is retried
through the storage once from the credential the result carries.
A phone that already holds a credential pairs nothing new
(PAIRED is terminal; re-pairing is a disconnect first), and a
process-wide single-flight guard covers the paired check, the
exchange, and the storage retry together.
`PairingDisconnect.kt` (SMS-11) is the disconnect caller, the
defined exit from PAIRED: a Settings entry shown while any
credential material is stored asks for confirmation, then the
clear runs under the same single-flight guard, and only a
verified clear is reported as a disconnect. SMS-7's clear now
reports its result too — the token file is gone, the field
removal is one synchronous commit, and the read-back finds no
credential — so an unfinished clear is announced as unfinished,
never as a disconnect that did not happen. The entry and the
retry key on stored material (`hasStoredCredentialMaterial`),
not on the usable credential, because a partly finished clear
leaves a phone that reads as unpaired but still holds a
leftover token or secret, and that leftover is what a retry
exists to remove. The pairing scanner takes a completion
callback (SMS-11): the exchange finishes on a background
thread after Settings resumes, so the callback is what makes
the disconnect entry appear for a just-completed pairing.
After a disconnect the phone pairs afresh through the same
scanner flow; there is no second re-pair path.

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
An already-paired phone refuses a new offer before any
exchange runs, and one attempt runs at a time under a
process-wide guard.
The library's own manifest supplies the camera permission and
its capture activity, so this slice adds no manifest entry.
Still not claimed: the download/App-Link serving of the QR is
brain-side work, and no binding sends a message ack yet.

## Upstream touchpoint registry (SMS-11, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/res/values/strings.xml` | The disconnect entry label, confirmation, and outcome messages |
| 2 | `app/src/main/res/layout/activity_settings.xml` | The "Disconnect from your business system" row in Settings |
| 3 | `app/src/main/kotlin/org/fossify/messages/activities/SettingsActivity.kt` | Wires the disconnect row, shown while any credential material is stored, refreshed on resume, on pairing completion, and after a disconnect |

Everything else in this slice is two new files
(`PairingDisconnect.kt` and `PairingAttemptGuard.kt` in the app-side
`org.fossify.messages.transport` package), changes to this
track's own app-side files (`CredentialStorage.kt`'s clear
reports a verified result; the pairing single-flight guard
moves into its own `PairingAttemptGuard.kt`, shared by the
pairing attempt and the disconnect clear), and this README.

## Upstream touchpoint registry (SMS-12, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The
slice changes only `Pairing.kt` inside `:transport` and its
test: the percent-decoder reads escape digits through a strict
ASCII hex check instead of a broad digit test that also
accepts non-ASCII digits and letters, so an escape spelled
with those can never decode into a token byte. The payload
reader's URI parse already rejects such spellings today; the
decoder no longer depends on that validation staying in front
of it. The decoder is internal so the module's tests can hold
it to that contract directly, with no URI parse in front of
it — those decoder-level tests fail against the broad digit
test they replace, which decoded the Arabic-Indic spelling of
an escape into a control byte instead of rejecting it.

## Upstream touchpoint registry (SMS-14, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The
whole slice is new files inside `:transport`
(`HeartbeatWire.kt` and its test) plus this README: it defines
the heartbeat request shape and the reading of its answer
(status only — the ack carries no body), so a binding that
performs the beat in a later slice sends the one shape and
judges the answer the one way. SMS-14 runs no network call,
starts no timer, and sends no ack — the binding, its schedule,
and the foreground notification act on these definitions in
later slices.

## Upstream touchpoint registry (SMS-15, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files
(`HeartbeatBinding.kt` in the app-side
`org.fossify.messages.transport` package) plus this README.
The binding performs the heartbeat SMS-14 defines with the
credential SMS-7 stores: a credential the seam does not accept
for its own endpoint beats nothing at all and reports its own
outcome, distinct from every wire failure. SMS-15 starts no
timer or scheduler, wires no foreground notification, and
sends no message ack — a scheduled caller acts on this binding
in a later slice.

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

## Upstream touchpoint registry (SMS-16, ≤5, tagged in code)

Three upstream files are modified in this slice:
(1) `AndroidManifest.xml` — the FOREGROUND_SERVICE and
FOREGROUND_SERVICE_DATA_SYNC permissions and the heartbeat
service entry; (2) `strings.xml` \u2014 the heartbeat channel name
and the persistent notification's state texts; (3)
`SettingsActivity.kt` \u2014 one call on each visit re-arms a paired
phone's heartbeat schedule, the recovery for a start the
platform refused while the app was backgrounded during pairing. Everything else
is new files: `HeartbeatSchedule.kt` and its test in
`:transport` (the schedule rhythm — first beat immediate, later
beats one full interval — and the notification-state vocabulary
every binding reads its latest outcome through), and
`HeartbeatService.kt` and `HeartbeatAlarmReceiver.kt` in the
app-side `org.fossify.messages.transport` package (a wake-up
alarm on that schedule starts a one-beat foreground service —
a standing dataSync service would exhaust the platform's
foreground-service budget and a handler timer would sleep
through deep idle — and the persistent notification is a
standing notification each beat updates). Pairing starts the service
and disconnect stops it, through edits to this track's own
app-side callers; a beat that finds no usable credential stops
the service again. SMS-16 sends no stale-alert (brain-side) and
no message ack, and nothing restarts the service after a reboot
or a force-stop yet — those act on this caller in later slices.

## Upstream touchpoint registry (SMS-17, ≤5, tagged in code)

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/AndroidManifest.xml` | Registers `HeartbeatBootReceiver` for BOOT_COMPLETED — a reboot clears the heartbeat alarm and removes its persistent notification, so the schedule needs a boot entry point to restore itself |

Everything else in this slice is one new file
(`HeartbeatBootReceiver.kt` in the app-side
`org.fossify.messages.transport` package) plus a small
refactor of this track's own heartbeat files: the
foreground-service start attempt the alarm receiver already
made moves into a shared helper in `HeartbeatService.kt`, so
the boot receiver and the alarm receiver hold the same
refusal contract — a refused start selects the inline beat,
never a lost beat. The boot receiver restores only a paired
phone (a stored credential is the signal it was paired
before the reboot): it reposts the persistent notification
in its starting state, re-arms the interval alarm first, then
beats at once — inline on current Android, which forbids
starting a dataSync foreground service from a boot broadcast
and enforces the ban inside the service's own foreground
call, and on older versions through the one-beat service
with the same inline path as the fallback when that start
is refused. An unpaired phone restores nothing.
A force-stopped app receives no boot broadcast, so the
Settings-visit re-arm remains that case's recovery. SMS-17
sends no stale-alert (brain-side) and no message ack.

## Upstream touchpoint registry (SMS-18, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The
whole slice is new files inside `:transport`
(`MessageDeliveryWire.kt` and its test) plus this README: it
defines the inbound delivery request shape and the reading of
its answer (status only — the brain ack carries no body), so
a binding that performs the delivery in a later slice sends
the one shape and judges the answer the one way. SMS-18 runs
no network call and holds no queue — the binding and the
held-message queue it drains act on these definitions in
later slices.

## Upstream touchpoint registry (SMS-19, ≤5, tagged in code)

None — zero upstream files are modified in this slice. The
whole slice is new files inside `:transport`
(`OutboundAckWire.kt` and its test) plus this README: it
defines the outbound ack report shape and the reading of its
answer (status only — the recording answer carries no body),
so a binding that performs the report in a later slice sends
the one shape and judges the answer the one way. SMS-19 runs
no network call and sends no ack — the bindings that perform
the SMS-18 delivery and this report, and the held-message
queue they drain, act on these definitions in later slices.

## Upstream touchpoint registry (SMS-20, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files
(`InboundDeliveryBinding.kt` in the app-side
`org.fossify.messages.transport` package) plus this README.
The binding performs the inbound delivery SMS-18 defines with
the credential SMS-7 stores: a credential the seam does not
accept for its own endpoint delivers nothing at all and
reports its own outcome, distinct from every wire failure, and
a message the shared disposition rule does not handle — or
that carries no source id — reports its own outcome too,
because neither fault is a link that merely went quiet.
SMS-20 wires no caller and holds no queue — the held-message
queue that drains through this binding, and the outbound ack
binding in the same shape, act on it in later slices.

## Upstream touchpoint registry (SMS-21, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files
(`OutboundAckBinding.kt` in the app-side
`org.fossify.messages.transport` package) plus this README.
The binding performs the outbound ack report SMS-19 defines
with the credential SMS-7 stores: a credential the seam does
not accept for its own endpoint reports nothing at all and
reports its own outcome, distinct from every wire failure, and
a verdict that carries no source id — or whose error code
contradicts it — reports its own outcome too, because neither
fault is a link that merely went quiet. SMS-21 wires no
caller — the send-result caller that hands verdicts to this
binding, and the held-message queue, act on it in later
slices.

## Upstream touchpoint registry (SMS-22, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files in `:transport`
(`HeldQueue.kt` and its test) plus this README. The
definitions hold no queue themselves: no storage is written,
no caller delivers on arrival yet, and no outbound verdict
queue is defined — the app-side storage of this queue, the
drain caller, and the pending outbound report queue act on
these definitions in later slices.

## Upstream touchpoint registry (SMS-23, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files
(`HeldQueueStorage.kt` in the app-side
`org.fossify.messages.transport` package) plus this README.
The storage persists the queue SMS-22 defines in the app shared
preferences, in SMS-7's shape: the whole queue is written in
one checked synchronous commit (with the slots of a previously
longer queue removed), and reads rebuild each entry as a
message and fold it through the SMS-22 definitions, so a
restored entry is held only if it still qualifies and a
restored queue never holds a source id twice. A store reports
success only after a verifying read-back. SMS-23 wires no
caller and drains nothing — the drain caller that delivers the
head through SMS-20's binding, and the pending outbound
report queue, act on this storage in later slices.

## Upstream touchpoint registry (SMS-24, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is two new files
(`HeldQueueDrain.kt` and `HeldQueueDrainResult.kt` in the app-side
`org.fossify.messages.transport` package) plus this README.
The drain caller delivers the stored queue SMS-23 persists
through the inbound delivery binding SMS-20 performs, in held
order, folding each answer back through the SMS-22 release
rule and persisting each release before the next message is
attempted. Only the brain's ack releases a head; the first
result that is not an ack stops the drain with the head and
everything behind it still held in order, and an ack whose
release cannot be persisted reports its own outcome, because
the message stays held for a redelivery against the same
source id. SMS-24 wires no trigger of its own — the arrival
caller that holds a message on arrival, any scheduled retry
of a blocked drain, and the pending outbound report queue act
on this caller in later slices.

## Upstream touchpoint registry (SMS-25, ≤5, tagged in code)

Four tagged touchpoints: (1) `SmsReceiver` (the default-role
intake), (2) `SmsReceivedReceiver` (the permissions-only
intake), (3) `MmsReceiver` — the three receivers' existing seam
call sites, extended — and (4) the WorkManager dependency in
`app/build.gradle.kts` (`androidx.work:work-runtime:2.10.5`,
Apache-2.0, no GMS — the local build's GMS-free invariant
holds). Everything else is in this track's own files: three
new files (`HeldArrival.kt`, `HeldDrainWorker.kt`, and
`HeldQueueGuard.kt` in the app-side
`org.fossify.messages.transport` package), the held-queue
storage and drain files extended, plus this README. Each
receiver builds its inbound message once, passes it through
the local binding as before, and then calls the arrival
caller. The caller holds the arrival through the stored
queue SMS-23 persists and enqueues the queue drain SMS-24
performs as lifecycle-managed work (unique, appended,
network-constrained), so delivery to the brain is attempted
on arrival without the drain ever running inside a
broadcast's execution window: the receiver's thread does only
the local hold, and the worker drains outside the broadcast's
lifetime. Only an arrival the shared holding rule holds is
attempted — an MMS arrival stays not forwarded and unheld, a
blank-sender arrival stays unheld, and an arrival with no
source id (the permissions-only path writes no provider row,
so its arrivals carry none) is never held — and only a paired
phone attempts the hold at all: with no usable credential an
unpaired or local-mode phone behaves exactly as before,
holding nothing and delivering nothing. Every
read-modify-write of the stored queue — an arrival's hold and
a drain's release alike — runs under one process-wide guard,
with a drain's network call outside it, so a hold landing
while a delivery is in flight is neither lost under that
delivery's release nor overtaken. The stored queue is bound
to the pairing that held it — its endpoint and device id are
stored beside it in the same commit, and a queue reads back
only under that pairing — so a disconnect and re-pair never
delivers the previous pairing's senders and bodies to a new
endpoint. SMS-25 wires no scheduled retry for a drain a
failure blocks, and the pending outbound report queue acts
on the drain's shape in later slices.

## Upstream touchpoint registry (SMS-27, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is one new file
(PendingReportQueueStorage.kt in the app-side
org.fossify.messages.transport package) plus this README.
The storage persists the queue SMS-26 defines in the app
shared preferences, in SMS-7's shape and SMS-23's shape: the
whole queue is written in one checked synchronous commit
(with the slots of a previously longer queue removed), and
reads rebuild each entry as a verdict and fold it through
the SMS-26 definitions, so a restored entry is pending only
if it still qualifies and a restored queue never holds a
source id twice. The nullable error code travels as a
presence flag beside the value, and a presence flag true
without its value rejects the entry instead of fabricating
a code. A store reports success only after a verifying
read-back. The queue is bound to the pairing that held it —
its endpoint and device id are stored beside it in the same
commit, and a queue reads back only under that pairing — so
a disconnect and re-pair never reports the previous
pairing's verdicts to a new endpoint. SMS-27 wires no
caller and reports nothing — the drain caller that reports
the head through SMS-21's binding, and the caller that holds
a verdict on a send result, act on this storage in later
slices.

## Upstream touchpoint registry (SMS-28, ≤5, tagged in code)

None — zero upstream files are modified in this slice.
Everything in this slice is three new files
(PendingReportQueueDrain.kt, PendingReportQueueDrainResult.kt,
and PendingReportQueueGuard.kt in the app-side
org.fossify.messages.transport package) plus this README.
The drain caller reports the stored queue SMS-27 persists
through the outbound ack binding SMS-21 performs, in reached
order, folding each answer back through the SMS-26 release
rule and persisting each release before the next report is
attempted. Only the brain recording a report releases a head;
the first result that is not a recording stops the drain with
the head and everything behind it still pending in order, and
a recording whose release cannot be persisted reports its own
outcome, because the report stays pending for a re-report
against the same source id. Each report carries the time the
phone reached its verdict, as stored — the drain takes no
report time of its own, so a re-report never rewrites when a
verdict was reached. Each report is performed with the
credential the queue was read under — the outbound ack
binding takes a caller-held credential for this — and the
release re-checks that pairing inside the guard before
folding, so a disconnect and re-pair landing mid-drain never
reroutes a verdict to the new pairing's endpoint and never
folds a release into the new pairing's queue. Every read-modify-write of the stored
queue runs under one process-wide guard, with a drain's
network call outside it, so a hold landing while a report is
in flight is neither lost under that report's release nor
overtaken. SMS-28 wires no trigger of its own — the caller
that holds a verdict on a send result, and any scheduled
retry of a blocked drain, act on this caller in later slices.

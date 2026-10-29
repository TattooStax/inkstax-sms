# Fork notice — InkStax SMS layer

This repository is **InkStax's fork of Fossify Messages**, published
under the GNU General Public License v3.0, exactly as upstream
requires. The upstream `LICENSE` file and all copyright notices are
preserved unmodified.

- **Upstream:** https://github.com/FossifyOrg/Messages
- **Pinned base:** release **1.10.2**, commit
  `194fc3a117dca658389e0719ae83cdeb8145b542`
  (`chore(release): v1.10.2 (26)`), tagged here as
  `fork-base-1.10.2`. Every commit at or below that tag is
  unmodified upstream history.
- **Why the fork exists:** the InkStax SMS layer. The fork stays a
  **separate APK/process** and talks to the InkStax core only
  through a bridge seam (one seam, three transport bindings:
  local / X52 / cloud-as-design-intent); it is never linked into
  the core or the shell. All changes made for InkStax are
  committed and published in this repository.
- **Discipline:** bridge code lives in its own module; upstream
  touchpoints are kept to ≤5 and tagged; no tenant IDs, endpoint
  credentials, or `google-services.json` are ever committed —
  channel credentials are build-injected at packaging time.
- **Track of record:** InkStax SMS track (`~/workspace/sms-dev/`
  in the owner's workspace), home thread "Keep SMS discussion in
  this thread". Design of record: director rounds 3–4 for the
  SMS layer (2026-10-28) — Fossify fork over the earlier
  capcom6-profile plan, posture by device class, GMS-free local
  build invariant, dual ack + heartbeat failure honesty, email
  as mirror/alert only (never a transport).

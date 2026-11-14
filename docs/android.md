# Android app

Native Kotlin + Jetpack Compose app for entering household transactions,
designed for an older Bangla-speaking user. Source in `android/`.

Status: **Milestones 3–5 done**: login, add expense, income and transfers, all
saved offline; history with upload status; corrections and voids offline with
conflict handling; balances, budgets and due recurring entries on the home
screen; receipt photos; reminders and notifications; a Manage area for admins;
Bangla and English. Still missing: a signed release build (Milestone 6).

## Stack

| Concern | Choice |
|---------|--------|
| UI | Jetpack Compose (Material 3), Navigation 3 |
| Local data | Room 2.8; schema JSON committed in `app/schemas/` |
| Background upload | WorkManager 2.12 |
| HTTP | OkHttp 5 + kotlinx.serialization |
| Token storage | AES-256-GCM key in the Android Keystore |
| Language | AppCompat per-app locales (Bangla default, English optional) |
| Build | Android Gradle Plugin 9.4, Gradle 9.8.1, Kotlin 2.3.20, compileSdk 37, targetSdk 36, **minSdk 26 (Android 8.0)** |

There is no DI framework: `AppContainer` wires the few objects by hand.

## Screens

1. **Login:** server address, username, password. The address of the last
   login is pre-filled.
2. **Home:** today's and this month's spending, plus income (server figures
   only); a separate "N entries waiting to be sent" banner; a large **Add
   expense** button, then Add income, ↔ Transfer, Balances, History and
   Settings. Below the buttons (so adding an entry never needs scrolling):
   **🔔 Due now** (recurring entries such as rent: "Paid" after a confirmation
   that says exactly what will be recorded, or "Not this time"; needs the
   internet), **this month's budget** (whole family plus any category close to
   or over its budget, in words and symbols; tap for all budgets), and the
   three most recent entries (tap to open).
   Budgets, balances and due items are server figures cached at each sync, so
   they show offline with "Updated …".
3. **Add expense / income:** large amount field (accepts Bangla digits) with
   quick-amount buttons (৳৫০ ৳১০০ ৳৫০০ ৳১০০০), large category buttons with
   icons (🛒 বাজার, 💊 চিকিৎসা ও ওষুধ …), sorted by the name shown with "Other" last, account (pre-selected when only
   one exists or last used), date (Today / Yesterday / other day), optional
   "for whom" and note. **Save is pinned to the bottom** of the screen. If
   something is missing, the form scrolls up to the red message and a short
   reminder appears above Save. A confirmation dialog must be acknowledged.
   **Transfer** (এক হিসাব থেকে অন্য হিসাবে): amount, from, to (the source is not
   offered as destination), date, note; neither income nor expense; saved
   offline like any entry.
4. **History:** each entry shows its upload state as a symbol and words: ✓ sent,
   ⏳ waiting, ⏳ correction or cancellation waiting, ⚠ problem with the server's
   reason. Tap an entry for its **details**, where the person who entered it
   (or an admin) can **correct** it (same form, pre-filled; date included) or
   **cancel** it with a reason ("entered by mistake", "entered twice", other).
   Both work offline (see `offline-sync.md`). A waiting cancellation can be
   taken back. A conflict shows the server's version and the person's
   correction side by side, with "apply my correction again" or "keep the
   server's". A ⚠ entry can be fixed and resent, or dropped.
   **Receipts** on the same screen: "📷 রসিদের ছবি তুলুন" opens the camera
   (through a private FileProvider; no camera permission needed). The photo is
   shrunk on the phone (at most 2000 px, upright, JPEG 85), kept in private
   app storage and queued (Room v3 `receipt_uploads`); the sync uploads it
   after its entry and then deletes the local copy. Each receipt shows
   ⏳ waiting, ✓ sent or ⚠ not accepted (the photo is kept; it can be removed).
   Waiting receipts block logout like unsent entries.
5. **Balances:** every account's balance as the server last reported it, the
   total, and a note when entries on the phone are not yet included.
6. **Settings:** language (বাংলা / English), sending status with "Send now",
   **reminders** (evening reminder only on days with nothing entered — on by
   default; every evening — off by default; time 18:00–22:00; due bills;
   budget alerts for admins), saved on the server so they follow the person;
   **family switcher** (only for people in several households; blocked while
   anything is unsent), and logout (blocked while anything is unsent).
7. **Manage (পরিচালনা), admins only:** (also **AI summaries**: the checked
   monthly summaries, read-only) the dashboard's tools on the phone. The
   button appears on Home only when the signed-in user is an admin of the
   household (role saved at login and refreshed from `GET /me` on every sync).
   Admins enter their own income and expenses through the normal screens above,
   exactly like members. Manage contains:
   - **Reports:** this month's (or another range's) income, expenses, net,
     spending by category and by person, and account balances, all exactly as
     the server reports them. Nothing is totalled on the phone.
   - **All transactions:** every family member's entries, newest first,
     filtered by period and type, with "Show more". An entry can be **corrected**
     (amount, category, account, note) or **voided** with a reason. Both send
     the version that was shown; only changed fields are sent. If someone else
     changed the entry meanwhile (409), the latest version is reloaded and shown
     and nothing is overwritten.
   - **Accounts:** add (cash, bank, mobile wallet, other; optional signed
     starting balance such as `-500` for a debt), rename, archive and restore.
     No account numbers are stored.
   - **Categories:** add, rename (Bangla and English), archive and restore.
   - **Members:** change role, deactivate and reactivate, and create an
     invitation link that is shared through Android's share sheet.

   Manage is **online only** (`data/remote/AdminApi.kt`); it shows a clear
   error with "Try again" when the server cannot be reached. Hiding the button
   is a convenience: every action is authorised by the server (`ctx.require`),
   so a member who reached these endpoints would get 403.

## Offline-first behaviour

See `offline-sync.md` for the full policy. In short:

- Saving never needs the network. The entry is written to Room as `PENDING`
  with a fresh `client_id` UUID, then a WorkManager upload is requested
  (network constraint, exponential backoff starting at 30 s, at most 8 attempts
  per chain, plus a 6-hourly periodic safety net).
- Retries reuse the same `client_id`; the backend's idempotency guarantees one
  transaction. Tested with a lost response, a server outage and a 45-entry backlog.
- `422`/`400`/`403`/`404` → `FAILED` (shown, not retried). `409` → `CONFLICT`
  (shown, never overwritten). Timeouts, `5xx`, `408` and `429` → stay
  `PENDING` and are retried later.
- When the session expires, entries stay on the phone. The person signs in
  again and sending resumes. A **different** person cannot sign in on top of
  another person's unsent entries, and only the signed-in person's entries are
  ever uploaded.

## Older-user design

- 21sp body text and 24–40sp headings, and the system font-size setting scales
  them further. Touch targets are at least 60dp; main actions are 72dp tall.
- Fixed high-contrast light and dark palettes (no wallpaper-derived colours).
- Selection is shown by ✓ and a thick border, never by colour alone. Status
  text always carries a symbol.
- Screen readers: selectable options expose radio-button semantics and their
  selected state; history rows are read as one sentence; errors are announced.
- Plain-language messages, e.g. "Saved. It will be sent when the phone is online."

## Security

- Refresh token encrypted with a non-exportable Keystore key; corrupted or
  invalidated data leads to "sign in again", never a crash. The access token is
  kept in memory only.
- **No cloud backup or device transfer** of app data (`allowBackup=false` plus
  extraction rules), so financial data stays on the phone and household server.
- Release builds accept only `https://` server addresses. **Debug builds** allow
  plain `http://` (a debug-only network security config) for development over
  the VPN.
- Rejected refresh → "sign in again" screen with the reassurance that entries
  are safe. Logout revokes the session on the server.

## Build and test

```bash
cd android
export ANDROID_HOME=$HOME/Android/Sdk      # or set sdk.dir in local.properties (gitignored)
./gradlew assembleDebug                    # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                # JVM unit tests
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # ALWAYS name the device
```

**Warning:** without `ANDROID_SERIAL`, `connectedDebugAndroidTest` runs on
**every** connected device and **uninstalls the app afterwards**, which deletes
the app's data, including unsent entries, on a real phone plugged in over USB.
This happened once during development (2026-10-15).

```bash
```

Real-backend end-to-end test (throwaway database only; skipped unless arguments
are given):

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=bd.family.expenses.ui.RealBackendTest \
  -Pandroid.testInstrumentationRunnerArguments.fetServer=http://10.0.2.2:8000 \
  -Pandroid.testInstrumentationRunnerArguments.fetUser=<test user> \
  -Pandroid.testInstrumentationRunnerArguments.fetPassword=<test password>
```

`10.0.2.2` is the development computer as seen from the emulator.

### Installing a debug build on a phone (development only)

Enable *Developer options → USB debugging*, connect the phone, then run
`./gradlew installDebug`. The debug app is installed as `bd.family.expenses.debug`
next to any future release build. The homelab serves plain HTTP (`security.md`,
S4), so the phone uses this debug build. A signed release APK is still open in
Milestone 6; its signing key will never be committed.

## Known limitations

- Recurring "Paid"/"Not this time" needs the internet (the amount recorded is
  the expected one; correct the entry afterwards if it differed). Budgets and
  recurring rules are set up on the dashboard.
- Private accounts are set on the dashboard; the phone only shows 🔒.
- The invitation link points to `{server}/accept?token=…`. It works on the
  homelab deployment, where Caddy serves the dashboard and the API from one
  address. In development, create invitations from the dashboard instead.
- Reminders are scheduled with WorkManager (no exact-alarm permission), so
  Android may deliver them a few minutes late; at most one a day. Due bills and
  budget alerts are notified after a sync, each once.
- Receipt OCR suggestions are shown on the dashboard only.
- No voice input (see `voice-evaluation.md`).
- History lists the newest 200 entries.
- Times in Bangla show "AM/PM" in Latin script (platform formatter).

# CLAUDE.md

Bondwidth is a privacy-first Android dialer and contacts app with relationship
tracking ("Bonds"): you set a check-in cadence per person, calls are logged
automatically, and the app shows who is getting overdue. It ships on F-Droid as
`com.phonepvr.friends`. Licensed GPL-3.0-only.

## *IMPORTANT* Workflow for completing tasks

1. Read the task.
2. Do TDD: write a failing (RED) test, then make it pass (GREEN).
   - During RED/GREEN, run only the test classes you are changing, e.g.
     `./gradlew testDebugUnitTest --tests 'com.phonepvr.friends.domain.cadence.*'`.
   - Run the full suite once, right before opening the PR, not after every step:
     `./gradlew testDebugUnitTest assembleDebug assembleRelease`, which is what
     CI runs.
3. Write a QA plan and test it by hand. Confirm everything works.
4. Open the PR **in the fork** (`gh pr create --repo ashwin1287/friends`) and
   merge it to the fork's `main`. Only then move on to the next task.
5. Start each new task from the latest `main`
   (`git checkout main && git pull`, then create a new branch).

### Fork setup

We work in a fork. Changes go upstream only when a whole feature is done.

- `origin` is the fork, `ashwin1287/friends`. Push branches here and open the
  day-to-day PRs here.
- `upstream` is the original repo, `phonepvr/friends`. We can't push to it.
  When a feature is finished, open a PR from the fork into upstream:
  `gh pr create --repo phonepvr/friends --head ashwin1287:<branch>`.
  Only the upstream maintainer can merge it. Don't open upstream PRs unless asked.
- To sync with upstream: `git fetch upstream && git checkout main &&
  git merge upstream/main && git push origin main`.
- Fork-only files such as this `CLAUDE.md` should not go into upstream PRs
  unless the maintainer wants them.

## Build and test

```sh
./gradlew assembleDebug      # debug build (installs as com.phonepvr.friends.dev)
./gradlew testDebugUnitTest  # JVM unit tests (JUnit + Robolectric)
```

- Stack: Kotlin, Jetpack Compose (Material 3), Hilt, Room, DataStore,
  WorkManager, Glance. JDK 17, minSdk 26, targetSdk 35. Single module: `app/`.
- Tests live in `app/src/test/`. There are no instrumented tests (`androidTest`)
  yet. Room tests run on the JVM through `sqlite-jdbc`.
  `testing/PermissionEnforcingContentResolver` fakes runtime permissions.

## Code layout (`app/src/main/java/com/phonepvr/friends/`)

- `domain/` holds pure Kotlin with no Android imports, such as `CadenceCalculator`,
  `PhoneNumberMatcher` and the review analytics. Put new business logic here so
  it can be unit-tested on the JVM.
- `data/` holds the Room DB (`db/`), repositories, call log sync, contacts,
  blocking, backup, dialer, in-call state, and the WhatsApp/Signal launchers
  (`reachout/`).
- `service/` is the telecom integration: `BondwidthInCallService`,
  `BondwidthCallScreeningService` and the call notifications.
- `work/` has the WorkManager jobs (call log sync, reminders, auto-backup).
  `widget/` is the Glance widget. `notification/` covers reminders and
  "mark as wished".
- `ui/` has one Compose package per screen. Navigation is in `ui/navigation/`.
- `feature/Features.kt` holds compile-time feature gates.

## Rules that are easy to break

- **Never add network access.** The manifest removes `INTERNET` and
  `ACCESS_NETWORK_STATE` with `tools:node="remove"`, and CI fails if the APK
  declares either one. Don't add dependencies that need the network
  (no Firebase, GMS, analytics or crash reporting).
- **Don't rename the internal "friends" identifiers.** The package
  `com.phonepvr.friends`, the database `friends.db` and the theme
  `Theme.Friends` stay as they are, because renaming them would orphan
  existing installs' data. The user-facing name is "Bondwidth".
- **Database changes need a migration.** Bump `version` in
  `data/db/FriendsDatabase.kt`, add a `MIGRATION_N_N+1`, register it in
  `di/DatabaseModule.kt`, and add a migration test like `Migration4To5Test`.
  Never fall back to destructive migration.
- **Backups must stay compatible.** Bump `BACKUP_SCHEMA_VERSION` in
  `data/backup/` when the format changes, and make sure older backups still
  restore (see `BackupPersonCompatTest`). Device-local IDs such as `contactId`
  are never exported.
- **Keep the GPL attribution.** Code ported from Fossify Phone or Fossify
  Contacts keeps a file-header comment that names the upstream file
  (see `NOTICE.md`).
- **Don't use real personal data in tests or fixtures.** Use placeholder phone
  numbers and names.

## Builds and releases (F-Droid reproducible builds)

See `RELEASING.md` and `SIGNING.md`. Key constraints:

- `versionCode` and `versionName` in `app/build.gradle.kts` must stay **plain
  literals**, because F-Droid parses them. `versionCode` only ever goes up and
  is currently in the 30000+ range.
- Keep these as they are: the env-var guard around release signing,
  `dependenciesInfo { includeInApk = false }`, and minify OFF. They are what
  make F-Droid's unsigned build byte-identical to ours.
- Debug builds use `applicationIdSuffix = ".dev"`. CI publishes them as
  pre-releases tagged `v1.0.0-build.N.attempt`.
- To cut a release, bump both version values and add
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (under 500
  bytes) in a PR. After it merges, tag `vX.Y.Z` to match `versionName`.

## Conventions

- Commit and PR titles look like `Area: what changed, in plain words`, e.g.
  `Dual-SIM: remember the SIM per number`. Write them in user-facing language.
- Comments explain *why*. The build files show the house style: they are
  heavily commented about constraints.

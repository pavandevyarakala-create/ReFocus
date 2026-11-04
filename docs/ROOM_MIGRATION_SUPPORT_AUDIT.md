# Room Migration Support Audit

## Current source evidence

- `ReelTrakDatabase` is schema version 13.
- `DatabaseModule` defines an upgrade chain from version 8 through 13.
- `DatabaseModule` also calls `fallbackToDestructiveMigration()`, so versions without a registered path can have their local database dropped and recreated.
- Repository history contains a version 5 database schema in commit `3db85ab` and a version 10 schema in commit `4ed1281`. Those snapshots use different entity/table layouts.
- The repository has no Git release tags and no exported Room schema JSON fixtures. The existing migration test helper covers a version 12 fixture only.

## What is not established

Repository evidence does not establish which historical schema versions were actually shipped or remain supported. In particular, there is no source or fixture evidence for versions 6 and 7, nor a supported conversion path from the version 5 entity layout to the current schema. The presence of a historical source schema or checked-in APK does not by itself establish Play release status or the user's minimum supported version.

## Release action

Before release, establish the minimum supported database version from authoritative release records and retrieve representative database schemas/data for every supported version. Add and test migrations only from those verified fixtures. Until then, upgrades from versions without a migration path remain at risk of destructive fallback; do not invent an intermediate or direct migration.

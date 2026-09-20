# AGENTS.md

## Project Overview

**Tab Bookmarks** (`ai.rever.boss.plugin.dynamic.tabbookmarks`) is a dynamic plugin for the BOSS desktop application.

Bookmark manager with folders, tags, notes, visit counts and optional page-content snapshots - fills the metadata gap in the existing URL-only bookmarks.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.tabbookmarks`
- **Main Class**: `ai.rever.boss.plugin.dynamic.bookmarks.BookmarksDynamicPlugin`
- **API Version**: 1.0.93

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.bookmarks)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `pluginStorageFactory`, `splitViewOperations`
- Null-safe provider access: providers may be null, UI must handle gracefully
- Cross-plugin API: `PageContentBridge` reflectively looks up the page-content
  plugin's `PageContentProvider` so the host classloader can resolve the lookup.
  Missing plugin = no-op, never throws.

### Storage model
- One PluginStorageProvider key per bookmark: `BOOKMARK_<id>` where `<id>` is the
  SHA-256 hex of the canonical URL.
- A single `BOOKMARKS_INDEX` key lists every known id, so reads scan the index
  rather than the full keyspace.
- All mutations take an internal mutex so index and per-bookmark writes stay in sync.
- Eviction (50,000 cap) fires only when a new bookmark would push the count past the
  cap; read-only operations never evict.

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations
- **kotlinx-serialization-json**: serialise/deserialise bookmarks and index

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash
- All size/count caps are documented in `BookmarksStore`'s KDoc

## CI/CD

- The **Tests** workflow (`.github/workflows/test.yml`) runs on every pull request. It
  compiles, runs the test suite, and assembles `build/libs/boss-plugin-tab-bookmarks-*.jar`.
  Required to be green before merging.
- The **Release** workflow (`.github/workflows/build.yml`) fires on push to `main` and
  delegates to `risa-labs-inc/BossConsole-Releases/.github/workflows/plugin-release.yml`,
  which publishes a GitHub Release and uploads to the BOSS plugin store. Requires
  `permissions: contents: write` so the release tag can be created.

## Storage limits cheat sheet

| Field | Limit |
|---|---|
| Bookmarks stored | 50,000 |
| Per-bookmark JSON | 256 KiB |
| `note` | 32 KiB |
| `title` | 4 KiB |
| Tags per bookmark | 50 |
| Folder depth | 10 |
| `archiveSnapshot` | 1 MiB |
| `crossRefs` per bookmark | 100 |
| Search/list result count | 200 |

## Cross-plugin integration

`PageContentBridge` looks up the page-content plugin's `PageContentProvider`
reflectively through `context.getPluginAPI(Class.forName(...))`. The lookup is
best-effort: with the page-content plugin not installed every bridge method
returns null or an empty flow, and the panel's "archive" affordance shows a
"plugin not available" state rather than throwing.

## Local development

The Gradle build pulls `boss-plugin-api` 1.0.93 from a sibling checkout at
`../boss-plugin-api/build/libs/boss-plugin-api-1.0.93.jar` unless `CI=true`.
That mirrors CI, which downloads the same jar from the public release on
GitHub. A mismatch between the local jar and the released jar is a real
source of build failures - bump `bossPluginApiPath` in `build.gradle.kts`
when the api jar version changes.

# BOSS Tab Bookmarks

Bookmark manager for the BOSS desktop application - folders, tags, notes, visit counts and
optional page-content snapshots. Adds the metadata the existing host bookmarks do not carry.

The host already ships a URL-only `BookmarkDataProvider`; this plugin layers a richer record
on top, keying on the same canonical URL so a URL deduplicates to one bookmark whether it
came in through the panel or an MCP call.

## What it does

- **Sidebar panel** in the left bottom slot (priority 82):
  - list every bookmark grouped by folder, sorted by recent / created / title / visits
  - search across URL, title, note, folder and tags
  - one-click tag chips at the top of the panel, one-click folder filter
  - "+ Add bookmark" form for URL, title, folder, tags and note
  - click to open the URL in the active browser panel, right side has a delete button
- **Persistence** through the host's `PluginStorageProvider`, scoped per-plugin. Each
  bookmark is stored under its own key, with a single index key listing the known ids.
  Hard cap of 50,000 bookmarks with oldest-by-`lastVisitedAt` eviction.
- **Nine MCP tools** surfaced on the `boss` MCP server for in-terminal agents:
  `tab_bookmarks_add`, `tab_bookmarks_get`, `tab_bookmarks_list`, `tab_bookmarks_search`,
  `tab_bookmarks_update`, `tab_bookmarks_delete`, `tab_bookmarks_record_visit`,
  `tab_bookmarks_archive` and `tab_bookmarks_export`.
- **Optional page-content archive**: `tab_bookmarks_archive` looks up the page-content
  plugin through `context.getPluginAPI(...)` and attaches the resulting JSON snapshot to
  the bookmark. With the page-content plugin absent the call returns `ok=false` rather
  than throwing, so the rest of the tool surface stays usable.

## MCP tools

| Tool | Purpose |
|---|---|
| `tab_bookmarks_add` | Create or enrich a bookmark (url + optional title/folder/tags/note/favicon) |
| `tab_bookmarks_get` | Fetch one bookmark by id or url |
| `tab_bookmarks_list` | List every bookmark, optionally filtered by folder and tag |
| `tab_bookmarks_search` | Substring search across url/title/note/folder/tags |
| `tab_bookmarks_update` | Patch title, folder, add/remove a tag, note, favicon |
| `tab_bookmarks_delete` | Delete a bookmark by id or url |
| `tab_bookmarks_record_visit` | Bump visitCount and lastVisitedAt; creates the record if needed |
| `tab_bookmarks_archive` | Snapshot the active browser tab (via page-content plugin) onto a bookmark |
| `tab_bookmarks_export` | Dump every bookmark as JSON or Markdown |

Every tool other than `tab_bookmarks_export` is either read-only (get, list, search,
record_visit's read-side, archive) or mutating (add, update, delete, record_visit's
write-side, archive's write-side). `record_visit` and `archive` are flagged
`readOnly = false` because they may write a new record.

## Storage limits

| Field | Limit |
|---|---|
| Bookmarks stored | 50,000 (oldest by `lastVisitedAt` evicted) |
| Per-bookmark serialised JSON | 256 KiB |
| `note` | 32 KiB |
| `title` | 4 KiB |
| Tags per bookmark | 50 |
| Folder depth (`/`-separated) | 10 |
| `archiveSnapshot` | 1 MiB |
| `crossRefs` per bookmark | 100 |
| Search/list result count | 200 |

Every field-level cap is enforced before the bookmark reaches the store, so a too-large
note or snapshot returns a `FieldTooLarge` result rather than silently truncating.

## Requirements

- BOSS >= 9.4.2 (the host version that ships the API version this plugin was compiled
  against - `boss-plugin-api` 1.0.93)
- `pluginStorageFactory`, `panelRegistry` and `splitViewOperations` from the host's
  `PluginContext`. The plugin degrades to a no-store state when any of these is null
  rather than throwing.
- For `tab_bookmarks_archive`: the page-content plugin. Without it the call returns
  `ok=false` and leaves the bookmark untouched.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-tab-bookmarks-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.

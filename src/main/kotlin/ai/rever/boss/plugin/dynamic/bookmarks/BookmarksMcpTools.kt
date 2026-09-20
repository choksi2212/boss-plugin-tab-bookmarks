package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools contributed by the Tab Bookmarks plugin.
 *
 * Nine focused tools that read or mutate the bookmark store:
 *  - `tab_bookmarks_add`            - create or enrich a bookmark.
 *  - `tab_bookmarks_get`            - fetch a single bookmark by id (or by url).
 *  - `tab_bookmarks_list`           - list bookmarks, optionally filtered by
 *                                    folder and tag.
 *  - `tab_bookmarks_search`         - substring search across the store.
 *  - `tab_bookmarks_update`         - patch a bookmark's mutable fields.
 *  - `tab_bookmarks_delete`         - remove a bookmark by id.
 *  - `tab_bookmarks_record_visit`   - bump visitCount and lastVisitedAt.
 *  - `tab_bookmarks_archive`        - capture a page-content snapshot and
 *                                    attach it to the bookmark.
 *  - `tab_bookmarks_export`         - dump every bookmark as JSON or Markdown.
 *
 * All tools share the same [BookmarksStore] instance the panel uses; an
 * MCP call and a panel click hit the same mutex.
 */
internal class BookmarksMcpToolProvider(
    override val providerId: String,
    private val store: BookmarksStore,
    private val pageContentBridge: PageContentBridge?,
) : McpToolProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun tools(): List<McpToolDefinition> = listOf(
        add(),
        get(),
        list(),
        search(),
        update(),
        delete(),
        recordVisit(),
        archive(),
        export(),
    )

    private fun add(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_add",
        description = "Create or enrich a bookmark for a URL. Any omitted field keeps the " +
            "stored value. Tags are deduplicated. Returns the bookmark on success.",
        inputSchema = ADD_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleAdd(args) },
    )

    private fun get(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_get",
        description = "Return a single bookmark. Provide either `id` (the SHA-256 of the " +
            "canonical URL) or `url`. Returns null fields when no bookmark exists.",
        inputSchema = GET_SCHEMA,
        handler = McpToolHandler { args -> handleGet(args) },
    )

    private fun list(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_list",
        description = "List every bookmark, optionally filtered by an exact `folder` match and " +
            "a single `tag`. Returns at most 200 bookmarks, ordered by lastVisitedAt.",
        inputSchema = LIST_SCHEMA,
        handler = McpToolHandler { args -> handleList(args) },
    )

    private fun search(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_search",
        description = "Substring search across url, title, note, folder and tags. Returns at " +
            "most 200 bookmarks.",
        inputSchema = SEARCH_SCHEMA,
        handler = McpToolHandler { args -> handleSearch(args) },
    )

    private fun update(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_update",
        description = "Patch a bookmark's mutable fields. Provide `id` (or `url`) and any of " +
            "title, folder, addTag, removeTag, note. Returns the patched bookmark.",
        inputSchema = UPDATE_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleUpdate(args) },
    )

    private fun delete(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_delete",
        description = "Delete a bookmark by `id` (or `url`). Returns whether a record was removed.",
        inputSchema = DELETE_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleDelete(args) },
    )

    private fun recordVisit(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_record_visit",
        description = "Bump visitCount and lastVisitedAt for a URL; optionally set the title. " +
            "Creates a bookmark if none exists yet.",
        inputSchema = RECORD_VISIT_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleRecordVisit(args) },
    )

    private fun archive(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_archive",
        description = "Snapshot the active browser tab's structured content (via the " +
            "page-content plugin) and attach it to the bookmark. With no plugin " +
            "loaded, the snapshot stays null and the call returns ok=false.",
        inputSchema = ARCHIVE_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleArchive(args) },
    )

    private fun export(): McpToolDefinition = McpToolDefinition(
        name = "tab_bookmarks_export",
        description = "Export every bookmark as JSON or Markdown.",
        inputSchema = EXPORT_SCHEMA,
        handler = McpToolHandler { args -> handleExport(args) },
    )

    private suspend fun handleAdd(args: McpToolArgs): McpToolResult {
        val rawUrl = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        if (rawUrl.isEmpty()) {
            return McpToolResult("URL cannot be empty", isError = true)
        }
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && title.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_TITLE_BYTES) {
            return McpToolResult("Title too large", isError = true)
        }
        val folder = args.string("folder")?.trim()?.trim('/') ?: ""
        if (BookmarkFolder.depth(folder) > BookmarksStore.MAX_FOLDERS_DEPTH) {
            return McpToolResult("Folder too deep", isError = true)
        }
        val rawTags = args.string("tags")
            ?.split(',', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (rawTags.size > BookmarksStore.MAX_TAGS_PER_BOOKMARK) {
            return McpToolResult("Too many tags", isError = true)
        }
        val note = args.string("note") ?: ""
        if (note.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_NOTE_BYTES) {
            return McpToolResult("Note too large", isError = true)
        }
        val favicon = args.string("favicon")?.trim()?.takeIf { it.isNotEmpty() }
        val id = UrlCanonicalizer.hash(rawUrl)
        val canonical = UrlCanonicalizer.canonicalize(rawUrl)
        val now = System.currentTimeMillis()
        val existing = store.getBookmark(id)
        val mergedTags = (existing?.tags ?: emptyList()) + rawTags.filter { it !in (existing?.tags ?: emptyList()) }
        val updated = (existing ?: Bookmark(
            id = id,
            url = canonical,
            urlHash = id,
            createdAt = now,
            updatedAt = now,
            lastVisitedAt = now,
            visitCount = 0,
        )).copy(
            title = title ?: existing?.title,
            folder = folder.ifEmpty { existing?.folder ?: "" },
            tags = mergedTags,
            note = if (note.isNotEmpty()) note else existing?.note ?: "",
            favicon = favicon ?: existing?.favicon,
            updatedAt = now,
        )
        return when (val result = store.putBookmark(updated)) {
            BookmarksStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("bookmark", encodeBookmark(updated)) }.toString(),
            )
            is BookmarksStore.StoreResult.FieldTooLarge ->
                McpToolResult("${result.field} too large", isError = true)
            is BookmarksStore.StoreResult.FolderTooDeep ->
                McpToolResult("Folder too deep", isError = true)
            is BookmarksStore.StoreResult.TagCap ->
                McpToolResult("Too many tags", isError = true)
            is BookmarksStore.StoreResult.TooLarge ->
                McpToolResult("Bookmark too large", isError = true)
            is BookmarksStore.StoreResult.TooManyRefs ->
                McpToolResult("Too many cross-references", isError = true)
            BookmarksStore.StoreResult.Unavailable ->
                McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleGet(args: McpToolArgs): McpToolResult {
        val id = resolveId(args)
            ?: return McpToolResult("Missing required argument: id or url", isError = true)
        val bookmark = store.getBookmark(id)
            ?: return McpToolResult(
                buildJsonObject {
                    put("found", false)
                    put("id", id)
                }.toString(),
            )
        return McpToolResult(
            buildJsonObject {
                put("found", true)
                put("bookmark", encodeBookmark(bookmark))
            }.toString(),
        )
    }

    private suspend fun handleList(args: McpToolArgs): McpToolResult {
        val folder = args.string("folder")?.takeIf { it.isNotBlank() }
        val tag = args.string("tag")?.trim()?.takeIf { it.isNotEmpty() }
        val query = args.string("query")?.trim()?.takeIf { it.isNotEmpty() }
        val results = if (query != null) {
            store.search(query).filter { rec ->
                (folder == null || rec.folder == folder) &&
                    (tag == null || tag in rec.tags)
            }
        } else {
            store.listBookmarks(folder = folder, tag = tag)
        }
        return McpToolResult(
            buildJsonObject {
                put("count", results.size)
                put("truncated", results.size >= BookmarksStore.MAX_SEARCH_RESULTS)
                put("bookmarks", buildJsonArray { results.forEach { add(encodeBookmark(it)) } })
            }.toString(),
        )
    }

    private suspend fun handleSearch(args: McpToolArgs): McpToolResult {
        val query = args.string("query")?.trim()
            ?: return McpToolResult("Missing required argument: query", isError = true)
        val results = store.search(query)
        return McpToolResult(
            buildJsonObject {
                put("query", query)
                put("count", results.size)
                put("truncated", results.size >= BookmarksStore.MAX_SEARCH_RESULTS)
                put("bookmarks", buildJsonArray { results.forEach { add(encodeBookmark(it)) } })
            }.toString(),
        )
    }

    private suspend fun handleUpdate(args: McpToolArgs): McpToolResult {
        val id = resolveId(args)
            ?: return McpToolResult("Missing required argument: id or url", isError = true)
        val existing = store.getBookmark(id)
            ?: return McpToolResult("Bookmark not found", isError = true)
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && title.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_TITLE_BYTES) {
            return McpToolResult("Title too large", isError = true)
        }
        val folder = args.string("folder")?.trim()?.trim('/')
        if (folder != null && BookmarkFolder.depth(folder) > BookmarksStore.MAX_FOLDERS_DEPTH) {
            return McpToolResult("Folder too deep", isError = true)
        }
        val note = args.string("note")
        if (note != null && note.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_NOTE_BYTES) {
            return McpToolResult("Note too large", isError = true)
        }
        val addTag = args.string("addTag")?.trim()?.takeIf { it.isNotEmpty() }
        val removeTag = args.string("removeTag")?.trim()?.takeIf { it.isNotEmpty() }
        val favicon = args.string("favicon")?.trim()?.takeIf { it.isNotEmpty() }
        val newTags = existing.tags.toMutableList()
        if (addTag != null && addTag !in newTags) newTags.add(addTag)
        if (removeTag != null) newTags.removeAll { it == removeTag }
        if (newTags.size > BookmarksStore.MAX_TAGS_PER_BOOKMARK) {
            return McpToolResult("Too many tags", isError = true)
        }
        val updated = existing.copy(
            title = title ?: existing.title,
            folder = folder ?: existing.folder,
            tags = newTags,
            note = note ?: existing.note,
            favicon = favicon ?: existing.favicon,
            updatedAt = System.currentTimeMillis(),
        )
        return when (val result = store.putBookmark(updated)) {
            BookmarksStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("bookmark", encodeBookmark(updated)) }.toString(),
            )
            is BookmarksStore.StoreResult.FieldTooLarge ->
                McpToolResult("${result.field} too large", isError = true)
            is BookmarksStore.StoreResult.FolderTooDeep ->
                McpToolResult("Folder too deep", isError = true)
            is BookmarksStore.StoreResult.TagCap ->
                McpToolResult("Too many tags", isError = true)
            is BookmarksStore.StoreResult.TooLarge ->
                McpToolResult("Bookmark too large", isError = true)
            is BookmarksStore.StoreResult.TooManyRefs ->
                McpToolResult("Too many cross-references", isError = true)
            BookmarksStore.StoreResult.Unavailable ->
                McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleDelete(args: McpToolArgs): McpToolResult {
        val id = resolveId(args)
            ?: return McpToolResult("Missing required argument: id or url", isError = true)
        val removed = store.removeBookmark(id)
        return McpToolResult(
            buildJsonObject {
                put("ok", removed)
                put("id", id)
            }.toString(),
        )
    }

    private suspend fun handleRecordVisit(args: McpToolArgs): McpToolResult {
        val rawUrl = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val title = args.string("title")?.trim()
        val record = store.recordVisit(rawUrl, title)
            ?: return McpToolResult("Storage unavailable", isError = true)
        return McpToolResult(
            buildJsonObject { put("ok", true); put("bookmark", encodeBookmark(record)) }.toString(),
        )
    }

    private suspend fun handleArchive(args: McpToolArgs): McpToolResult {
        val rawUrl = args.string("url")?.trim()
        val tabId = args.string("tabId")?.trim()
        val bridge = pageContentBridge
            ?: return McpToolResult(
                buildJsonObject {
                    put("ok", false)
                    put("reason", "page-content plugin not available")
                }.toString(),
            )
        val snapshot = when {
            tabId != null -> bridge.snapshotByTabId(tabId)
            else -> bridge.snapshotCurrent()
        }
        val encoded = bridge.serialize(snapshot)
        if (encoded == null) {
            return McpToolResult(
                buildJsonObject {
                    put("ok", false)
                    put("reason", "no snapshot available (no browser tab or page-content plugin returned null)")
                }.toString(),
            )
        }
        val now = System.currentTimeMillis()
        val id = if (rawUrl != null) {
            UrlCanonicalizer.hash(rawUrl)
        } else {
            // No URL supplied - archive against whatever the active tab is, falling
            // back to looking up an existing record by snapshot URL.
            val snapUrl = snapshot?.get("url")?.toString()?.trim('"')
            if (snapUrl == null) {
                return McpToolResult(
                    buildJsonObject {
                        put("ok", false)
                        put("reason", "no URL provided and snapshot has no URL")
                    }.toString(),
                )
            }
            UrlCanonicalizer.hash(snapUrl)
        }
        val existing = store.getBookmark(id)
        if (existing == null) {
            val title = snapshot?.get("title")?.toString()?.trim('"')
            val urlCanonical = UrlCanonicalizer.canonicalize(snapshot?.get("url")?.toString()?.trim('"') ?: rawUrl ?: "")
            val created = Bookmark(
                id = id,
                url = urlCanonical,
                urlHash = id,
                title = title,
                archiveSnapshot = encoded,
                createdAt = now,
                updatedAt = now,
                lastVisitedAt = now,
                visitCount = 0,
            )
            return when (val result = store.putBookmark(created)) {
                BookmarksStore.StoreResult.Ok -> McpToolResult(
                    buildJsonObject {
                        put("ok", true)
                        put("created", true)
                        put("bookmark", encodeBookmark(created))
                    }.toString(),
                )
                is BookmarksStore.StoreResult.FieldTooLarge ->
                    McpToolResult("Snapshot too large", isError = true)
                is BookmarksStore.StoreResult.TooLarge ->
                    McpToolResult("Bookmark too large", isError = true)
                else ->
                    McpToolResult("Storage unavailable", isError = true)
            }
        }
        val updated = existing.copy(
            archiveSnapshot = encoded,
            updatedAt = now,
        )
        return when (val result = store.putBookmark(updated)) {
            BookmarksStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("created", false)
                    put("bookmark", encodeBookmark(updated))
                }.toString(),
            )
            is BookmarksStore.StoreResult.FieldTooLarge ->
                McpToolResult("Snapshot too large", isError = true)
            is BookmarksStore.StoreResult.TooLarge ->
                McpToolResult("Bookmark too large", isError = true)
            else ->
                McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleExport(args: McpToolArgs): McpToolResult {
        val format = (args.string("format") ?: "json").lowercase()
        val bookmarks = store.allBookmarks()
        val text = when (format) {
            "markdown", "md" -> renderMarkdown(bookmarks)
            else -> json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Bookmark.serializer()),
                bookmarks,
            )
        }
        return McpToolResult(text = text)
    }

    private fun renderMarkdown(bookmarks: List<Bookmark>): String {
        if (bookmarks.isEmpty()) return "# Tab Bookmarks\n\nNo bookmarks.\n"
        val sb = StringBuilder()
        sb.append("# Tab Bookmarks\n\n")
        sb.append("Bookmarks: ").append(bookmarks.size).append('\n').append('\n')
        val grouped = bookmarks.groupBy { it.folder.ifEmpty { BookmarkFolder.ROOT } }
            .toSortedMap(compareBy(String::toString))
        for ((folder, list) in grouped) {
            val heading = if (folder.isEmpty()) "Unfiled" else folder
            sb.append("## ").append(heading).append('\n')
            for (bm in list) {
                sb.append("- [")
                sb.append(bm.title ?: bm.url)
                sb.append("](").append(bm.url).append(')')
                if (bm.visitCount > 0) sb.append(" - visits: ").append(bm.visitCount)
                if (bm.lastVisitedAt > 0L) sb.append(" - last: ").append(formatTime(bm.lastVisitedAt))
                if (bm.tags.isNotEmpty()) sb.append(" - tags: ").append(bm.tags.joinToString(", "))
                sb.append('\n')
                if (bm.note.isNotBlank()) {
                    sb.append("  - ").append(bm.note.replace("\n", "\n  - ")).append('\n')
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun resolveId(args: McpToolArgs): String? {
        val id = args.string("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) return id
        val url = args.string("url")?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null) return UrlCanonicalizer.hash(url)
        return null
    }

    private fun encodeBookmark(bm: Bookmark): JsonObject = buildJsonObject {
        put("id", bm.id)
        put("url", bm.url)
        put("urlHash", bm.urlHash)
        if (bm.title != null) put("title", bm.title) else put("title", JsonNull)
        put("folder", bm.folder)
        put("tags", JsonArray(bm.tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("note", bm.note)
        if (bm.favicon != null) put("favicon", bm.favicon) else put("favicon", JsonNull)
        put("createdAt", bm.createdAt)
        put("updatedAt", bm.updatedAt)
        put("lastVisitedAt", bm.lastVisitedAt)
        put("visitCount", bm.visitCount)
        if (bm.archiveSnapshot != null) put("archiveSnapshot", bm.archiveSnapshot) else put("archiveSnapshot", JsonNull)
        put(
            "crossRefs",
            JsonArray(bm.crossRefs.map { ref ->
                buildJsonObject {
                    put("pluginId", ref.pluginId)
                    put("refId", ref.refId)
                    put("refType", ref.refType)
                    put("label", ref.label)
                }
            }),
        )
    }

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
        return sdf.format(java.util.Date(epochMs))
    }

    private companion object {
        const val ADD_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "title":{"type":"string","description":"Optional title."},
              "folder":{"type":"string","description":"Optional nested folder, slash-separated."},
              "tags":{"type":"string","description":"Optional comma- or newline-separated tag list."},
              "note":{"type":"string","description":"Optional note text."},
              "favicon":{"type":"string","description":"Optional favicon URL."}
            },"required":["url"]}
        """

        const val GET_SCHEMA = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Bookmark id (SHA-256 hex of canonical URL)."},
              "url":{"type":"string","description":"Page URL; hashed to the id."}
            }}
        """

        const val LIST_SCHEMA = """
            {"type":"object","properties":{
              "folder":{"type":"string","description":"Exact-match folder filter."},
              "tag":{"type":"string","description":"Single-tag filter."},
              "query":{"type":"string","description":"Optional substring filter applied on top."}
            }}
        """

        const val SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Substring to find across url/title/note/folder/tags (required)."}
            },"required":["query"]}
        """

        const val UPDATE_SCHEMA = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Bookmark id."},
              "url":{"type":"string","description":"Page URL; hashed to the id."},
              "title":{"type":"string","description":"New title."},
              "folder":{"type":"string","description":"New folder."},
              "addTag":{"type":"string","description":"Tag to add."},
              "removeTag":{"type":"string","description":"Tag to remove."},
              "note":{"type":"string","description":"New note text."},
              "favicon":{"type":"string","description":"New favicon URL."}
            }}
        """

        const val DELETE_SCHEMA = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Bookmark id."},
              "url":{"type":"string","description":"Page URL; hashed to the id."}
            }}
        """

        const val RECORD_VISIT_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "title":{"type":"string","description":"Optional title to set on the bookmark."}
            },"required":["url"]}
        """

        const val ARCHIVE_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL to attach the snapshot to. Defaults to the active tab's URL when omitted."},
              "tabId":{"type":"string","description":"Optional browser tab id to snapshot a specific tab."}
            }}
        """

        const val EXPORT_SCHEMA = """
            {"type":"object","properties":{
              "format":{"type":"string","description":"json or markdown (default json)."}
            }}
        """
    }
}

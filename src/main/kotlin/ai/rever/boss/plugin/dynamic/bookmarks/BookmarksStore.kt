package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistent store for [Bookmark]s.
 *
 * Backed by [PluginStorageProvider] so each bookmark lives under its own key
 * (`BOOKMARK_<id>`) and a single index key (`BOOKMARKS_INDEX`) lists the
 * known ids. Reads scan the index, not the full keyspace, so the cost of an
 * unrelated plugin adding its own keys is bounded.
 *
 * ## Bounded growth
 *
 * The user could bookmark many URLs a year and the store could explode, so
 * the store refuses to add beyond these limits:
 *  - [MAX_BOOKMARKS] (50,000) - the oldest bookmark by `lastVisitedAt` is
 *    evicted when the cap is hit. Eviction only fires when a new record
 *    would push the count past the cap; read-only operations never evict.
 *  - [MAX_BOOKMARK_BYTES] (256 KiB) - a [putBookmark] that serialises to
 *    more than this is refused. The cap is on the *serialised* size, so a
 *    field that fits one platform's UTF-8 might not fit another's; the
 *    conservative limit is the smallest.
 *  - [MAX_NOTE_BYTES] (32 KiB) - a note that exceeds this on its own is
 *    refused before it is written.
 *  - [MAX_TITLE_BYTES] (4 KiB) - a title that exceeds this on its own is
 *    refused before it is written.
 *  - [MAX_TAGS_PER_BOOKMARK] (50) - a record whose `tags` list would exceed
 *    this size is refused.
 *  - [MAX_FOLDERS_DEPTH] (10) - a folder path with more segments than this
 *    is refused.
 *  - [MAX_SNAPSHOT_BYTES] (1 MiB) - an archive snapshot larger than this is
 *    refused before it is written.
 *  - [MAX_RELATED_URLS] (100) - the crossRefs list is capped at this
 *    length (the list carries related URLs and cross-plugin references).
 *
 * [search] returns at most [MAX_SEARCH_RESULTS] (200) bookmarks; callers
 * that want more should paginate by their own key (the order is stable:
 * most recently visited first).
 *
 * All public functions are safe to call concurrently. Mutations hold an
 * internal mutex so the index and the per-bookmark writes stay in sync;
 * reads are lock-free.
 */
class BookmarksStore(
    private val storage: PluginStorageProvider?,
) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(String.serializer())
    private val bookmarkSerializer = Bookmark.serializer()

    suspend fun getBookmark(id: String): Bookmark? {
        val s = storage ?: return null
        val raw = s.getJson(bookmarkKey(id)) ?: return null
        return runCatching { json.decodeFromString(bookmarkSerializer, raw) }.getOrNull()
    }

    suspend fun getBookmarkByUrl(url: String): Bookmark? {
        val id = UrlCanonicalizer.hash(url)
        return getBookmark(id)
    }

    /**
     * Every bookmark currently on disk.
     *
     * Returns bookmarks in `lastVisitedAt` descending order so the panel's
     * "most recent" tab matches the order returned here. Records missing
     * the field sort to the end.
     */
    suspend fun allBookmarks(): List<Bookmark> {
        val s = storage ?: return emptyList()
        val ids = readIndex(s)
        if (ids.isEmpty()) return emptyList()
        val records = mutableListOf<Bookmark>()
        for (id in ids) {
            val raw = s.getJson(bookmarkKey(id)) ?: continue
            val record = runCatching { json.decodeFromString(bookmarkSerializer, raw) }.getOrNull()
            if (record != null) records.add(record)
        }
        return records.sortedByDescending { it.lastVisitedAt }
    }

    /**
     * Write or replace [bookmark].
     *
     * The bookmark is refused if any field exceeds its cap (see the KDoc)
     * or the serialised size would exceed [MAX_BOOKMARK_BYTES]. If the
     * bookmark would push the index past [MAX_BOOKMARKS], the oldest by
     * `lastVisitedAt` is evicted first.
     */
    suspend fun putBookmark(bookmark: Bookmark): StoreResult {
        val s = storage ?: return StoreResult.Unavailable
        validateBookmarks(listOf(bookmark))?.let { return it }
        val serialized = json.encodeToString(bookmarkSerializer, bookmark)
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_BOOKMARK_BYTES) {
            return StoreResult.TooLarge(serialized.toByteArray(Charsets.UTF_8).size)
        }
        mutex.withLock {
            val ids = readIndex(s).toMutableList()
            if (bookmark.id !in ids) {
                ids.add(bookmark.id)
                evictIfOverCap(s, ids)
            }
            s.putJson(bookmarkKey(bookmark.id), serialized)
            s.putJson(INDEX_KEY, json.encodeToString(listSerializer, ids))
        }
        return StoreResult.Ok
    }

    suspend fun removeBookmark(id: String): Boolean {
        val s = storage ?: return false
        var removed = false
        mutex.withLock {
            val ids = readIndex(s).toMutableList()
            if (ids.remove(id)) {
                s.remove(bookmarkKey(id))
                s.putJson(INDEX_KEY, json.encodeToString(listSerializer, ids))
                removed = true
            }
        }
        return removed
    }

    suspend fun bookmarkCount(): Int {
        val s = storage ?: return 0
        return readIndex(s).size
    }

    /**
     * Substring search across `url`, `title`, `tags`, `note`, `folder`.
     *
     * Case-insensitive, returns at most [MAX_SEARCH_RESULTS] bookmarks. The
     * order is `lastVisitedAt` descending, so a search for the same query
     * twice in a row returns the same set.
     */
    suspend fun search(query: String, limit: Int = MAX_SEARCH_RESULTS): List<Bookmark> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val needle = trimmed.lowercase()
        val records = allBookmarks()
        val matches = records.filter { rec ->
            rec.url.lowercase().contains(needle) ||
                (rec.title?.lowercase()?.contains(needle) == true) ||
                rec.note.lowercase().contains(needle) ||
                rec.folder.lowercase().contains(needle) ||
                rec.tags.any { it.lowercase().contains(needle) }
        }
        return matches.take(limit.coerceAtLeast(0))
    }

    /**
     * Substring search across every record, optionally narrowed by [folder]
     * (exact match) and a single [tag]. Returns the same ordered list as
     * [search] - most recently visited first - capped at [limit].
     */
    suspend fun listBookmarks(folder: String? = null, tag: String? = null, limit: Int = MAX_SEARCH_RESULTS): List<Bookmark> {
        val records = allBookmarks()
        val filtered = records.filter { rec ->
            (folder == null || rec.folder == folder) &&
                (tag == null || tag in rec.tags)
        }
        return filtered.take(limit.coerceAtLeast(0))
    }

    /**
     * Bulk update that bumps `visitCount` and `lastVisitedAt` for [url].
     *
     * Creates a new bookmark if none exists. The created bookmark uses
     * [title] when supplied and an empty string for the user-visible
     * note/folder/tag fields.
     */
    suspend fun recordVisit(url: String, title: String? = null, now: Long = System.currentTimeMillis()): Bookmark? {
        val s = storage ?: return null
        val id = UrlCanonicalizer.hash(url)
        val canonical = UrlCanonicalizer.canonicalize(url)
        val existing = getBookmark(id)
        val updated = if (existing == null) {
            Bookmark(
                id = id,
                url = canonical,
                urlHash = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                lastVisitedAt = now,
                visitCount = 1,
            )
        } else {
            existing.copy(
                title = title ?: existing.title,
                updatedAt = now,
                lastVisitedAt = now,
                visitCount = existing.visitCount + 1,
            )
        }
        return when (val result = putBookmark(updated)) {
            StoreResult.Ok -> updated
            else -> null
        }
    }

    private suspend fun evictIfOverCap(s: PluginStorageProvider, ids: MutableList<String>) {
        if (ids.size <= MAX_BOOKMARKS) return
        // Load the bookmarks we are considering evicting, sort by
        // lastVisitedAt ascending, and drop enough to make room. We only
        // load what is needed - if the cap is 50k and we are at 50k + 1,
        // only the oldest one needs to go.
        val toEvict = ids.size - MAX_BOOKMARKS
        val sorted = ids.mapNotNull { id ->
            val raw = s.getJson(bookmarkKey(id)) ?: return@mapNotNull null
            runCatching { json.decodeFromString(bookmarkSerializer, raw) }.getOrNull()
        }.sortedBy { it.lastVisitedAt }
        for (rec in sorted.take(toEvict)) {
            s.remove(bookmarkKey(rec.id))
            ids.remove(rec.id)
        }
    }

    private suspend fun readIndex(s: PluginStorageProvider): List<String> {
        val raw = s.getJson(INDEX_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun bookmarkKey(id: String): String = "BOOKMARK_$id"

    /**
     * Validate every field that has a cap. Returns the first failure as a
     * [StoreResult] the caller can hand back to the user, or null when the
     * record is fine.
     */
    private fun validateBookmarks(bookmarks: List<Bookmark>): StoreResult? {
        for (b in bookmarks) {
            if (b.title != null && b.title.toByteArray(Charsets.UTF_8).size > MAX_TITLE_BYTES) {
                return StoreResult.FieldTooLarge("title", b.title.toByteArray(Charsets.UTF_8).size, MAX_TITLE_BYTES)
            }
            if (b.note.toByteArray(Charsets.UTF_8).size > MAX_NOTE_BYTES) {
                return StoreResult.FieldTooLarge("note", b.note.toByteArray(Charsets.UTF_8).size, MAX_NOTE_BYTES)
            }
            if (b.tags.size > MAX_TAGS_PER_BOOKMARK) {
                return StoreResult.TagCap(b.tags.size, MAX_TAGS_PER_BOOKMARK)
            }
            if (BookmarkFolder.depth(b.folder) > MAX_FOLDERS_DEPTH) {
                return StoreResult.FolderTooDeep(BookmarkFolder.depth(b.folder), MAX_FOLDERS_DEPTH)
            }
            if (b.archiveSnapshot != null && b.archiveSnapshot.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
                return StoreResult.FieldTooLarge(
                    "archiveSnapshot",
                    b.archiveSnapshot.toByteArray(Charsets.UTF_8).size,
                    MAX_SNAPSHOT_BYTES,
                )
            }
            if (b.crossRefs.size > MAX_RELATED_URLS) {
                return StoreResult.TooManyRefs(b.crossRefs.size, MAX_RELATED_URLS)
            }
        }
        return null
    }

    sealed class StoreResult {
        object Ok : StoreResult()
        object Unavailable : StoreResult()
        data class TooLarge(val bytes: Int) : StoreResult()
        data class FieldTooLarge(val field: String, val bytes: Int, val cap: Int) : StoreResult()
        data class TagCap(val got: Int, val cap: Int) : StoreResult()
        data class FolderTooDeep(val got: Int, val cap: Int) : StoreResult()
        data class TooManyRefs(val got: Int, val cap: Int) : StoreResult()
    }

    companion object {
        const val INDEX_KEY = "BOOKMARKS_INDEX"

        /** Hard cap on the number of stored bookmarks. */
        const val MAX_BOOKMARKS = 50_000

        /** Per-bookmark size limit, in bytes, on the serialised JSON. */
        const val MAX_BOOKMARK_BYTES = 256 * 1024

        /** Hard cap on the size of the note field. */
        const val MAX_NOTE_BYTES = 32 * 1024

        /** Hard cap on the size of the title field. */
        const val MAX_TITLE_BYTES = 4 * 1024

        /** Maximum number of tags per bookmark. */
        const val MAX_TAGS_PER_BOOKMARK = 50

        /** Maximum folder depth (number of "/" segments). */
        const val MAX_FOLDERS_DEPTH = 10

        /** Maximum size of an archive snapshot in bytes. */
        const val MAX_SNAPSHOT_BYTES = 1024 * 1024

        /** Maximum number of cross-plugin references on a bookmark. */
        const val MAX_RELATED_URLS = 100

        /** Maximum number of bookmarks returned by [search] / [listBookmarks]. */
        const val MAX_SEARCH_RESULTS = 200
    }
}

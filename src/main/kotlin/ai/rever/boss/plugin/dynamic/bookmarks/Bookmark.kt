package ai.rever.boss.plugin.dynamic.bookmarks

import kotlinx.serialization.Serializable

/**
 * One persisted bookmark.
 *
 * The primary key is [id] (SHA-256 hex of the canonical URL, 64 chars). The
 * canonical URL is stored in [url]; [urlHash] mirrors [id] for symmetry with
 * the page-memory plugin and makes a migration straightforward. Every other
 * field is optional so a "just the URL" record survives the round trip and
 * can be enriched later.
 *
 * [tags] and [crossRefs] are ordered lists - the order the user typed them -
 * and both deduplicate on insert but do not re-sort, so "first added" is the
 * visible order in the panel.
 *
 * [folder] is a nested path with [BookmarksStore.MAX_FOLDERS_DEPTH] as the
 * hard cap; the "/" separator is the only one recognised. Empty string means
 * "no folder" (the root).
 *
 * [archiveSnapshot] is an optional snapshot of the page's structured content
 * captured via the page-content plugin (see [PageContentBridge]). It is
 * stored as the raw JSON string returned by the page-content provider and is
 * capped at [BookmarksStore.MAX_SNAPSHOT_BYTES].
 */
@Serializable
data class Bookmark(
    val id: String,
    val url: String,
    val urlHash: String = id,
    val title: String? = null,
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val note: String = "",
    val favicon: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastVisitedAt: Long = 0L,
    val visitCount: Int = 0,
    val archiveSnapshot: String? = null,
    val crossRefs: List<CrossRef> = emptyList(),
)

@Serializable
data class CrossRef(
    val pluginId: String,
    val refId: String,
    val refType: String,
    val label: String = "",
)

/**
 * Every value [Bookmark.folder] is allowed to carry. The store refuses to
 * insert a record whose folder is deeper than [BookmarksStore.MAX_FOLDERS_DEPTH].
 */
object BookmarkFolder {
    const val SEPARATOR = "/"
    const val ROOT = ""

    /**
     * Number of segments in [folder] - 0 for the root, 1 for "Research", 2 for
     * "Research/ML" and so on. A trailing separator counts as an empty segment
     * and is rejected by the store before this is called.
     */
    fun depth(folder: String): Int {
        if (folder.isEmpty()) return 0
        return folder.split(SEPARATOR).size
    }
}

/**
 * Sort orders the panel offers. Each value maps to a [Comparator] the panel
 * applies over a `List<Bookmark>`.
 */
object BookmarkSort {
    const val LAST_VISITED = "lastVisited"
    const val CREATED = "created"
    const val TITLE = "title"
    const val VISIT_COUNT = "visitCount"

    val ALL = listOf(LAST_VISITED, CREATED, TITLE, VISIT_COUNT)

    fun comparator(sort: String): Comparator<Bookmark> = when (sort) {
        CREATED -> compareByDescending<Bookmark> { it.createdAt }
            .thenByDescending { it.id }
        TITLE -> compareBy<Bookmark> { (it.title ?: it.url).lowercase() }
            .thenBy { it.id }
        VISIT_COUNT -> compareByDescending<Bookmark> { it.visitCount }
            .thenByDescending { it.lastVisitedAt }
        else -> compareByDescending<Bookmark> { it.lastVisitedAt }
            .thenByDescending { it.createdAt }
    }
}

package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.SplitViewOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Bookmarks panel.
 *
 * Holds:
 *  - the user's search query and tag/folder filters
 *  - the current sort order
 *  - the list of bookmarks the panel renders
 *  - transient status / error lines
 *  - the add-bookmark form drafts (URL, title, folder, tags, note)
 *
 * The ViewModel is the single place that talks to [BookmarksStore]; the
 * compose layer is a passive renderer that calls [refresh], [addBookmark],
 * [deleteBookmark], etc. on user actions.
 */
class BookmarksViewModel(
    private val store: BookmarksStore,
    private val splitViewOperations: SplitViewOperations?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeTag = MutableStateFlow<String?>(null)
    val activeTag: StateFlow<String?> = _activeTag.asStateFlow()

    private val _activeFolder = MutableStateFlow<String?>(null)
    val activeFolder: StateFlow<String?> = _activeFolder.asStateFlow()

    private val _sort = MutableStateFlow(BookmarkSort.LAST_VISITED)
    val sort: StateFlow<String> = _sort.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _allFolders = MutableStateFlow<List<String>>(emptyList())
    val allFolders: StateFlow<List<String>> = _allFolders.asStateFlow()

    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    private val _addFormVisible = MutableStateFlow(false)
    val addFormVisible: StateFlow<Boolean> = _addFormVisible.asStateFlow()

    private val _addUrl = MutableStateFlow("")
    val addUrl: StateFlow<String> = _addUrl.asStateFlow()

    private val _addTitle = MutableStateFlow("")
    val addTitle: StateFlow<String> = _addTitle.asStateFlow()

    private val _addFolder = MutableStateFlow("")
    val addFolder: StateFlow<String> = _addFolder.asStateFlow()

    private val _addTags = MutableStateFlow("")
    val addTags: StateFlow<String> = _addTags.asStateFlow()

    private val _addNote = MutableStateFlow("")
    val addNote: StateFlow<String> = _addNote.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            val all = store.allBookmarks()
            _bookmarks.value = filterAndSort(all)
            _allFolders.value = all.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()
            _allTags.value = all.flatMap { it.tags }.distinct().sorted()
        }
    }

    fun updateSearchQuery(text: String) {
        _searchQuery.value = text
        scope.launch {
            val all = store.allBookmarks()
            val filtered = if (text.isBlank()) all else store.search(text)
            _bookmarks.value = filterAndSort(filtered)
        }
    }

    fun setActiveTag(tag: String?) {
        _activeTag.value = tag
        refresh()
    }

    fun setActiveFolder(folder: String?) {
        _activeFolder.value = folder
        refresh()
    }

    fun setSort(sort: String) {
        if (sort in BookmarkSort.ALL) _sort.value = sort
        refresh()
    }

    private fun filterAndSort(records: List<Bookmark>): List<Bookmark> {
        val tag = _activeTag.value
        val folder = _activeFolder.value
        val filtered = records.filter { rec ->
            (folder == null || rec.folder == folder) &&
                (tag == null || tag in rec.tags)
        }
        return filtered.sortedWith(BookmarkSort.comparator(_sort.value))
    }

    fun openBookmarkUrl(bookmark: Bookmark) {
        val ops = splitViewOperations ?: run {
            _error.value = "Split view not available"
            return
        }
        ops.openUrlInActivePanel(bookmark.url, bookmark.title ?: bookmark.url, false)
    }

    fun deleteBookmark(id: String) {
        scope.launch {
            if (store.removeBookmark(id)) {
                _status.value = "Bookmark deleted"
                refresh()
            } else {
                _error.value = "Could not delete bookmark"
            }
        }
    }

    fun showAddForm() {
        _addFormVisible.value = true
    }

    fun hideAddForm() {
        _addFormVisible.value = false
        clearAddDrafts()
    }

    fun updateAddUrl(text: String) {
        _addUrl.value = text
    }

    fun updateAddTitle(text: String) {
        _addTitle.value = text
    }

    fun updateAddFolder(text: String) {
        _addFolder.value = text
    }

    fun updateAddTags(text: String) {
        _addTags.value = text
    }

    fun updateAddNote(text: String) {
        _addNote.value = text
    }

    fun submitAddForm() {
        val rawUrl = _addUrl.value.trim()
        if (rawUrl.isEmpty()) {
            _error.value = "URL cannot be empty"
            return
        }
        if (rawUrl.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_BOOKMARK_BYTES) {
            _error.value = "URL too long"
            return
        }
        val title = _addTitle.value.trim().takeIf { it.isNotEmpty() }
        if (title != null && title.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_TITLE_BYTES) {
            _error.value = "Title too long"
            return
        }
        val folder = _addFolder.value.trim().trim('/')
        if (BookmarkFolder.depth(folder) > BookmarksStore.MAX_FOLDERS_DEPTH) {
            _error.value = "Folder too deep"
            return
        }
        val rawTags = _addTags.value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rawTags.size > BookmarksStore.MAX_TAGS_PER_BOOKMARK) {
            _error.value = "Too many tags"
            return
        }
        val tags = rawTags.distinct()
        val note = _addNote.value
        if (note.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_NOTE_BYTES) {
            _error.value = "Note too long"
            return
        }
        val id = UrlCanonicalizer.hash(rawUrl)
        val canonical = UrlCanonicalizer.canonicalize(rawUrl)
        val now = System.currentTimeMillis()
        scope.launch {
            val existing = store.getBookmark(id)
            val updated = (existing ?: Bookmark(
                id = id,
                url = canonical,
                urlHash = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                lastVisitedAt = now,
                visitCount = 0,
            )).copy(
                title = title ?: existing?.title,
                folder = folder.ifEmpty { existing?.folder ?: "" },
                tags = (existing?.tags ?: emptyList()) + tags.filter { it !in (existing?.tags ?: emptyList()) },
                note = if (note.isNotEmpty()) note else existing?.note ?: "",
                updatedAt = now,
            )
            when (val result = store.putBookmark(updated)) {
                BookmarksStore.StoreResult.Ok -> {
                    _status.value = "Bookmark added"
                    hideAddForm()
                    refresh()
                }
                is BookmarksStore.StoreResult.FieldTooLarge ->
                    _error.value = "${result.field} too large (${result.bytes} > ${result.cap})"
                is BookmarksStore.StoreResult.FolderTooDeep ->
                    _error.value = "Folder too deep (${result.got} > ${result.cap})"
                is BookmarksStore.StoreResult.TagCap ->
                    _error.value = "Too many tags (${result.got} > ${result.cap})"
                is BookmarksStore.StoreResult.TooLarge ->
                    _error.value = "Bookmark too large (${result.bytes} bytes)"
                is BookmarksStore.StoreResult.TooManyRefs ->
                    _error.value = "Too many cross-references (${result.got} > ${result.cap})"
                BookmarksStore.StoreResult.Unavailable ->
                    _error.value = "Storage unavailable"
            }
        }
    }

    fun clearMessages() {
        _status.value = null
        _error.value = null
    }

    private fun clearAddDrafts() {
        _addUrl.value = ""
        _addTitle.value = ""
        _addFolder.value = ""
        _addTags.value = ""
        _addNote.value = ""
    }
}

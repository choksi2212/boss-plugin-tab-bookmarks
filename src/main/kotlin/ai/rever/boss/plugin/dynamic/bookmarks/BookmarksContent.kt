package ai.rever.boss.plugin.dynamic.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose UI for the Tab Bookmarks panel.
 *
 * Two-section vertical layout:
 *  - top: toolbar + status line + filters + add-form (when open)
 *  - bottom: a scrollable list of bookmark rows, grouped by folder when
 *    no folder filter is active
 */
@Composable
fun BookmarksContent(viewModel: BookmarksViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val folders by viewModel.allFolders.collectAsState()
    val tags by viewModel.allTags.collectAsState()
    val activeTag by viewModel.activeTag.collectAsState()
    val activeFolder by viewModel.activeFolder.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val statusMessage by viewModel.status.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val addFormVisible by viewModel.addFormVisible.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Toolbar(
                sort = sort,
                onSortChange = { viewModel.setSort(it) },
                onAdd = { viewModel.showAddForm() },
                onRefresh = { viewModel.refresh() },
            )
            StatusLine(statusMessage = statusMessage, errorMessage = errorMessage, onDismiss = { viewModel.clearMessages() })
            Filters(
                searchQuery = searchQuery,
                onSearchChange = { viewModel.updateSearchQuery(it) },
                tags = tags,
                activeTag = activeTag,
                onTagClick = { viewModel.setActiveTag(if (activeTag == it) null else it) },
                folders = folders,
                activeFolder = activeFolder,
                onFolderChange = { viewModel.setActiveFolder(it) },
            )
            if (addFormVisible) {
                AddBookmarkForm(viewModel = viewModel)
            }
            BookmarkList(
                bookmarks = bookmarks,
                activeFolder = activeFolder,
                onOpen = { viewModel.openBookmarkUrl(it) },
                onDelete = { viewModel.deleteBookmark(it.id) },
            )
        }
    }
}

@Composable
private fun Toolbar(
    sort: String,
    onSortChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Tab Bookmarks",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        SortMenu(selected = sort, onChange = onSortChange)
        IconButton(
            onClick = onAdd,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add bookmark",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SortMenu(selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(text = labelForSort(selected), fontSize = 10.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BookmarkSort.ALL.forEach { sort ->
                DropdownMenuItem(onClick = {
                    onChange(sort)
                    expanded = false
                }) {
                    Text(text = labelForSort(sort), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    statusMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(statusMessage, errorMessage) {
        if (statusMessage != null || errorMessage != null) {
            delay(3000)
            onDismiss()
        }
    }
    val message = errorMessage ?: statusMessage ?: return
    val isError = errorMessage != null
    val bg = if (isError) MaterialTheme.colors.error.copy(alpha = 0.15f) else MaterialTheme.colors.primary.copy(alpha = 0.15f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Filters(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    tags: List<String>,
    activeTag: String?,
    onTagClick: (String) -> Unit,
    folders: List<String>,
    activeFolder: String?,
    onFolderChange: (String?) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search title, URL, tag, note, folder", fontSize = 12.sp) },
            singleLine = true,
        )
        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tags, key = { it }) { tag ->
                    TagChip(label = tag, active = tag == activeTag, onClick = { onTagClick(tag) })
                }
            }
        }
        if (folders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FolderFilter(
                folders = folders,
                activeFolder = activeFolder,
                onChange = onFolderChange,
            )
        }
    }
}

@Composable
private fun TagChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.primary.copy(alpha = 0.12f)
    val fg = if (active) MaterialTheme.colors.onPrimary else MaterialTheme.colors.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun FolderFilter(
    folders: List<String>,
    activeFolder: String?,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (activeFolder == null) "All folders" else "Folder: $activeFolder",
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(onClick = {
                onChange(null)
                expanded = false
            }) {
                Text("All folders", fontSize = 11.sp)
            }
            folders.forEach { folder ->
                DropdownMenuItem(onClick = {
                    onChange(folder)
                    expanded = false
                }) {
                    Text(folder, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AddBookmarkForm(viewModel: BookmarksViewModel) {
    val url by viewModel.addUrl.collectAsState()
    val title by viewModel.addTitle.collectAsState()
    val folder by viewModel.addFolder.collectAsState()
    val tags by viewModel.addTags.collectAsState()
    val note by viewModel.addNote.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader(title = "Add bookmark")
        OutlinedTextField(
            value = url,
            onValueChange = viewModel::updateAddUrl,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("URL (required)", fontSize = 11.sp) },
            singleLine = true,
        )
        OutlinedTextField(
            value = title,
            onValueChange = viewModel::updateAddTitle,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Title (optional)", fontSize = 11.sp) },
            singleLine = true,
        )
        OutlinedTextField(
            value = folder,
            onValueChange = viewModel::updateAddFolder,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Folder, e.g. Research/ML (optional)", fontSize = 11.sp) },
            singleLine = true,
        )
        OutlinedTextField(
            value = tags,
            onValueChange = viewModel::updateAddTags,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tags, comma-separated (optional)", fontSize = 11.sp) },
            singleLine = true,
        )
        OutlinedTextField(
            value = note,
            onValueChange = viewModel::updateAddNote,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Note (optional)", fontSize = 11.sp) },
            minLines = 2,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { viewModel.hideAddForm() }) {
                Text("Cancel", fontSize = 11.sp)
            }
            TextButton(onClick = { viewModel.submitAddForm() }) {
                Text("Save", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    activeFolder: String?,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No bookmarks yet - add one with the + button, or use the tab_bookmarks_* MCP tools.",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(8.dp),
            )
        }
        return
    }
    val grouped = if (activeFolder != null) {
        mapOf(activeFolder to bookmarks)
    } else {
        bookmarks.groupBy { it.folder.ifEmpty { BookmarkFolder.ROOT } }
            .toSortedMap(compareBy(String::toString))
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        grouped.forEach { (folder, list) ->
            item(key = "folder-$folder") {
                SectionHeader(title = if (folder.isEmpty()) "Unfiled" else folder)
            }
            items(list, key = { it.id }) { bookmark ->
                BookmarkRow(bookmark = bookmark, onOpen = onOpen, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .clickable { onOpen(bookmark) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookmark.title ?: bookmark.url,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bookmark.url,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append("Visits: ").append(bookmark.visitCount)
                if (bookmark.lastVisitedAt > 0L) {
                    append(" - Last: ").append(formatTime(bookmark.lastVisitedAt))
                }
                if (bookmark.tags.isNotEmpty()) {
                    append(" - Tags: ").append(bookmark.tags.joinToString(", "))
                }
            }
            Text(
                text = meta,
                fontSize = 9.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = { onDelete(bookmark) },
            modifier = Modifier.size(20.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun labelForSort(sort: String): String = when (sort) {
    BookmarkSort.CREATED -> "Created"
    BookmarkSort.TITLE -> "Title"
    BookmarkSort.VISIT_COUNT -> "Visits"
    else -> "Recent"
}

private fun formatTime(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
    return sdf.format(java.util.Date(epochMs))
}

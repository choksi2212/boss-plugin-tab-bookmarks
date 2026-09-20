package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * The Tab Bookmarks panel component.
 *
 * Wraps [BookmarksViewModel] and exposes its content. The component does
 * NOT keep a long-lived observer; the ViewModel is created once and the
 * compose layer refreshes it on user actions. [refresh] is called once
 * when the panel first opens.
 */
class BookmarksComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val store: BookmarksStore,
    private val splitViewOperations: SplitViewOperations?,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = BookmarksViewModel(store, splitViewOperations)

    init {
        // Load the list once when the panel first opens.
        viewModel.refresh()
    }

    @Composable
    override fun Content() {
        BookmarksContent(viewModel = viewModel)
    }
}

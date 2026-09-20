package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Bookmark

/**
 * Describes the Tab Bookmarks panel: id, sidebar icon, default slot.
 *
 * Lives on the left bottom slot so it sits beside Git Status (priority 14)
 * and Page Memory (priority 68). Priority 82 keeps it after the panels that
 * load first and the user is most likely to open by default.
 */
object BookmarksInfo : PanelInfo {
    override val id = PanelId("tab-bookmarks", 82)
    override val displayName = "Tab Bookmarks"
    override val icon = FeatherIcons.Bookmark
    override val defaultSlotPosition: Panel = left.bottom
}

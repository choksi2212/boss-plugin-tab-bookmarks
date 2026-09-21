package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory

/**
 * Tab Bookmarks dynamic plugin - Loaded from external JAR.
 *
 * Two surfaces, one store:
 *  - the sidebar panel, for human use
 *  - the `tab_bookmarks_*` MCP tools, for in-terminal agents
 *
 * Both reach into the same [BookmarksStore], so an MCP write and a panel
 * click on the same URL race through the same mutex. The archive snapshot
 * tool optionally reaches the page-content plugin through
 * [PageContentBridge]; the bridge is a no-op when that plugin is absent.
 */
class BookmarksDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.tabbookmarks"
    override val displayName: String = "Tab Bookmarks"
    override val version: String = manifestVersion()
    override val description: String =
        "Bookmark manager with folders, tags, notes, visit counts and optional " +
        "page-content snapshots - fills the metadata gap in the existing URL-only bookmarks"
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-tab-bookmarks"

    private var store: BookmarksStore? = null
    private var pageContentBridge: PageContentBridge? = null
    private var storageFactory: PluginStorageFactory? = null

    override fun register(context: PluginContext) {
        storageFactory = context.pluginStorageFactory
        val storage = storageFactory?.createStorage(pluginId)
        val resolvedStore = BookmarksStore(storage)
        store = resolvedStore

        val resolvedBridge = PageContentBridge(context)
        pageContentBridge = resolvedBridge

        context.panelRegistry.registerPanel(BookmarksInfo) { ctx, panelInfo ->
            BookmarksComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                store = resolvedStore,
                splitViewOperations = context.splitViewOperations,
            )
        }

        context.registerMcpToolProvider(
            BookmarksMcpToolProvider(
                providerId = pluginId,
                store = resolvedStore,
                pageContentBridge = resolvedBridge,
            ),
        )
    }

    override fun dispose() {
        store = null
        pageContentBridge = null
        storageFactory = null
    }

    /**
     * The version from this plugin's own manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the
     * same resource path, so a `getResourceAsStream` that returns the first
     * hit could read someone else's manifest if the host ever loads
     * plugins through a parent-first classloader. Only the entry that
     * names this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}

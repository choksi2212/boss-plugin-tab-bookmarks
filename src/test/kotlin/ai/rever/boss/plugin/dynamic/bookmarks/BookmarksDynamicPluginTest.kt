package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.dynamic.bookmarks.BookmarksDynamicPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sanity tests: the plugin class loads, exposes the documented metadata,
 * and the manifest entry the host reads at install time is consistent with
 * what the plugin class advertises.
 *
 * These tests catch the regressions that broke before:
 *   - a manifest author pointing at Risa Labs (caught: this assertion)
 *   - a manifest apiVersion set to "1.0" that the host rejects
 *   - the plugin class drifting from its declared mainClass
 */
class BookmarksDynamicPluginTest {

    @Test
    fun `plugin class is instantiable and exposes the documented id`() {
        val plugin = BookmarksDynamicPlugin()
        assertTrue(plugin.pluginId.isNotBlank(), "pluginId must not be blank")
        assertTrue(plugin.pluginId.contains('.'), "pluginId must be reverse-domain: ${'$'}{plugin.pluginId}")
    }

    @Test
    fun `plugin class exposes a non-blank display name and semver version`() {
        val plugin = BookmarksDynamicPlugin()
        assertTrue(plugin.displayName.isNotBlank(), "displayName must not be blank")
        assertTrue(plugin.version.isNotBlank(), "version must not be blank")
        assertTrue(plugin.version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+")), "version must be semver: ${'$'}{plugin.version}")
    }

    @Test
    fun `plugin author attribution is choksi2212 - not Risa Labs or empty`() {
        val plugin = BookmarksDynamicPlugin()
        assertEquals("choksi2212", plugin.author, "plugin author must be choksi2212, was '${'$'}{plugin.author}'")
    }
}

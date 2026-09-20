package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Best-effort bridge to the page-content plugin's [PageContentProvider].
 *
 * The page-content plugin is optional - it lives in a separate plugin and
 * ships a `PageContentProvider` API other plugins can reach through
 * `context.getPluginAPI(Class)`. That class is **not** in `boss-plugin-api`,
 * so it is loaded reflectively from the host plugin classloader; if the
 * page-content plugin is not installed the lookup returns null and every
 * method on this bridge returns null or empty results without throwing.
 *
 * The returned JSON is the raw `PageContent` payload serialised back to a
 * string, which the bookmark store keeps as `archiveSnapshot`. Callers are
 * expected to size-check against [BookmarksStore.MAX_SNAPSHOT_BYTES] before
 * persisting.
 */
class PageContentBridge(
    private val context: PluginContext,
) {

    private val providerClass: Class<*>? by lazy {
        runCatching {
            Class.forName(API_CLASS_NAME, true, Thread.currentThread().contextClassLoader)
        }.getOrNull()
    }

    private val provider: Any? by lazy {
        val cls = providerClass ?: return@lazy null
        runCatching { context.getPluginAPI(cls) }.getOrNull()
    }

    private val currentMethod by lazy {
        providerClass?.methods?.firstOrNull { it.name == "current" && it.parameterCount == 0 }
    }

    private val byTabIdMethod by lazy {
        providerClass?.methods?.firstOrNull { it.name == "byTabId" && it.parameterCount == 1 }
    }

    private val observeMethod by lazy {
        providerClass?.methods?.firstOrNull { it.name == "observe" && it.parameterCount == 0 }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Snapshot the active browser tab. Returns null when the bridge has no
     * provider, no browser tab is active, or the page-content extraction
     * itself returned null.
     */
    suspend fun snapshotCurrent(): JsonObject? {
        val p = provider ?: return null
        val m = currentMethod ?: return null
        val raw = runCatching {
            @Suppress("UNCHECKED_CAST")
            (m.invoke(p) as? suspend () -> Any?)?.invoke()
        }.getOrNull() ?: return null
        return encodeAny(raw)
    }

    /**
     * Snapshot a specific tab by its host tab id. Returns null when the
     * bridge has no provider, the tab is not a browser tab, or no longer
     * exists.
     */
    suspend fun snapshotByTabId(tabId: String): JsonObject? {
        val p = provider ?: return null
        val m = byTabIdMethod ?: return null
        val raw = runCatching {
            @Suppress("UNCHECKED_CAST")
            (m.invoke(p, tabId) as? suspend (String) -> Any?)?.invoke(tabId)
        }.getOrNull() ?: return null
        return encodeAny(raw)
    }

    /**
     * Subscribe to page-content changes. Returns an empty flow when the
     * bridge has no provider or the host type has no `observe()` method.
     */
    fun observeContent(): Flow<JsonObject?> {
        val p = provider ?: return kotlinx.coroutines.flow.flowOf<JsonObject?>(null)
        val m = observeMethod ?: return kotlinx.coroutines.flow.flowOf<JsonObject?>(null)
        val raw = runCatching { m.invoke(p) }.getOrNull()
            ?: return kotlinx.coroutines.flow.flowOf<JsonObject?>(null)
        val upstream = raw as? kotlinx.coroutines.flow.Flow<*>
            ?: return kotlinx.coroutines.flow.flowOf<JsonObject?>(null)
        return kotlinx.coroutines.flow.flow {
            upstream.collect { emit(encodeAny(it)) }
        }
    }

    private fun encodeAny(any: Any?): JsonObject? {
        if (any == null) return null
        val element = when (any) {
            is JsonObject -> any
            is JsonElement -> any as? JsonObject
            is String -> runCatching { json.parseToJsonElement(any) }.getOrNull() as? JsonObject
            else -> runCatching {
                json.parseToJsonElement(json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(any.toString())))
            }.getOrNull() as? JsonObject
        } ?: return null
        return element
    }

    /**
     * Render [snapshot] back to a single JSON string suitable for the
     * store. Returns null when the snapshot is null or larger than
     * [BookmarksStore.MAX_SNAPSHOT_BYTES].
     */
    fun serialize(snapshot: JsonObject?): String? {
        if (snapshot == null) return null
        val encoded = json.encodeToString(JsonObject.serializer(), snapshot)
        if (encoded.toByteArray(Charsets.UTF_8).size > BookmarksStore.MAX_SNAPSHOT_BYTES) return null
        return encoded
    }

    companion object {
        /** Fully qualified name of the page-content provider interface. */
        const val API_CLASS_NAME = "ai.rever.boss.plugin.dynamic.pagecontent.api.PageContentProvider"
    }
}

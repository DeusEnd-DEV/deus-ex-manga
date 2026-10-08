package eu.kanade.tachiyomi.extension.es.emperorscan

import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.array
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.getPreferences
import keiyoushi.utils.long
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class EmperorScan :
    KeiSource(),
    ConfigurableSource {

    private val baseHost get() = baseUrl.toHttpUrl().host

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2) { it.host == baseHost }

    override suspend fun getPopularManga(page: Int) = getCatalog("$baseUrl/manga/", page, "vistas")

    override suspend fun getLatestUpdates(page: Int) = getCatalog("$baseUrl/manga/", page, "reciente")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/buscar".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return client.get(url).inertia<CatalogProps>().toMangasPage()
        }

        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        val order = filters.firstInstanceOrNull<OrderFilter>()?.toUriPart() ?: "vistas"
        val listUrl = if (genre.isEmpty()) "$baseUrl/manga/" else "$baseUrl/manga-genre/$genre/"
        return getCatalog(listUrl, page, order)
    }

    private suspend fun getCatalog(listUrl: String, page: Int, order: String): MangasPage {
        val url = (if (page > 1) "${listUrl}page/$page/" else listUrl).toHttpUrl().newBuilder()
            .addQueryParameter("orden", order)
            .build()
        return client.get(url).inertia<CatalogProps>().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseHost || url.pathSegments.firstOrNull() != "manga" || url.pathSize < 2) return null
        return client.get("$baseUrl/manga/${url.pathSegments[1]}/").inertia<SeriesProps>().series.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(baseUrl + manga.url, ensureSuccess = false)
        val props = when {
            response.isSuccessful -> response.inertia<SeriesProps>()
            // Some series got a new slug when the site moved off Madara.
            response.code == 404 -> {
                response.close()
                client.get(baseUrl + findCurrentPath(manga.title)).inertia<SeriesProps>()
            }
            else -> {
                response.close()
                throw Exception("HTTP error ${response.code}")
            }
        }

        val chapterList = buildList {
            addAll(props.chapters)
            for (page in 2..props.chaptersPagination.last) {
                val url = baseUrl + props.chaptersPagination.json.replace("__PAGE__", page.toString())
                addAll(client.get(url).parseAs<ChapterPageDto>().items)
            }
        }.filterNot { removeLocked && it.locked }.map { it.toSChapter() }

        return SMangaUpdate(props.series.toSManga(), chapterList)
    }

    private suspend fun findCurrentPath(title: String): String {
        val url = "$baseUrl/buscar".toHttpUrl().newBuilder().addQueryParameter("q", title).build()
        return client.get(url).inertia<CatalogProps>().let { catalog ->
            catalog.toMangasPage().mangas.firstOrNull { it.title.normalized() == title.normalized() }?.url
        } ?: throw Exception("Manga no encontrado; puede haber sido eliminado del sitio")
    }

    private fun String.normalized() = lowercase().replace(NON_ALPHANUMERIC_REGEX, "")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val props = document.inertia<ReaderProps>()
        if (props.chapter.locked) return emptyList()

        val pages = props.pages.associateBy { it.n }.toMutableMap()
        props.reader?.let { reader ->
            val manifest = ReaderCrypto.decryptManifest(reader.x, rootKey(document), baseHost, props.chapter.id)
            fetchManifestPages(manifest, props.chapter.id, props.series.slug).forEach { pages[it.n] = it }
        }

        return pages.values.sortedBy { it.n }.mapIndexed { i, page ->
            Page(i, imageUrl = page.imageUrl()!!)
        }
    }

    private suspend fun fetchManifestPages(manifest: ReaderManifestDto, chapterId: Long, slug: String): List<PageDto> {
        val url = "$baseUrl/${manifest.e}/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter(manifest.p[0], slug)
            .addQueryParameter(manifest.p[2], manifest.q)
            .build()
        val response = client.get(url, jsonHeaders, ensureSuccess = false)
        if (response.code == 428) {
            response.close()
            throw Exception("La web pide verificación anti-bots; abre el capítulo en WebView y vuelve a intentarlo")
        }
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP error ${response.code}")
        }

        val root = response.parseAs<JsonElement>()
        // The wrapping changes with the manifest version: plain object, [c, e, d, s] array, or {"r": {...}}.
        val payload = when (manifest.v / 3) {
            1 -> root.array.let { mapOf("c" to it[0], "d" to it[2]) }
            2 -> root["r"]!!.let { mapOf("c" to it["c"]!!, "d" to it["d"]!!) }
            else -> mapOf("c" to root["c"]!!, "d" to root["d"]!!)
        }
        check(payload["c"]!!.long == chapterId) { "Manifest for a different chapter" }
        return ReaderCrypto.decodePages(payload["d"]!!.string, manifest.v % 3, manifest.k)
    }

    private val jsonHeaders by lazy { headers.newBuilder().set("Accept", "application/json").build() }

    @Volatile
    private var cachedRootKey: Pair<String, ByteArray>? = null

    private suspend fun rootKey(document: Document): ByteArray {
        val appUrl = document.selectFirst("script[type=module][src*=/app-]")!!.absUrl("src")
        cachedRootKey?.takeIf { it.first == appUrl }?.let { return it.second }
        val appJs = client.get(appUrl).use { it.body.string() }
        return ReaderCrypto.deriveRootKey(appJs).also { cachedRootKey = appUrl to it }
    }

    private inline fun <reified T> Response.inertia(): T = asJsoup().inertia()

    private inline fun <reified T> Document.inertia(): T = selectFirst("script[data-page=app]")!!.data().parseAs<InertiaPage<T>>().props

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Los filtros se ignoran al buscar por texto."),
        OrderFilter(),
        GenreFilter(),
    )

    private val preferences by lazy { getPreferences() }

    private val removeLocked get() = preferences.getBoolean(REMOVE_PREMIUM_CHAPTERS, REMOVE_PREMIUM_CHAPTERS_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = REMOVE_PREMIUM_CHAPTERS
            title = "Filtrar capítulos VIP"
            summary = "Oculta los capítulos bloqueados (VIP)"
            setDefaultValue(REMOVE_PREMIUM_CHAPTERS_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Para aplicar los cambios, actualiza la lista de capítulos", Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val REMOVE_PREMIUM_CHAPTERS = "removePremiumChapters"
        private const val REMOVE_PREMIUM_CHAPTERS_DEFAULT = true
        private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]")
    }
}

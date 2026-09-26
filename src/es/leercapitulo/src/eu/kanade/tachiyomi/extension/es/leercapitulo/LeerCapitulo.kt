package eu.kanade.tachiyomi.extension.es.leercapitulo

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapitulo : HttpSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(1, 3.seconds) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.lc-slide").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.lc-slide-name")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.text()
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article.lc-release").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.lc-release-title")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.text()
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            filters.filterIsInstance<UriPartFilter>().forEach { filter ->
                filter.toUriPart().takeIf { it.isNotEmpty() }?.let { addQueryParameter(filter.param, it) }
            }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article.lc-card").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.lc-card-name")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.text()
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }

        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Los filtros se pueden combinar entre ellos y con la búsqueda por texto."),
        GenreFilter(),
        ThemeFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            author = document.fact("Autor")?.text()
            artist = document.fact("Dibujo")?.text()

            val altNames = document.selectFirst("h1 + p.lc-muted")?.text()
            val desc = document.selectFirst("#sinopsis p")?.wholeText()?.trim()
            description = buildString {
                if (!desc.isNullOrEmpty()) append(desc)
                if (!altNames.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Títulos alternativos: ")
                    append(altNames)
                }
            }

            genre = (
                document.select("a.badge[href*='?genre='], a.badge[href*='?theme=']").map { it.text() } +
                    listOfNotNull(document.fact("Tipo")?.text())
                ).distinct().joinToString()
            status = document.fact("Estado")?.text()?.toStatus() ?: SManga.UNKNOWN
            thumbnail_url = document.selectFirst(".lc-cover-lg img")?.imgAttr()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapterList > a.lc-chapter-row").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                name = element.selectFirst("span.n")!!.text()
                date_upload = dateFormat.tryParse(element.selectFirst("span.d")?.text())
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#lcPages > img[data-index]")
            .sortedBy { it.attr("data-index").toIntOrNull() ?: 0 }
            .mapIndexed { i, img -> Page(i, imageUrl = img.imgAttr()) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Los datos de la ficha están en <li><span class="k">Etiqueta</span><valor></li>.
    private fun Element.fact(label: String): Element? = selectFirst("ul.lc-facts > li:has(> span.k:containsOwn($label)) > :not(.k)")

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun String.toStatus() = when (this) {
        "Ongoing" -> SManga.ONGOING
        "Paused" -> SManga.ON_HIATUS
        "Completed" -> SManga.COMPLETED
        "Cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}

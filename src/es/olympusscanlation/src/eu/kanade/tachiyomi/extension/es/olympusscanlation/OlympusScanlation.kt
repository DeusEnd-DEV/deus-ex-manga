package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.text.Normalizer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OlympusScanlation :
    KeiSource(),
    ConfigurableSource {

    private fun fetchedDomainUrl() {
        if (!preferences.fetchDomainPref()) return
        try {
            val initClient = network.client
            val document = initClient.newCall(GET("https://olympus.pages.dev", headers)).execute().asJsoup()
            val domain = document.selectFirst("meta[property=og:url]")?.attr("content")
                ?: return
            val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
            val newDomain = "https://$host"
            preferences.edit().putString(BASE_URL_PREF, newDomain).apply()
        } catch (_: Exception) {
            return
        }
    }

    // Fuente con el ID de la versión 2, cuyos mangas se guardaron como /series/comic-<slug>: se enlazan por slug o título.
    private val legacy get() = id == LEGACY_ID

    private fun List<MangaDto>.findLegacy(manga: SManga): MangaDto? {
        val slug = manga.url.substringAfterLast("/").removePrefix("comic-")
        val title = manga.title.normalized()
        return firstOrNull { it.slug.replace(TIMESTAMP_SUFFIX, "") == slug } ?: firstOrNull { it.name.normalized() == title }
    }

    private fun resolveLegacy(manga: SManga) = seriesList.findLegacy(manga)
        ?: throw Exception("No se encontró \"${manga.title}\" en Olympus; migra el manga a mano")

    private fun String.normalized() = Normalizer.normalize(this, Normalizer.Form.NFD).lowercase().filter { it.isLetterOrDigit() && it.code < 128 }

    private val apiBaseUrl get() = baseUrl.replace("https://", "https://panel.")

    private val preferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        fetchedDomainUrl()
        return rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
            .rateLimit(2, 1.seconds) { it.host == apiBaseUrl.toHttpUrl().host }
    }

    private var seriesList: List<MangaDto> = emptyList()

    private var lastFetchTime: Long = 0L

    private val seriesListMutex = Mutex()

    private suspend fun fetchSeriesList() = seriesListMutex.withLock {
        val now = System.currentTimeMillis()

        if (seriesList.isNotEmpty() && (now - lastFetchTime) < CACHE_DURATION_MS) {
            return@withLock
        }

        val series = client.get("$baseUrl/api/series/list").parseAs<PayloadMangaDto>()

        val comics = series.data.asSequence()
            .filter { it.type == "comic" }
            .toList()

        seriesList = comics
        lastFetchTime = now

        val newSlugMap = comics.associate { it.id to it.slug }

        preferences.slugMap += newSlugMap
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (legacy) return MangasPage(emptyList(), false)
        fetchSeriesList()
        val result = client.get("$baseUrl/api/rankings?page=$page&period=total_ranking").parseAs<RankingDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data
            .filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, hasNextPage = result.hasNextPage())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (legacy) return MangasPage(emptyList(), false)
        fetchSeriesList()
        val result = client.get("$baseUrl/api/new-chapters?page=$page").parseAs<NewChaptersDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data.filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, result.hasNextPage())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (legacy) return MangasPage(emptyList(), false)
        fetchSeriesList()
        val filteredList = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        val paginatedList = filteredList.drop((page - 1) * 20).take(20)
        val hasNextPage = page * 20 < filteredList.size
        return MangasPage(paginatedList.map { it.toSManga() }, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String {
        if (legacy) return baseUrl + "/series/comic-" + (seriesList.findLegacy(manga)?.slug ?: manga.url.substringAfterLast("/").removePrefix("comic-"))
        val slug = preferences.slugMap[manga.url.toInt()]!!
        return "$baseUrl/series/comic-$slug"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        fetchSeriesList()
        val mangaId = if (legacy) resolveLegacy(manga).id.toString() else manga.url
        val slug = preferences.slugMap[mangaId.toInt()]!!

        return coroutineScope {
            val details = async {
                if (fetchDetails) {
                    client.get("$baseUrl/api/series/$slug?type=comic")
                        .parseAs<MangaDetailDto>().data.toSMangaDetails()
                } else {
                    manga
                }
            }
            val chapterList = async {
                if (fetchChapters) fetchChapterList(slug, mangaId) else chapters
            }
            SMangaUpdate(details.await().also { if (legacy) it.url = manga.url }, chapterList.await())
        }
    }

    private suspend fun fetchChapterList(mangaSlug: String, mangaId: String): List<SChapter> {
        val data = fetchChapterPage(mangaSlug, 1)
        val result = data.data.toMutableList()
        var page = 2
        while (data.meta.total > result.size) {
            val newData = fetchChapterPage(mangaSlug, page)
            result += newData.data
            page += 1
        }
        return result.map { it.toSChapter(mangaId) }
    }

    private suspend fun fetchChapterPage(mangaSlug: String, page: Int): PayloadChapterDto = client.get("$apiBaseUrl/api/series/$mangaSlug/chapters?page=$page&direction=desc&type=comic")
        .parseAs<PayloadChapterDto>()

    override fun getChapterUrl(chapter: SChapter): String {
        check(CHAPTER_URL.matches(chapter.url)) { "Actualiza la lista de capítulos" }
        val mangaId = chapter.url.substringBefore("/")
        val chapterId = chapter.url.substringAfter("/")
        val mangaSlug = preferences.slugMap[mangaId.toInt()]!!
        return "$baseUrl/capitulo/$chapterId/comic-$mangaSlug"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        check(CHAPTER_URL.matches(chapter.url)) { "Actualiza la lista de capítulos" }
        val mangaId = chapter.url.substringBefore("/")
        val chapterId = chapter.url.substringAfter("/")
        val mangaSlug = preferences.slugMap[mangaId.toInt()]!!

        return client.get("$baseUrl/api/capitulo/comic-$mangaSlug/$chapterId")
            .parseAs<PayloadPagesDto>().chapter.pages.mapIndexed { i, img ->
                Page(i, imageUrl = img)
            }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = "Buscar dominio automáticamente"
            summary = "Intenta buscar el dominio automáticamente al abrir la fuente."
            setDefaultValue(true)
        }.also { screen.addPreference(it) }
    }

    private fun SharedPreferences.fetchDomainPref() = getBoolean(FETCH_DOMAIN_PREF, true)

    private var slugMapCache: Map<Int, String>? = null
    private var SharedPreferences.slugMap: Map<Int, String>
        get() {
            slugMapCache?.let { return it }
            val json = getString(SLUG_MAP, "{}")!!
            slugMapCache = try {
                json.parseAs<Map<Int, String>>()
            } catch (_: SerializationException) {
                emptyMap()
            }
            return slugMapCache!!
        }
        set(map) {
            slugMapCache = map
            edit().putString(SLUG_MAP, map.toJsonString()).apply()
        }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val FETCH_DOMAIN_PREF = "fetchDomain"

        private const val SLUG_MAP = "slugMap"

        private const val LEGACY_ID = 1163124599525658616L
        private val TIMESTAMP_SUFFIX = Regex("""-\d{8}-\d{9}$""")
        private val CHAPTER_URL = Regex("""\d+/\d+""")

        private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    }
}

package eu.kanade.tachiyomi.extension.all.ninemanga

import eu.kanade.tachiyomi.extension.all.niadd.Niadd
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import java.net.URLDecoder

@Source
abstract class NineManga : Niadd() {
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.legacyPath()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.legacyPath()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val update = super.fetchMangaUpdate(
            SManga.create().apply { url = manga.url.legacyPath() },
            chapters,
            fetchDetails,
            fetchChapters,
        )
        val details = if (fetchDetails) update.manga.apply { url = manga.url } else manga
        return SMangaUpdate(details, update.chapters)
    }

    // NineManga guardaba las rutas codificadas como formulario ("Tu+talento+es+mio.html", "don%27t"),
    // pero niadd.com solo las reconoce con espacios y apóstrofos normales: se decodifican y OkHttp las
    // vuelve a codificar como un navegador. Las rutas propias de niadd no llevan '+' ni '%'.
    private fun String.legacyPath(): String {
        if ('+' !in this && '%' !in this) return this
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }
}

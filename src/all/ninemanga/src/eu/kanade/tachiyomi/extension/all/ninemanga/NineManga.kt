package eu.kanade.tachiyomi.extension.all.ninemanga

import eu.kanade.tachiyomi.extension.all.niadd.Niadd
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.Request
import java.net.URLDecoder

@Source
abstract class NineManga : Niadd() {
    override fun mangaDetailsRequest(manga: SManga): Request = super.mangaDetailsRequest(SManga.create().apply { url = manga.url.legacyPath() })

    override fun chapterListRequest(manga: SManga): Request = super.chapterListRequest(SManga.create().apply { url = manga.url.legacyPath() })

    override fun pageListRequest(chapter: SChapter): Request = super.pageListRequest(SChapter.create().apply { url = chapter.url.legacyPath() })

    // NineManga guardaba las rutas codificadas como formulario ("Tu+talento+es+mio.html", "don%27t"),
    // pero niadd.com solo las reconoce con espacios y apóstrofos normales: se decodifican y OkHttp las
    // vuelve a codificar como un navegador. Las rutas propias de niadd no llevan '+' ni '%'.
    private fun String.legacyPath(): String {
        if ('+' !in this && '%' !in this) return this
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }
}

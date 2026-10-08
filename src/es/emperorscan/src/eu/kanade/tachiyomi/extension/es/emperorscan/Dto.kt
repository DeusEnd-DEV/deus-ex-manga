package eu.kanade.tachiyomi.extension.es.emperorscan

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Inertia.js page object embedded in every HTML page as <script data-page="app">.
@Serializable
class InertiaPage<T>(val props: T)

@Serializable
class CatalogProps(
    private val series: List<SeriesDto>,
    private val pagination: PaginationDto? = null,
) {
    fun toMangasPage() = MangasPage(series.map { it.toSManga() }, pagination?.next != null)
}

@Serializable
class PaginationDto(val next: String? = null)

@Serializable
class SeriesDto(
    val slug: String,
    val url: String,
    val title: String,
    private val type: String? = null,
    private val status: String? = null,
    private val cover: String? = null,
    @SerialName("cover_lg") private val coverLg: String? = null,
    @SerialName("alt_titles") private val altTitles: List<String> = emptyList(),
    private val synopsis: String? = null,
    private val genres: List<NamedDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = this@SeriesDto.url
        title = this@SeriesDto.title
        thumbnail_url = coverLg ?: cover
        description = buildString {
            synopsis?.takeIf { it.isNotBlank() }?.let(::append)
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ")
                append(altTitles.joinToString())
            }
        }
        genre = (listOfNotNull(type?.replaceFirstChar { it.uppercase() }) + genres.map { it.name }).joinToString()
        status = this@SeriesDto.status.toStatus()
    }

    private fun String?.toStatus() = when (this?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed", "finished" -> SManga.COMPLETED
        "hiatus", "paused", "on_hold" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

@Serializable
class NamedDto(val name: String)

@Serializable
class SeriesProps(
    val series: SeriesDto,
    val chapters: List<ChapterDto>,
    @SerialName("chapters_pagination") val chaptersPagination: ChaptersPaginationDto,
)

@Serializable
class ChaptersPaginationDto(
    val last: Int,
    val json: String,
)

@Serializable
class ChapterPageDto(val items: List<ChapterDto>)

@Serializable
class ChapterDto(
    private val url: String,
    private val label: String,
    private val title: String? = null,
    @SerialName("published_at") private val publishedAt: String? = null,
    val locked: Boolean = false,
) {
    fun toSChapter() = SChapter.create().apply {
        url = this@ChapterDto.url
        name = if (title.isNullOrBlank()) label else "$label - $title"
        date_upload = Instant.tryParse(publishedAt)
    }
}

@Serializable
class ReaderProps(
    val series: ReaderSeriesDto,
    val chapter: ReaderChapterDto,
    val pages: List<PageDto>,
    val reader: ReaderDto? = null,
)

@Serializable
class ReaderSeriesDto(val slug: String)

@Serializable
class ReaderChapterDto(
    val id: Long,
    val locked: Boolean = false,
)

@Serializable
class ReaderDto(val x: String)

@Serializable
class PageDto(
    val n: Int,
    private val src: String? = null,
    private val srcset: String? = null,
) {
    // srcset lists "url 600w, url 800w"; take the widest candidate.
    fun imageUrl(): String? = srcset
        ?.split(",")
        ?.map { it.trim().split(" ") }
        ?.maxByOrNull { it.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0 }
        ?.first()
        ?: src
}

@Serializable
class ReaderManifestDto(
    val e: String,
    val p: List<String>,
    val v: Int,
    val k: String,
    val q: String,
)

package eu.kanade.tachiyomi.extension.es.emperorscan

import eu.kanade.tachiyomi.multisrc.imperiomanhwa.ImperioManhwa
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class EmperorScan : ImperioManhwa() {
    override val searchPath = "buscar"

    // The theme's 2 req/s gets HTTP 429 halfway through a library update.
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 3.seconds) { it.host == baseUrl.toHttpUrl().host }
}

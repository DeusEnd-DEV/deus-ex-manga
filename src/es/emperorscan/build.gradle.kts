import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Emperor Scan"
    // Was 71 while built on the Madara theme; must not go below it.
    versionCode = 72
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://imperiomanhwa.com"
    }

    deeplink {
        path("/manga/..*")
    }
}

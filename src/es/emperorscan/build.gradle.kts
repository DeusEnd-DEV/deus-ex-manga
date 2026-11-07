import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Emperor Scan"
    theme = "imperiomanhwa"
    versionCode = 75
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://imperiomanhwa.com"
    }
}

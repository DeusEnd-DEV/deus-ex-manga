import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Olympus Scanlation"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl {
            custom("https://olympusxyz.com")
        }
        versionId = 3
    }

    // Versión 2 de la fuente: solo para bibliotecas antiguas, enlaza cada manga con el actual.
    source {
        name = "Olympus Scanlation (antigua)"
        lang = "es"
        baseUrl {
            custom("https://olympusxyz.com")
        }
        id = 1163124599525658616L
    }
}

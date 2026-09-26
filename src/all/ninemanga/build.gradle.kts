import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// NineManga se mudó a niadd.com y Keiyoushi la eliminó en favor de su extensión Niadd.
// Este módulo compila el código de Niadd bajo el paquete y los IDs de fuente de la antigua
// NineManga, para que las bibliotecas que la usaban sigan funcionando sin migrar: niadd.com
// acepta las mismas rutas (/manga/Nombre%20Del%20Manga.html) que guardaba NineManga.
val niaddVersionCode = Regex("""versionCode\s*=\s*(\d+)""")
    .find(file("../niadd/build.gradle.kts").readText())!!.groupValues[1].toInt()

// Súbelo en 1 con cada cambio propio de este módulo; los cambios de Niadd suman solos.
val ownRevision = 1

keiyoushi {
    name = "NineManga"
    versionCode = 1000 * ownRevision + niaddVersionCode
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    // IDs de la antigua NineManga: MD5 de "ninemanga<xx>/<idioma>/1", salvo pt-BR, que era fijo.
    source {
        name = "NineManga"
        lang = "en"
        baseUrl = "https://www.niadd.com"
        id = 120391793502126753L
    }
    source {
        name = "NineManga"
        lang = "es"
        baseUrl = "https://es.niadd.com"
        id = 4097111295486074350L
    }
    source {
        name = "NineManga"
        lang = "pt-BR"
        baseUrl = "https://br.niadd.com"
        id = 7162569729467394726L
    }
    source {
        name = "NineManga"
        lang = "ru"
        baseUrl = "https://ru.niadd.com"
        id = 6531002958728774729L
    }
    source {
        name = "NineManga"
        lang = "de"
        baseUrl = "https://de.niadd.com"
        id = 8454920920230076882L
    }
    source {
        name = "NineManga"
        lang = "it"
        baseUrl = "https://it.niadd.com"
        id = 4312023446566221911L
    }
    source {
        name = "NineManga"
        lang = "fr"
        baseUrl = "https://fr.niadd.com"
        id = 3415116372640359218L
    }
}

// Se compila una copia del código de Niadd sin su @Source para que la fuente sea la subclase NineManga,
// que corrige las rutas antiguas. La copia se regenera en cada build, así hereda los cambios de Niadd.
val niaddCopy = layout.buildDirectory.dir("generated/niadd").get().asFile
val niaddSrc = file("../niadd/src")
niaddCopy.deleteRecursively()
niaddSrc.walk().filter { it.isFile && it.extension == "kt" }.forEach { source ->
    val target = niaddCopy.resolve(source.relativeTo(niaddSrc))
    target.parentFile.mkdirs()
    target.writeText(
        source.readText()
            .replace(Regex("""@Source\s+abstract class Niadd\b"""), "abstract class Niadd")
            .replace(Regex("""import keiyoushi\.annotation\.Source\r?\n"""), ""),
    )
}

android {
    sourceSets {
        named("main") {
            kotlin.directories.add(niaddCopy.path)
        }
    }
}

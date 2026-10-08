package eu.kanade.tachiyomi.extension.es.emperorscan

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
    Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class OrderFilter :
    UriPartFilter(
        "Ordenar por",
        arrayOf(
            "Más vistas" to "vistas",
            "Actualizadas recientemente" to "reciente",
            "Nuevas" to "nuevas",
            "Mejor valoradas" to "valoradas",
            "Más seguidas" to "seguidas",
            "Alfabético" to "alfabetico",
        ),
    )

class GenreFilter :
    UriPartFilter(
        "Género",
        arrayOf(
            "Todos" to "",
            "+18" to "18",
            "Academia" to "academia",
            "Acción" to "accion",
            "Amigos con derechos" to "amigos-con-derechos",
            "Amigos de la infancia" to "amigos-de-la-infancia",
            "Apocaliptico" to "apocaliptico",
            "Artes marciales" to "artes-marciales",
            "Aventura" to "aventura",
            "Ciencia ficción" to "ciencia-ficcion",
            "Comedia" to "comedia",
            "Cultivo" to "cultivo",
            "Demonios" to "demonios",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Familia" to "familia",
            "Fantasía" to "fantasia",
            "Fetiches" to "fetiches",
            "Girls love" to "girls-love",
            "Gore" to "gore",
            "Harem" to "harem",
            "Historias cortas" to "historias-cortas",
            "Histórico" to "historico",
            "Horror" to "horror",
            "Infidelidad" to "infidelidad",
            "Intercambio de parejas" to "intercambio-de-parejas",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Madrastra" to "madrastra",
            "Madre" to "madre",
            "Madre e hija" to "madre-e-hija",
            "Magia" to "magia",
            "Milf" to "milf",
            "Misterio" to "misterio",
            "Mujer casada" to "mujer-casada",
            "Mujer mayor" to "mujer-mayor",
            "NTR" to "ntr",
            "Pareja casada" to "pareja-casada",
            "Primer amor" to "primer-amor",
            "Psicológico" to "psicologico",
            "Recuentos de la vida" to "recuentos-de-la-vida",
            "Reencarnacion" to "reencarnacion",
            "Regresión" to "regresion",
            "Relacion secreta" to "relacion-secreta",
            "Romance" to "romance",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Sin censura" to "sin-censura",
            "Sistema" to "sistema",
            "Sobrenatural" to "sobrenatural",
            "Superpoderes" to "superpoderes",
            "Supervivencia" to "supervivencia",
            "Thriller" to "thriller",
            "Tragedia" to "tragedia",
            "Traición" to "traicion",
            "Universidad" to "universidad",
            "Venganza" to "venganza",
            "Vida escolar" to "vida-escolar",
        ),
    )

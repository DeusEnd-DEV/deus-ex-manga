package eu.kanade.tachiyomi.extension.es.emperorscan

import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The reader only ships the first page; the rest come from a manifest endpoint described by
 * `props.reader.x`, which is XOR-encrypted with an HMAC-SHA256 keystream. The root key is built
 * by an obfuscated function in the site's app bundle from a constant array and
 * `document.documentElement.tagName`, so it's derived from the bundle instead of hardcoded.
 */
object ReaderCrypto {
    private val keyFunctionRegex = Regex(
        """function \w+\(\)\{const (\w+)=new Uint8Array\(32\),\w+=document\.documentElement\.tagName;return (.*?),\1\}""",
    )
    private val arrayNameRegex = Regex("""=\(?(\w+)\[\d+]""")
    private val assignmentRegex = Regex("""^(\w+)\[(\d+)]=(.*)$""")

    fun deriveRootKey(appJs: String): ByteArray {
        val (keyVar, body) = keyFunctionRegex.find(appJs)?.destructured
            ?: throw Exception("No se encontró la clave del lector de Emperor Scan")
        val arrayName = arrayNameRegex.find(body)!!.groupValues[1]
        val constants = Regex("""const $arrayName=\[([\d,]+)]""").find(appJs)!!.groupValues[1]
            .split(",").map { it.toLong() }

        val key = ByteArray(32)
        body.split(",$keyVar[").forEachIndexed { i, part ->
            val (_, index, expr) = assignmentRegex.find(if (i == 0) part else "$keyVar[$part")!!.destructured
            key[index.toInt()] = KeyExpression(expr, constants).evaluate().toByte()
        }
        return key
    }

    fun decryptManifest(x: String, rootKey: ByteArray, host: String, chapterId: Long): ReaderManifestDto {
        val chapterKey = hmac(rootKey, "rk|${host.lowercase()}|$chapterId")
        val data = base64Url(x)
        for (block in 0 until (data.size + 31) / 32) {
            val stream = hmac(chapterKey, "ks|$block")
            for (i in 0 until 32) {
                val pos = block * 32 + i
                if (pos >= data.size) break
                data[pos] = (data[pos].toInt() xor stream[i].toInt()).toByte()
            }
        }
        return data.decodeToString().parseAs()
    }

    // Mode 1: base64 JSON, mode 2: base64 XORed with the manifest's hex key, otherwise plain JSON.
    fun decodePages(data: String, mode: Int, hexKey: String): List<PageDto> {
        val json = when (mode) {
            1 -> base64Url(data).decodeToString()
            2 -> {
                val key = hexKey.decodeHex()
                val bytes = base64Url(data)
                for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
                bytes.decodeToString()
            }
            else -> data
        }
        return json.parseAs()
    }

    private fun base64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').trimEnd('='))

    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message.encodeToByteArray())
    }
}

/**
 * Evaluates the bundle's key expressions, e.g. `(ae[53]-ae[59]-41^r.charCodeAt(2))&255`.
 * JS precedence: `+`/`-` bind tighter than `&`, which binds tighter than `^`.
 */
private class KeyExpression(private val src: String, private val constants: List<Long>) {
    private var pos = 0

    fun evaluate(): Long = xor().also { check(pos == src.length) { "Unexpected key expression: $src" } }

    private fun xor(): Long {
        var v = and()
        while (peek('^')) {
            pos++
            v = v xor and()
        }
        return v
    }

    private fun and(): Long {
        var v = additive()
        while (peek('&')) {
            pos++
            v = v and additive()
        }
        return v
    }

    private fun additive(): Long {
        var v = atom()
        while (true) {
            when {
                peek('+') -> {
                    pos++
                    v += atom()
                }
                peek('-') -> {
                    pos++
                    v -= atom()
                }
                else -> return v
            }
        }
    }

    private fun atom(): Long {
        if (peek('(')) {
            pos++
            val v = xor()
            pos++
            return v
        }
        ARRAY_REGEX.matchAt(src, pos)?.let {
            pos = it.range.last + 1
            return constants[it.groupValues[1].toInt()]
        }
        CHAR_CODE_REGEX.matchAt(src, pos)?.let {
            pos = it.range.last + 1
            return TAG_NAME[it.groupValues[1].toInt()].code.toLong()
        }
        NUMBER_REGEX.matchAt(src, pos)?.let {
            pos = it.range.last + 1
            return it.value.toLong()
        }
        throw Exception("Unexpected key expression: $src")
    }

    private fun peek(c: Char) = pos < src.length && src[pos] == c

    companion object {
        private const val TAG_NAME = "HTML"
        private val ARRAY_REGEX = Regex("""\w+\[(\d+)]""")
        private val CHAR_CODE_REGEX = Regex("""\w+\.charCodeAt\((\d+)\)""")
        private val NUMBER_REGEX = Regex("""\d+""")
    }
}

package ru.drdroid78.sberboom.protocol

import android.util.Base64
import org.json.JSONObject

/**
 * Разбор backend-JWT без проверки подписи.
 *
 * Прошивка динамика проверяет RS256-подпись публичным ключом Sber и срок
 * действия. Здесь мы показываем только содержимое токена, чтобы понять,
 * годится ли он: alg, exp, sub, aud, scopes.
 */
object JwtInfo {

    data class Info(
        val alg: String? = null,
        val exp: Long? = null,
        val iat: Long? = null,
        val sub: String? = null,
        val aud: String? = null,
        val scopes: String? = null,
        val error: String? = null
    )

    fun parse(token: String): Info {
        val parts = token.trim().removePrefix("Bearer ").trim().split('.')
        if (parts.size != 3) return Info(error = "не JWT: ожидается 3 части")
        return try {
            val header = JSONObject(decodePart(parts[0]))
            val payload = JSONObject(decodePart(parts[1]))
            Info(
                alg = header.optString("alg").takeIf { it.isNotBlank() },
                exp = payload.optLong("exp").takeIf { it > 0 },
                iat = payload.optLong("iat").takeIf { it > 0 },
                sub = payload.optString("sub").takeIf { it.isNotBlank() },
                aud = payload.opt("aud")?.toString(),
                scopes = payload.opt("scopes")?.toString()
                    ?: payload.opt("scope")?.toString()
            )
        } catch (t: Throwable) {
            Info(error = "не удалось разобрать: ${t.message}")
        }
    }

    /** Local format/expiry checks only. The speaker still verifies signature and rights. */
    fun validationError(token: String?, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        if (token.isNullOrBlank()) return "Backend-JWT required"
        val info = parse(token)
        return TokenPolicy.error(info.alg, info.exp, info.error, nowSec)
    }

    fun describe(token: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val info = parse(token)
        if (info.error != null) return info.error
        return buildString {
            append("alg=").append(info.alg ?: "?")
            val exp = info.exp
            if (exp != null) {
                val left = exp - nowSec
                if (left <= 0) {
                    append(", ПРОСРОЧЕН")
                } else {
                    append(", осталось ").append(left / 60).append(" мин")
                }
            } else {
                append(", exp нет")
            }
            info.sub?.let { append(", sub=").append(it.take(24)) }
            info.aud?.let { append(", aud=").append(it.take(24)) }
            info.scopes?.let { append(", scopes=").append(it.take(40)) }
        }
    }

    private fun decodePart(part: String): String {
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return String(bytes, Charsets.UTF_8)
    }
}

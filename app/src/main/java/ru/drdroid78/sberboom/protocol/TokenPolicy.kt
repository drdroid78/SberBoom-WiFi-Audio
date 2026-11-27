package ru.drdroid78.sberboom.protocol

/** Never implies that a locally parsed token is authorized by the speaker. */
object TokenPolicy {
    fun error(alg: String?, exp: Long?, parseError: String?, nowSec: Long): String? = when {
        parseError != null -> "Invalid JWT format"
        alg != "RS256" -> "Unsupported JWT algorithm"
        exp == null -> "JWT expiry required"
        exp <= nowSec -> "JWT expired"
        else -> null
    }
}

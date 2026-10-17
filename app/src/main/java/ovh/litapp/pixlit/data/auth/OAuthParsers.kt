package ovh.litapp.pixlit.data.auth

import com.google.gson.JsonParser
import ovh.litapp.pixlit.data.api.toSafeString

/**
 * Tolerant parsers for the raw OAuth response bodies Pixelfed returns (DATA-06 step 2 / F-12).
 *
 * Registration and token exchange are parsed by hand rather than through a Gson DTO because the
 * server mixes string and numeric primitives; these functions normalise either to a `String` and
 * fall back to `null`/the raw body instead of throwing.
 */
object OAuthParsers {

    fun parseTokenResponseBody(rawBody: String): String? {
        return try {
            val jsonElement = JsonParser.parseString(rawBody)
            if (jsonElement != null && jsonElement.isJsonObject) {
                jsonElement.asJsonObject.get("access_token").toSafeString()
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun parseRegistrationResponseBody(rawBody: String): Pair<String?, String?> {
        return try {
            val jsonElement = JsonParser.parseString(rawBody)
            if (jsonElement != null && jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val clientId = obj.get("client_id").toSafeString()
                val clientSecret = obj.get("client_secret").toSafeString()
                Pair(clientId, clientSecret)
            } else {
                Pair(null, null)
            }
        } catch (t: Throwable) {
            Pair(null, null)
        }
    }

    fun parseErrorResponseBody(rawErrBody: String): String {
        return try {
            val jsonElement = JsonParser.parseString(rawErrBody)
            if (jsonElement != null && jsonElement.isJsonObject) {
                val jsonObject = jsonElement.asJsonObject

                val errorVal = jsonObject.get("error").toSafeString()
                val descVal = jsonObject.get("error_description").toSafeString()
                val msgVal = jsonObject.get("message").toSafeString()

                when {
                    !errorVal.isNullOrBlank() && !descVal.isNullOrBlank() -> "$errorVal: $descVal"
                    !descVal.isNullOrBlank() -> descVal
                    !errorVal.isNullOrBlank() -> errorVal
                    !msgVal.isNullOrBlank() -> msgVal
                    else -> rawErrBody
                }
            } else {
                rawErrBody
            }
        } catch (t: Throwable) {
            rawErrBody
        }
    }
}

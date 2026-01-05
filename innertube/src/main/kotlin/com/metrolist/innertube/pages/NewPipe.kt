package com.metrolist.innertube

import com.metrolist.innertube.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder

object NewPipeUtils {

    private val json = Json { ignoreUnknownKeys = true }
    
    private val httpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                followRedirects(true)
            }
        }
    }

    // Regex patterns from the old working library
    private val IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN = Regex("player\\\\/([a-z0-9]{8})\\\\/")
    private val EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN = Regex("\"jsUrl\":\"(/s/player/[A-Za-z0-9]+/player_ias\\.vflset/[A-Za-z_-]+/base\\.js)\"")
    private val SIGNATURE_TIMESTAMP_PATTERN = Regex("signatureTimestamp[=:](\\d+)")

    private var cachedPlayerId: String? = null
    private var cachedSignatureTimestamp: Int? = null

    /**
     * Extract player ID (8-char hash) from YouTube - same method as the old working library
     */
    private suspend fun extractPlayerId(videoId: String): String {
        // Try IFrame resource first
        try {
            val iframeContent = httpClient.get("https://www.youtube.com/iframe_api") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }.bodyAsText()
            
            val match = IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN.find(iframeContent)
            if (match != null) {
                return match.groupValues[1]
            }
        } catch (e: Exception) {
            // Fall through to embed page
        }

        // Fallback to embed page
        try {
            val embedContent = httpClient.get("https://www.youtube.com/embed/$videoId") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }.bodyAsText()
            
            val jsUrlMatch = EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN.find(embedContent)
            if (jsUrlMatch != null) {
                val jsUrl = jsUrlMatch.groupValues[1].replace("\\/", "/")
                val hashMatch = IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN.find(jsUrl)
                if (hashMatch != null) {
                    return hashMatch.groupValues[1]
                }
            }
        } catch (e: Exception) {
            // Fall through
        }

        throw Exception("Could not extract player ID")
    }

    /**
     * Extract signature timestamp from the JavaScript player file
     */
    private suspend fun extractSignatureTimestamp(playerId: String): Int {
        val playerUrl = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_GB/base.js"
        val playerCode = httpClient.get(playerUrl) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }.bodyAsText()
        
        val match = SIGNATURE_TIMESTAMP_PATTERN.find(playerCode)
            ?: throw Exception("Could not find signature timestamp")
        
        return match.groupValues[1].toInt()
    }

    private fun ensurePlayerInfo(videoId: String) {
        if (cachedPlayerId == null || cachedSignatureTimestamp == null) {
            runBlocking {
                cachedPlayerId = extractPlayerId(videoId)
                cachedSignatureTimestamp = extractSignatureTimestamp(cachedPlayerId!!)
            }
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        ensurePlayerInfo(videoId)
        cachedSignatureTimestamp ?: throw Exception("Could not get signature timestamp")
    }

    /**
     * Decode a single parameter using the PipePipe API
     */
    private suspend fun decode(playerId: String, paramType: String, value: String): String {
        val encodedValue = URLEncoder.encode(value, "UTF-8")
        val apiUrl = "https://api.pipepipe.dev/decoder/decode?player=$playerId&$paramType=$encodedValue"
        
        val response = httpClient.get(apiUrl) {
            header("User-Agent", "PipePipe/4.7.0")
        }.bodyAsText()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        
        // Check top-level type
        val topType = jsonObj["type"]?.jsonPrimitive?.content
        if (topType != "result") {
            throw Exception("API returned unexpected type: $topType")
        }

        val responses = jsonObj["responses"]?.jsonArray
            ?: throw Exception("No responses in API result")
        
        val firstResponse = responses[0].jsonObject
        val responseType = firstResponse["type"]?.jsonPrimitive?.content
        if (responseType != "result") {
            throw Exception("Response item has unexpected type: $responseType")
        }

        val data = firstResponse["data"]?.jsonObject
            ?: throw Exception("No data in response")
        
        return data[value]?.jsonPrimitive?.content
            ?: throw Exception("Could not find decoded value for: $value")
    }

    /**
     * Decode signature using the API
     */
    private suspend fun decodeSignature(playerId: String, signature: String): String {
        return decode(playerId, "sig", signature)
    }

    /**
     * Decode throttling parameter (n) using the API
     */
    private suspend fun decodeThrottlingParameter(playerId: String, nParam: String): String {
        return decode(playerId, "n", nParam)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            ensurePlayerInfo(videoId)
            val playerId = cachedPlayerId ?: throw Exception("Could not get player ID")

            val url = format.url
            if (url != null) {
                // Direct URL - just need to deobfuscate throttling parameter
                return@runCatching runBlocking {
                    getUrlWithThrottlingParameterDeobfuscated(playerId, url)
                }
            }

            // Cipher URL - need to deobfuscate signature and throttling parameter
            format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw Exception("Could not parse cipher signature")
                val signatureParam = params["sp"] ?: "sig"
                val baseUrl = params["url"]
                    ?: throw Exception("Could not parse cipher url")

                return@runCatching runBlocking {
                    // Decode signature
                    val decodedSignature = decodeSignature(playerId, obfuscatedSignature)
                    
                    // Build URL with decoded signature
                    val urlBuilder = URLBuilder(baseUrl)
                    urlBuilder.parameters[signatureParam] = decodedSignature
                    val urlWithSig = urlBuilder.toString()
                    
                    // Deobfuscate throttling parameter
                    getUrlWithThrottlingParameterDeobfuscated(playerId, urlWithSig)
                }
            } ?: throw Exception("Could not find format url")
        }

    /**
     * Get URL with throttling parameter deobfuscated - same logic as old library
     */
    private suspend fun getUrlWithThrottlingParameterDeobfuscated(
        playerId: String,
        streamingUrl: String
    ): String {
        val nParam = extractNParam(streamingUrl) ?: return streamingUrl
        val decodedNParam = URLDecoder.decode(nParam, "UTF-8")
        
        val deobfuscatedN = decodeThrottlingParameter(playerId, decodedNParam)
        
        return streamingUrl.replace(nParam, URLEncoder.encode(deobfuscatedN, "UTF-8"))
    }

    private fun extractNParam(url: String): String? {
        return try {
            val queryPart = if (url.contains("?")) url.substringAfter("?") else return null
            val params = parseQueryString(queryPart)
            params["n"]
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear all caches - useful when player version changes
     */
    fun clearAllCaches() {
        cachedPlayerId = null
        cachedSignatureTimestamp = null
    }
}
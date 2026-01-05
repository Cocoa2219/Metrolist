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
import kotlinx.serialization.json.int
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

    private var cachedPlayer: String? = null
    private var cachedSignatureTimestamp: Int? = null

    /**
     * Fetch player info from PipePipe API - same as MetrolistExtractor
     */
    private suspend fun fetchPlayerInfo(): Pair<String, Int>? {
        return try {
            val response = httpClient.get("https://api.pipepipe.dev/decoder/latest-player") {
                header("User-Agent", "PipePipe/5.0.0")
            }.bodyAsText()
            
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val player = jsonObj["player"]?.jsonPrimitive?.content
            val signatureTimestamp = jsonObj["signatureTimestamp"]?.jsonPrimitive?.int
            
            if (player != null && signatureTimestamp != null) {
                player to signatureTimestamp
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun ensurePlayerInfo() {
        if (cachedPlayer == null || cachedSignatureTimestamp == null) {
            val playerInfo = runBlocking { fetchPlayerInfo() }
            cachedPlayer = playerInfo?.first
            cachedSignatureTimestamp = playerInfo?.second
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        ensurePlayerInfo()
        cachedSignatureTimestamp ?: throw Exception("Could not get signature timestamp")
    }

    /**
     * Decode a single parameter using the PipePipe API
     */
    private suspend fun decode(player: String, paramType: String, value: String): String {
        val encodedValue = URLEncoder.encode(value, "UTF-8")
        val apiUrl = "https://api.pipepipe.dev/decoder/decode?player=$player&$paramType=$encodedValue"
        
        val response = httpClient.get(apiUrl) {
            header("User-Agent", "PipePipe/5.0.0")
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
    private suspend fun decodeSignature(player: String, signature: String): String {
        return decode(player, "sig", signature)
    }

    /**
     * Decode throttling parameter (n) using the API
     */
    private suspend fun decodeThrottlingParameter(player: String, nParam: String): String {
        return decode(player, "n", nParam)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            ensurePlayerInfo()
            val player = cachedPlayer ?: throw Exception("Could not get player info")

            val url = format.url
            if (url != null) {
                // Direct URL - just need to deobfuscate throttling parameter
                return@runCatching runBlocking {
                    getUrlWithThrottlingParameterDeobfuscated(player, url)
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
                    val decodedSignature = decodeSignature(player, obfuscatedSignature)
                    
                    // Build URL with decoded signature
                    val urlBuilder = URLBuilder(baseUrl)
                    urlBuilder.parameters[signatureParam] = decodedSignature
                    val urlWithSig = urlBuilder.toString()
                    
                    // Deobfuscate throttling parameter
                    getUrlWithThrottlingParameterDeobfuscated(player, urlWithSig)
                }
            } ?: throw Exception("Could not find format url")
        }

    /**
     * Get URL with throttling parameter deobfuscated - same logic as MetrolistExtractor
     */
    private suspend fun getUrlWithThrottlingParameterDeobfuscated(
        player: String,
        streamingUrl: String
    ): String {
        val nParam = extractNParam(streamingUrl) ?: return streamingUrl
        val decodedNParam = URLDecoder.decode(nParam, "UTF-8")
        
        val deobfuscatedN = decodeThrottlingParameter(player, decodedNParam)
        
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
        cachedPlayer = null
        cachedSignatureTimestamp = null
    }
}
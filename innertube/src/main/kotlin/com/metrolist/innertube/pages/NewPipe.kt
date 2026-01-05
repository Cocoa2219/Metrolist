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

    private suspend fun batchDecryptCiphers(
        nValues: List<String>,
        sigValues: List<String>,
        player: String
    ): Map<String, String> {
        if (nValues.isEmpty() && sigValues.isEmpty()) return emptyMap()

        val queryParams = mutableListOf<String>()

        if (nValues.isNotEmpty()) {
            queryParams.add("n=${nValues.joinToString(",") { URLEncoder.encode(it, "UTF-8") }}")
        }

        if (sigValues.isNotEmpty()) {
            queryParams.add("sig=${sigValues.joinToString(",") { URLEncoder.encode(it, "UTF-8") }}")
        }

        val apiUrl = "https://api.pipepipe.dev/decoder/decode?player=$player&${queryParams.joinToString("&")}"
        
        val response = httpClient.get(apiUrl) {
            header("User-Agent", "PipePipe/5.0.0")
        }.bodyAsText()

        val results = mutableMapOf<String, String>()
        val jsonObj = json.parseToJsonElement(response).jsonObject
        val responses = jsonObj["responses"]?.jsonArray ?: return results

        for (responseItem in responses) {
            val itemObj = responseItem.jsonObject
            val type = itemObj["type"]?.jsonPrimitive?.content
            if (type == "result") {
                val data = itemObj["data"]?.jsonObject ?: continue
                for ((key, value) in data) {
                    results[key] = value.jsonPrimitive.content
                }
            }
        }
        return results
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            ensurePlayerInfo()
            val player = cachedPlayer ?: throw Exception("Could not get player info")

            val url = format.url
            if (url != null) {
                val nParam = extractNParam(url)
                if (nParam != null) {
                    val decodedN = URLDecoder.decode(nParam, "UTF-8")
                    val decryptedMap = runBlocking {
                        batchDecryptCiphers(listOf(decodedN), emptyList(), player)
                    }
                    val decryptedN = decryptedMap[decodedN]
                    if (decryptedN != null) {
                        return@runCatching replaceNParam(url, decryptedN)
                    }
                }
                return@runCatching url
            }

            format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw Exception("Could not parse cipher signature")
                val signatureParam = params["sp"] ?: "sig"
                val baseUrl = params["url"]
                    ?: throw Exception("Could not parse cipher url")

                val nValues = mutableListOf<String>()
                val sigValues = mutableListOf<String>()

                // The signature (s parameter) always goes to sigValues
                sigValues.add(obfuscatedSignature)

                // Check for n parameter in the base URL
                val nParam = extractNParam(baseUrl)
                if (nParam != null) {
                    val decodedN = URLDecoder.decode(nParam, "UTF-8")
                    nValues.add(decodedN)
                }

                val decryptedMap = runBlocking {
                    batchDecryptCiphers(nValues, sigValues, player)
                }

                val urlBuilder = URLBuilder(baseUrl)
                val decryptedSig = decryptedMap[obfuscatedSignature]
                    ?: throw Exception("Could not decrypt signature")
                urlBuilder.parameters[signatureParam] = decryptedSig

                var finalUrl = urlBuilder.toString()

                if (nParam != null) {
                    val decodedN = URLDecoder.decode(nParam, "UTF-8")
                    val decryptedN = decryptedMap[decodedN]
                    if (decryptedN != null) {
                        finalUrl = replaceNParam(finalUrl, decryptedN)
                    }
                }

                return@runCatching finalUrl
            } ?: throw Exception("Could not find format url")
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

    private fun replaceNParam(url: String, newValue: String): String {
        val regex = Regex("([?&])n=([^&]*)")
        return regex.replace(url) { matchResult ->
            "${matchResult.groupValues[1]}n=${URLEncoder.encode(newValue, "UTF-8")}"
        }
    }
}
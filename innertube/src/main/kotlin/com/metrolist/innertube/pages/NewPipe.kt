package com.metrolist.innertube

import com.metrolist.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import project.pipepipe.extractor.services.youtube.YouTubeDecryptionHelper
import java.net.URLEncoder

object NewPipeUtils {

    private var cachedPlayer: String? = null
    private var cachedSignatureTimestamp: Int? = null

    private fun ensurePlayerInfo() {
        if (cachedPlayer == null || cachedSignatureTimestamp == null) {
            val playerInfo = YouTubeDecryptionHelper.getLatestPlayer()
            cachedPlayer = playerInfo?.first
            cachedSignatureTimestamp = playerInfo?.second
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        ensurePlayerInfo()
        cachedSignatureTimestamp ?: throw Exception("Could not get signature timestamp")
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            ensurePlayerInfo()
            val player = cachedPlayer ?: throw Exception("Could not get player info")

            val url = format.url
            if (url != null) {
                val nParam = extractNParam(url)
                if (nParam != null) {
                    val decryptedMap = runBlocking {
                        YouTubeDecryptionHelper.batchDecryptCiphers(listOf(nParam), emptyList(), player)
                    }
                    val decryptedN = decryptedMap[nParam]
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
                val signatureParam = params["sp"]
                    ?: throw Exception("Could not parse cipher signature parameter")
                val baseUrl = params["url"]
                    ?: throw Exception("Could not parse cipher url")

                val nValues = mutableListOf<String>()
                val sigValues = mutableListOf<String>()

                if (signatureParam == "n") {
                    nValues.add(obfuscatedSignature)
                } else {
                    sigValues.add(obfuscatedSignature)
                }

                val nParam = extractNParam(baseUrl)
                if (nParam != null) {
                    nValues.add(nParam)
                }

                val decryptedMap = runBlocking {
                    YouTubeDecryptionHelper.batchDecryptCiphers(nValues, sigValues, player)
                }

                val urlBuilder = URLBuilder(baseUrl)
                val decryptedSig = decryptedMap[obfuscatedSignature]
                    ?: throw Exception("Could not decrypt signature")
                urlBuilder.parameters[signatureParam] = decryptedSig

                var finalUrl = urlBuilder.toString()

                if (nParam != null) {
                    val decryptedN = decryptedMap[nParam]
                    if (decryptedN != null) {
                        finalUrl = replaceNParam(finalUrl, decryptedN)
                    }
                }

                return@runCatching finalUrl
            } ?: throw Exception("Could not find format url")
        }

    private fun extractNParam(url: String): String? {
        return try {
            val params = parseQueryString(url.substringAfter("?"))
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
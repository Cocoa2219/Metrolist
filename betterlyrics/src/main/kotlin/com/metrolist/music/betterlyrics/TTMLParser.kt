package com.metrolist.music.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {
    
    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>,
        val isBackground: Boolean = false
    )
    
    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val isBackground: Boolean = false
    )
    
    // Temporary structure for raw span data before merging
    private data class RawSpan(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasSpaceBefore: Boolean,
        val isBackground: Boolean = false
    )
    
    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ttml.byteInputStream())
            
            // Find all <p> elements (paragraphs/lines)
            val pElements = doc.getElementsByTagName("p")
            
            for (i in 0 until pElements.length) {
                val pElement = pElements.item(i) as? Element ?: continue
                
                val begin = pElement.getAttribute("begin")
                if (begin.isNullOrEmpty()) continue
                
                val startTime = parseTime(begin)
                val rawSpans = mutableListOf<RawSpan>()
                
                // Parse child nodes to detect spaces between spans
                parseChildNodes(pElement, rawSpans)
                
                // Merge consecutive spans that form a single word
                val mergedWords = mergeSpans(rawSpans)
                
                // Build line text from merged words
                val lineText = mergedWords.joinToString(" ") { it.text }
                
                // Check if this is a background vocal line
                val isBackgroundLine = pElement.getAttribute("ttm:role") == "x-bg" ||
                    pElement.getAttribute("itunes:role") == "background"
                
                if (lineText.isNotEmpty()) {
                    lines.add(
                        ParsedLine(
                            text = lineText,
                            startTime = startTime,
                            words = mergedWords,
                            isBackground = isBackgroundLine
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Return empty list on parse error
            return emptyList()
        }
        
        return lines
    }
    
    private fun parseChildNodes(element: Element, rawSpans: MutableList<RawSpan>) {
        var lastWasSpace = true // Start as true so first word doesn't get space prefix
        
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            
            when (node.nodeType) {
                Node.TEXT_NODE -> {
                    // Check if there's whitespace between spans
                    val text = node.textContent
                    if (text.isNotEmpty() && text.any { it.isWhitespace() }) {
                        lastWasSpace = true
                    }
                }
                Node.ELEMENT_NODE -> {
                    val elem = node as Element
                    if (elem.tagName.equals("span", ignoreCase = true)) {
                        val wordBegin = elem.getAttribute("begin")
                        val wordEnd = elem.getAttribute("end")
                        val wordText = elem.textContent
                        
                        // Check for background vocal markers
                        val isBackground = elem.getAttribute("ttm:role") == "x-bg" ||
                            elem.getAttribute("itunes:role") == "background"
                        
                        if (wordText.isNotEmpty() && wordBegin.isNotEmpty() && wordEnd.isNotEmpty()) {
                            rawSpans.add(
                                RawSpan(
                                    text = wordText,
                                    startTime = parseTime(wordBegin),
                                    endTime = parseTime(wordEnd),
                                    hasSpaceBefore = lastWasSpace,
                                    isBackground = isBackground
                                )
                            )
                            lastWasSpace = false
                        }
                    }
                }
            }
        }
    }
    
    private fun mergeSpans(rawSpans: List<RawSpan>): List<ParsedWord> {
        if (rawSpans.isEmpty()) return emptyList()
        
        val mergedWords = mutableListOf<ParsedWord>()
        var currentText = StringBuilder()
        var currentStartTime = 0.0
        var currentEndTime = 0.0
        var currentIsBackground = false
        var isFirstSpan = true
        
        for (span in rawSpans) {
            if (isFirstSpan || span.hasSpaceBefore) {
                // Save previous word if exists
                if (currentText.isNotEmpty()) {
                    mergedWords.add(
                        ParsedWord(
                            text = currentText.toString(),
                            startTime = currentStartTime,
                            endTime = currentEndTime,
                            isBackground = currentIsBackground
                        )
                    )
                }
                // Start new word
                currentText = StringBuilder(span.text)
                currentStartTime = span.startTime
                currentEndTime = span.endTime
                currentIsBackground = span.isBackground
                isFirstSpan = false
            } else {
                // Merge with previous span (no space between them)
                currentText.append(span.text)
                currentEndTime = span.endTime
                // If any part is background, mark the whole word as background
                currentIsBackground = currentIsBackground || span.isBackground
            }
        }
        
        // Don't forget the last word
        if (currentText.isNotEmpty()) {
            mergedWords.add(
                ParsedWord(
                    text = currentText.toString(),
                    startTime = currentStartTime,
                    endTime = currentEndTime,
                    isBackground = currentIsBackground
                )
            )
        }
        
        return mergedWords
    }
    
    fun toLRC(lines: List<ParsedLine>): String {
        return buildString {
            lines.forEach { line ->
                val timeMs = (line.startTime * 1000).toLong()
                val minutes = timeMs / 60000
                val seconds = (timeMs % 60000) / 1000
                val centiseconds = (timeMs % 1000) / 10
                
                appendLine(String.format("[%02d:%02d.%02d]%s", minutes, seconds, centiseconds, line.text))
                
                // Add word-level timestamps as special comments if available
                if (line.words.isNotEmpty()) {
                    val wordsData = line.words.joinToString("|") { word ->
                        val bgMarker = if (word.isBackground) ":bg" else ""
                        "${word.text}:${word.startTime}:${word.endTime}$bgMarker"
                    }
                    appendLine("<$wordsData>")
                }
            }
        }
    }
    
    private fun parseTime(timeStr: String): Double {
        // Parse TTML time format (e.g., "9.731", "1:23.456", "1:23:45.678")
        return try {
            when {
                timeStr.contains(":") -> {
                    val parts = timeStr.split(":")
                    when (parts.size) {
                        2 -> {
                            // MM:SS.mmm format
                            val minutes = parts[0].toDouble()
                            val seconds = parts[1].toDouble()
                            minutes * 60 + seconds
                        }
                        3 -> {
                            // HH:MM:SS.mmm format
                            val hours = parts[0].toDouble()
                            val minutes = parts[1].toDouble()
                            val seconds = parts[2].toDouble()
                            hours * 3600 + minutes * 60 + seconds
                        }
                        else -> timeStr.toDoubleOrNull() ?: 0.0
                    }
                }
                else -> timeStr.toDoubleOrNull() ?: 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
}

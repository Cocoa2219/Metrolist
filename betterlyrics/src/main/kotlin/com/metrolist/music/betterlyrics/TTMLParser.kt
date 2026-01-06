package com.metrolist.music.betterlyrics

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {
    
    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>
    )
    
    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double
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
                val end = pElement.getAttribute("end")
                if (begin.isNullOrEmpty()) continue
                
                val lineStartTime = parseTime(begin)
                val lineEndTime = if (end.isNotEmpty()) parseTime(end) else lineStartTime
                
                val words = mutableListOf<ParsedWord>()
                val lineText = StringBuilder()
                
                val childNodes = pElement.childNodes
                for (j in 0 until childNodes.length) {
                    val node = childNodes.item(j)
                    
                    when (node.nodeType) {
                        org.w3c.dom.Node.ELEMENT_NODE -> {
                            val childElement = node as Element
                            if (childElement.tagName == "span") {
                                val wordBegin = childElement.getAttribute("begin")
                                val wordEnd = childElement.getAttribute("end")
                                
                                // Check for background vocal role
                                val role = childElement.getAttribute("ttm:role") ?: childElement.getAttribute("role")
                                val isBgSpan = role == "x-bg" || role == "background"
                                
                                val wordText = childElement.textContent
                                
                                if (wordText.isNotEmpty()) {
                                    // Check if we should merge with the previous word
                                    // Merge if:
                                    // 1. There is a previous word
                                    // 2. No space separator in lineText (from Text Nodes)
                                    // 3. Current word doesn't explicitly start with space
                                    val shouldMerge = words.isNotEmpty() &&
                                            lineText.isNotEmpty() &&
                                            !lineText.last().isWhitespace() &&
                                            !wordText.startsWith(" ")
                                    
                                    lineText.append(wordText)
                                    
                                    val wordStartTime = if (wordBegin.isNotEmpty()) parseTime(wordBegin) else lineStartTime
                                    val wordEndTime = if (wordEnd.isNotEmpty()) parseTime(wordEnd) else lineEndTime
                                    
                                    if (shouldMerge) {
                                        val lastWord = words.removeAt(words.lastIndex)
                                        words.add(
                                            lastWord.copy(
                                                text = lastWord.text + wordText.trim(),
                                                endTime = wordEndTime // Extend the word to end of this syllable
                                            )
                                        )
                                    } else {
                                        words.add(
                                            ParsedWord(
                                                text = wordText.trim(),
                                                startTime = wordStartTime,
                                                endTime = wordEndTime
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        org.w3c.dom.Node.TEXT_NODE -> {
                            val text = node.textContent
                            // If text node is purely whitespace, treat as a single space
                            // If it has content, append as is
                            if (text.isNotBlank()) {
                                 lineText.append(text)
                            } else if (text.isNotEmpty()) {
                                 // Collapse multiple whitespace chars (like indentation) to single space
                                 // Only add if not already ending in whitespace to avoid double spaces
                                 if (lineText.isNotEmpty() && !lineText.last().isWhitespace()) {
                                     lineText.append(" ")
                                 }
                            }
                        }
                    }
                }
                
                if (lineText.isNotEmpty()) {
                    lines.add(
                        ParsedLine(
                            text = lineText.toString(),
                            startTime = lineStartTime,
                            words = words
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
                        "${word.text}:${word.startTime}:${word.endTime}"
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

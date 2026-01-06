package com.metrolist.music.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {
    
    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val endTime: Double,
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
            
            // Find all <div> elements which contain <p> elements (sections)
            val divElements = doc.getElementsByTagName("div")
            
            for (divIdx in 0 until divElements.length) {
                val divElement = divElements.item(divIdx) as? Element ?: continue
                
                // Get all <p> elements within this div
                val pElements = divElement.getElementsByTagName("p")
                
                for (pIdx in 0 until pElements.length) {
                    val pElement = pElements.item(pIdx) as? Element ?: continue
                    parsePElement(pElement, lines)
                }
            }
            
            // Fallback: If no div elements found, try parsing p elements directly
            if (lines.isEmpty()) {
                val pElements = doc.getElementsByTagName("p")
                
                for (i in 0 until pElements.length) {
                    val pElement = pElements.item(i) as? Element ?: continue
                    parsePElement(pElement, lines)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        
        return lines.sortedBy { it.startTime }
    }
    
    private fun parsePElement(pElement: Element, lines: MutableList<ParsedLine>) {
        val begin = pElement.getAttribute("begin")
        val end = pElement.getAttribute("end")
        if (begin.isNullOrEmpty()) return
        
        val startTime = parseTime(begin)
        val endTime = if (end.isNotEmpty()) parseTime(end) else startTime + 5.0
        val words = mutableListOf<ParsedWord>()
        val lineText = StringBuilder()
        
        // Recursively parse all span elements including nested ones
        parseSpanElements(pElement, words, lineText, startTime, endTime)
        
        // If no spans found, check for direct text content
        if (lineText.isEmpty()) {
            val directText = getDirectTextContent(pElement).trim()
            if (directText.isNotEmpty()) {
                lineText.append(directText)
                words.add(ParsedWord(directText, startTime, endTime))
            }
        }
        
        if (lineText.isNotEmpty()) {
            lines.add(ParsedLine(lineText.toString().trim(), startTime, endTime, words))
        }
    }
    
    /**
     * Recursively parse span elements to extract word-level timing
     * Handles nested spans and merges syllables into words based on whitespace
     */
    private fun parseSpanElements(
        element: Element,
        words: MutableList<ParsedWord>,
        lineText: StringBuilder,
        lineStartTime: Double,
        lineEndTime: Double
    ) {
        val childNodes = element.childNodes
        
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val childElement = node as Element
                    if (childElement.tagName.equals("span", ignoreCase = true)) {
                        val wordBegin = childElement.getAttribute("begin")
                        val wordEnd = childElement.getAttribute("end")
                        
                        // Check if this span has nested spans
                        val nestedSpans = childElement.getElementsByTagName("span")
                        if (nestedSpans.length > 0 && hasDirectSpanChildren(childElement)) {
                            // Parse nested spans recursively
                            parseSpanElements(childElement, words, lineText, lineStartTime, lineEndTime)
                        } else {
                            // This is a leaf span with text
                            val wordText = getDirectTextContent(childElement)
                            
                            if (wordText.isNotEmpty()) {
                                // Check if we should merge with the previous word
                                // Merge if:
                                // 1. There is a previous word
                                // 2. No space separator in lineText (from TextNodes)
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
                                            endTime = wordEndTime
                                        )
                                    )
                                } else {
                                    words.add(ParsedWord(wordText.trim(), wordStartTime, wordEndTime))
                                }
                            }
                        }
                    }
                }
                Node.TEXT_NODE -> {
                    val text = node.textContent
                    if (text.isNotBlank()) {
                        lineText.append(text)
                    } else if (text.isNotEmpty()) {
                        // Collapse multiple whitespace chars to single space
                        if (lineText.isNotEmpty() && !lineText.last().isWhitespace()) {
                            lineText.append(" ")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Check if element has direct span children (not nested further)
     */
    private fun hasDirectSpanChildren(element: Element): Boolean {
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val childElement = node as Element
                if (childElement.tagName.equals("span", ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * Get only direct text content of an element (not from nested elements)
     */
    private fun getDirectTextContent(element: Element): String {
        val textBuilder = StringBuilder()
        val childNodes = element.childNodes
        
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.TEXT_NODE) {
                textBuilder.append(node.textContent)
            }
        }
        
        return textBuilder.toString()
    }
    
    /**
     * Parse TTML time format
     * Supports: "9.731", "1:23.456", "1:23:45.678", "00:01:23.456"
     */
    private fun parseTime(timeStr: String): Double {
        return try {
            val cleanTime = timeStr.trim()
            when {
                cleanTime.contains(":") -> {
                    val parts = cleanTime.split(":")
                    when (parts.size) {
                        2 -> {
                            val minutes = parts[0].toDoubleOrNull() ?: 0.0
                            val seconds = parts[1].toDoubleOrNull() ?: 0.0
                            minutes * 60 + seconds
                        }
                        3 -> {
                            val hours = parts[0].toDoubleOrNull() ?: 0.0
                            val minutes = parts[1].toDoubleOrNull() ?: 0.0
                            val seconds = parts[2].toDoubleOrNull() ?: 0.0
                            hours * 3600 + minutes * 60 + seconds
                        }
                        else -> cleanTime.toDoubleOrNull() ?: 0.0
                    }
                }
                else -> cleanTime.toDoubleOrNull() ?: 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
    
    fun toLRC(lines: List<ParsedLine>): String {
        val sb = StringBuilder()
        
        for (line in lines) {
            val minutes = (line.startTime / 60).toInt()
            val seconds = line.startTime % 60
            val timestamp = String.format("[%02d:%05.2f]", minutes, seconds)
            sb.appendLine("$timestamp${line.text}")
            
            // Add word timestamps as metadata
            if (line.words.isNotEmpty() && line.words.size > 1) {
                val wordData = line.words.joinToString("|") { word ->
                    "${word.text}:${word.startTime}:${word.endTime}"
                }
                sb.appendLine("<$wordData>")
            }
        }
        
        return sb.toString()
    }
}

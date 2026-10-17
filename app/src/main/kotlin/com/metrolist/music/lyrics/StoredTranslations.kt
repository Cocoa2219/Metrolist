/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One AI translation of a song's lyrics; [lines] map to its non-blank lines in order. */
@Serializable
data class StoredTranslation(
    val language: String,
    val mode: String,
    val lines: List<String>,
    val model: String = "",
    val createdAt: Long = 0,
)

/**
 * Every translation made for a song, the one chosen for display, and whether translations are
 * shown at all. [selected] is -1 for the translation the lyrics came with, if any.
 */
@Serializable
data class StoredTranslations(
    val translations: List<StoredTranslation> = emptyList(),
    val selected: Int = -1,
    val shown: Boolean = true,
) {
    val active: StoredTranslation? get() = translations.getOrNull(selected)?.takeIf { shown }

    fun indexOf(language: String, mode: String, model: String? = null) = translations.indexOfFirst {
        it.language == language && it.mode == mode && (model == null || it.model == model)
    }

    /** Adds [translation], replacing one of the same language, mode and model, and selects it. */
    fun with(translation: StoredTranslation): StoredTranslations {
        val existing = indexOf(translation.language, translation.mode, translation.model)
        val list = if (existing >= 0) translations.toMutableList().apply { set(existing, translation) }
        else translations + translation
        return StoredTranslations(list, if (existing >= 0) existing else list.lastIndex, shown = true)
    }

    fun without(index: Int): StoredTranslations {
        val list = translations.filterIndexed { i, _ -> i != index }
        val newSelected = when {
            selected == index -> -1
            selected > index -> selected - 1
            else -> selected
        }
        return copy(translations = list, selected = newSelected)
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * The record lives in the existing translatedLyrics column, so the schema stays as it is.
 * Older rows hold one translation as plain lines, described by translationLanguage/Mode.
 */
fun LyricsEntity.storedTranslations(): StoredTranslations {
    val stored = translatedLyrics.trim()
    if (stored.isEmpty()) return StoredTranslations()
    if (stored.startsWith("{")) {
        runCatching { return json.decodeFromString<StoredTranslations>(stored) }
    }
    return StoredTranslations(listOf(StoredTranslation(translationLanguage, translationMode, stored.split("\n"))), selected = 0)
}

fun LyricsEntity.withTranslations(translations: StoredTranslations): LyricsEntity {
    if (translations.translations.isEmpty()) return copy(translatedLyrics = "", translationLanguage = "", translationMode = "")
    val active = translations.translations.getOrNull(translations.selected)
    return copy(
        translatedLyrics = json.encodeToString(StoredTranslations.serializer(), translations),
        translationLanguage = active?.language.orEmpty(),
        translationMode = active?.mode.orEmpty(),
    )
}

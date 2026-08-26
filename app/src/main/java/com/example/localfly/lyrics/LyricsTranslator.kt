package com.example.localfly.lyrics

import com.example.localfly.adapters.LyricLine
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Si la letra está en inglés, traduce cada línea al español y la deja en
 * LyricLine.translation, para mostrarla junto a la original (estilo
 * Spotify). Si no está en inglés, o falla la traducción por lo que sea
 * (sin internet la primera vez, etc.), devuelve las líneas sin tocar —
 * nunca bloquea el mostrar la letra original.
 */
object LyricsTranslator {

    suspend fun translateIfEnglish(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines

        val sample = lines.joinToString(" ") { it.content }.take(500)
        if (sample.isBlank()) return lines

        val detectedLanguage = try {
            detectLanguage(sample)
        } catch (e: Exception) {
            null
        }

        if (detectedLanguage != "en") return lines

        return try {
            translateLines(lines)
        } catch (e: Exception) {
            lines
        }
    }

    private suspend fun detectLanguage(text: String): String? = suspendCancellableCoroutine { cont ->
        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                cont.resume(if (languageCode == "und") null else languageCode)
            }
            .addOnFailureListener { cont.resume(null) }
    }

    private suspend fun translateLines(lines: List<LyricLine>): List<LyricLine> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            downloadModelIfNeeded(translator)

            return lines.map { line ->
                if (line.content.isBlank()) {
                    line
                } else {
                    val translated = translateSingleLine(translator, line.content)
                    line.copy(translation = translated)
                }
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun downloadModelIfNeeded(
        translator: com.google.mlkit.nl.translate.Translator
    ): Unit = suspendCancellableCoroutine { cont ->
        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { cont.resume(Unit) }
            .addOnFailureListener { cont.resume(Unit) } // seguir igual; translateSingleLine fallará limpio si no hay modelo
    }

    private suspend fun translateSingleLine(
        translator: com.google.mlkit.nl.translate.Translator,
        text: String
    ): String? = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
}

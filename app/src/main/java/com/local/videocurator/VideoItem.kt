package com.local.videocurator

import kotlin.math.roundToInt

data class VideoItem(
    var id: String,
    var uri: String,
    var name: String,
    var baseName: String,
    var relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val lastModified: Long,
    var rating: Int,
    var scoreValue: Double,
    var manualOrder: Int
) {
    companion object {
        const val DEFAULT_RATING = 5
        private val BRACKET_SCORE = Regex("""^【(\d+(?:\.\d+)?)】\s*""")
        private val PLUS_SCORE = Regex("""^\+(\d+(?:\.\d+)?)\s*""")

        fun extractBaseName(fileName: String): String {
            val withoutExt = fileName.substringBeforeLast('.', fileName)
            return withoutExt
                .replace(BRACKET_SCORE, "")
                .replace(PLUS_SCORE, "")
                .trim()
                .ifBlank { "未命名视频" }
        }

        fun resolveBaseName(fileName: String, storedBaseName: String?): String {
            val extracted = extractBaseName(fileName)
            val normalizedStored = storedBaseName?.trim().orEmpty()
            return when {
                extracted != "未命名视频" -> extracted
                normalizedStored.isNotBlank() && normalizedStored != "未命名视频" -> normalizedStored
                else -> extracted
            }
        }

        fun getExtension(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot >= 0) fileName.substring(dot) else ""
        }

        fun parseLeadingScoreValue(fileName: String): Double? {
            val bracket = BRACKET_SCORE.find(fileName)
            if (bracket != null) {
                return bracket.groupValues[1].toDoubleOrNull()
            }
            val plus = PLUS_SCORE.find(fileName)
            return plus?.groupValues?.get(1)?.toDoubleOrNull()
        }

        fun parseLeadingRating(fileName: String): Int? {
            val score = parseLeadingScoreValue(fileName) ?: return null
            return ratingFromScore(score)
        }

        fun ratingFromScore(score: Double): Int {
            val clamped = score.coerceIn(3.0, 5.0)
            val normalized = (clamped * 10.0).roundToInt() / 10.0
            return ((normalized - 3.0) * 5.0).roundToInt().coerceIn(0, 10)
        }

        fun composeDisplayScore(rating: Int, suffix: Int): Double {
            val bucketBase = 3.0 + rating.coerceIn(0, 10) / 5.0
            val safeSuffix = suffix.coerceIn(0, 99)
            val str = "%.1f%02d".format(bucketBase, safeSuffix)
            return str.toDoubleOrNull() ?: 0.0
        }

        fun formatScore(value: Double): String = "%.3f".format(value)
    }
}

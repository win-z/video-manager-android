package com.local.videocurator

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
            val base = (score * 10).toInt() / 10.0
            return ((base - 3.0) * 5).toInt().coerceIn(0, 10)
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

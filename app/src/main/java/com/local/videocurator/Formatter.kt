package com.local.videocurator

import kotlin.math.ln
import kotlin.math.pow

object Formatter {
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val size = bytes / 1024.0.pow(digitGroups.toDouble())
        val decimals = if (size >= 10 || digitGroups == 0) 0 else 1
        return "%,.${decimals}f %s".format(size, units[digitGroups])
    }
}

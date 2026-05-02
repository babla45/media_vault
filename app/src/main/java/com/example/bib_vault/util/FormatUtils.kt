package com.example.bib_vault.util

import java.text.DecimalFormat

/**
 * Formatting utilities for file sizes, durations, and timestamps.
 */
object FormatUtils {

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")
    private val decimalFormat = DecimalFormat("#.##")

    /** Format a byte count as a human-readable file size (e.g., "12.5 MB") */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < sizeUnits.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "${decimalFormat.format(value)} ${sizeUnits[unitIndex]}"
    }

    /** Format a duration in milliseconds as mm:ss or hh:mm:ss */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** Format epoch millis as a readable date string */
    fun formatTimestamp(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    /** Get a file extension from a filename */
    fun getExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "").uppercase()
    }
}

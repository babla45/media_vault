package com.example.bib_vault.util

/**
 * Utility for MIME type detection and media type classification.
 */
object MimeUtils {

    /** Guess MIME type from a filename extension */
    fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // Video
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"

            // Audio
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wma" -> "audio/x-ms-wma"
            "opus" -> "audio/opus"

            // Image
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heif"

            else -> "application/octet-stream"
        }
    }

    /** Classify a MIME type into a broad media category */
    fun getMediaType(mimeType: String): MediaType = when {
        mimeType.startsWith("video/") -> MediaType.VIDEO
        mimeType.startsWith("audio/") -> MediaType.AUDIO
        mimeType.startsWith("image/") -> MediaType.IMAGE
        else -> MediaType.OTHER
    }

    /** Get a user-friendly label for a MIME type */
    fun getTypeLabel(mimeType: String): String = when (getMediaType(mimeType)) {
        MediaType.VIDEO -> "Video"
        MediaType.AUDIO -> "Audio"
        MediaType.IMAGE -> "Image"
        MediaType.OTHER -> "File"
    }
}

/** Broad media type categories */
enum class MediaType {
    VIDEO, AUDIO, IMAGE, OTHER
}

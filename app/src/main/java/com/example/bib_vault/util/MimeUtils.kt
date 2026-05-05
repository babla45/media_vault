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

            // Documents & archives (shown under Others in vault)
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "md", "markdown" -> "text/markdown"
            "yml", "yaml" -> "text/yaml"
            "toml" -> "text/plain"
            "ini", "cfg", "conf" -> "text/plain"
            "log" -> "text/plain"
            "tsv" -> "text/tab-separated-values"
            "sql" -> "application/sql"
            "graphql", "gql" -> "application/graphql"
            "gradle" -> "text/plain"
            "properties" -> "text/plain"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java"
            "py" -> "text/x-python"
            "rs" -> "text/x-rust"
            "go" -> "text/x-go"
            "c" -> "text/x-c"
            "h" -> "text/x-c"
            "cpp", "cc", "hpp" -> "text/x-c++"
            "js", "mjs", "cjs" -> "text/javascript"
            "ts", "tsx" -> "text/typescript"
            "jsx" -> "text/jsx"
            "css" -> "text/css"
            "scss" -> "text/x-scss"
            "less" -> "text/less"
            "sh", "bash", "zsh" -> "application/x-sh"
            "bat", "cmd" -> "text/plain"
            "ps1" -> "text/plain"
            "rb" -> "text/x-ruby"
            "php" -> "application/x-httpd-php"
            "swift" -> "text/x-swift"
            "proto" -> "text/x-protobuf"
            "cmake" -> "text/plain"
            "dockerfile" -> "text/x-dockerfile"
            "gitignore", "dockerignore", "editorconfig" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "apk" -> "application/vnd.android.package-archive"

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

    /**
     * Whether the file is treated as human-readable text for in-app preview
     * (plain text, markup, source code, data formats like JSON/XML/CSV).
     */
    fun isTextPreviewable(mimeType: String, fileName: String): Boolean {
        val mime = mimeType.lowercase()
        if (mime.startsWith("text/")) return true
        when (mime) {
            "application/json",
            "application/xml",
            "application/javascript",
            "application/sql",
            "application/graphql",
            "application/x-sh",
            "application/vnd.api+json",
            "application/x-yaml",
            "application/x-httpd-php" -> return true
        }
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (base.equals("dockerfile", ignoreCase = true)) return true
        if (base.equals("makefile", ignoreCase = true)) return true
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in TEXT_PREVIEW_EXTENSIONS
    }

    /** PDF files (preview via system PDF renderer). */
    fun isPdfPreviewable(mimeType: String, fileName: String): Boolean {
        if (mimeType.equals("application/pdf", ignoreCase = true)) return true
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        return base.substringAfterLast('.', "").lowercase() == "pdf"
    }

    /** Markdown sources (.md) — rendered preview, not plain text only. */
    fun isMarkdownFile(mimeType: String, fileName: String): Boolean {
        if (mimeType.equals("text/markdown", ignoreCase = true)) return true
        if (mimeType.equals("text/x-markdown", ignoreCase = true)) return true
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext == "md" || ext == "markdown"
    }

    /** Get a user-friendly label for a MIME type */
    fun getTypeLabel(mimeType: String): String = when (getMediaType(mimeType)) {
        MediaType.VIDEO -> "Video"
        MediaType.AUDIO -> "Audio"
        MediaType.IMAGE -> "Image"
        MediaType.OTHER -> when (mimeType) {
            "application/pdf" -> "PDF"
            "text/plain" -> "Text"
            "text/html" -> "HTML"
            "text/markdown" -> "Markdown"
            "text/csv", "text/tab-separated-values" -> "CSV"
            "text/yaml", "application/x-yaml" -> "YAML"
            "application/xml" -> "XML"
            "application/zip" -> "ZIP"
            "application/json" -> "JSON"
            else -> "File"
        }
    }
}

/** Broad media type categories */
enum class MediaType {
    VIDEO, AUDIO, IMAGE, OTHER
}

private val TEXT_PREVIEW_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "xml", "csv", "tsv", "log",
    "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue",
    "kt", "kts", "java", "gradle", "properties",
    "py", "rb", "php", "go", "rs", "c", "h", "cpp", "hpp", "cc",
    "swift", "m", "mm",
    "sh", "bash", "zsh", "bat", "cmd", "ps1",
    "yaml", "yml", "toml", "ini", "cfg", "conf",
    "sql", "graphql", "gql", "proto",
    "cmake", "env",
    "gitignore", "dockerignore", "editorconfig",
)

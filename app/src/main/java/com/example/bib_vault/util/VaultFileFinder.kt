package com.example.bib_vault.util

import android.os.Environment
import com.example.bib_vault.crypto.CryptoConstants
import java.io.File
import java.io.FileInputStream

/**
 * Scans shared storage for BibVault (.biv) container files.
 */
object VaultFileFinder {

    private val SKIP_DIR_NAMES = setOf(
        "Android",
        "Lost.dir",
        "LOST.DIR",
        ".thumbnails",
        ".trash",
        "cache",
        "Cache",
        "tmp",
        "Temp"
    )

    data class FoundVault(
        val file: File,
        val displayName: String,
        val parentPath: String
    )

    /**
     * Find all .biv vault files under primary and secondary storage roots.
     * Best-effort; requires all-files access for a thorough scan.
     */
    fun findVaultFiles(maxDepth: Int = 10): List<FoundVault> {
        val results = LinkedHashMap<String, FoundVault>()
        for (root in storageRoots()) {
            if (!root.exists() || !root.canRead()) continue
            walk(root, results, depth = 0, maxDepth = maxDepth)
        }
        return results.values.sortedByDescending { it.file.lastModified() }
    }

    private fun storageRoots(): List<File> {
        val roots = linkedSetOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        val storage = File("/storage")
        storage.listFiles()?.forEach { child ->
            val name = child.name
            if (name.equals("emulated", ignoreCase = true) ||
                name.equals("self", ignoreCase = true)
            ) {
                return@forEach
            }
            if (child.isDirectory && child.canRead()) {
                roots.add(child)
            }
        }
        return roots.toList()
    }

    private fun walk(
        dir: File,
        out: LinkedHashMap<String, FoundVault>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            try {
                if (child.isDirectory) {
                    if (shouldSkipDirectory(child)) continue
                    walk(child, out, depth + 1, maxDepth)
                } else if (isLikelyVaultFile(child)) {
                    val canonical = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    out.putIfAbsent(
                        canonical,
                        FoundVault(
                            file = child,
                            displayName = child.name,
                            parentPath = child.parent ?: ""
                        )
                    )
                }
            } catch (_: SecurityException) {
                // Skip unreadable entries
            } catch (_: Exception) {
                // Skip problematic entries
            }
        }
    }

    private fun shouldSkipDirectory(dir: File): Boolean {
        val name = dir.name
        if (name.startsWith(".")) return true
        if (name in SKIP_DIR_NAMES) return true
        return false
    }

    private fun isLikelyVaultFile(file: File): Boolean {
        if (!file.isFile) return false
        if (!file.name.endsWith(".biv", ignoreCase = true)) return false
        if (file.length() < CryptoConstants.HEADER_FIXED_SIZE) return false
        return hasVaultMagic(file)
    }

    private fun hasVaultMagic(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(CryptoConstants.MAGIC_BYTES.size)
                val read = input.read(magic)
                read == magic.size && magic.contentEquals(CryptoConstants.MAGIC_BYTES)
            }
        } catch (_: Exception) {
            false
        }
    }
}

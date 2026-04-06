package com.local.videocurator

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class VideoFolderImporter(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "flv", "mpeg", "mpg")

    fun importFromTree(treeUri: Uri, existing: List<VideoItem>): MutableList<VideoItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return existing.toMutableList()
        val existingMap = existing.associateBy { it.id }.toMutableMap()
        val ordered = existing.toMutableList()
        var nextOrder = (existing.maxOfOrNull { it.manualOrder } ?: 0) + 1

        walk(root, emptyList()).forEach { entry ->
            val id = buildId(entry.file)
            val old = existingMap[id]
            val fileName = entry.file.name ?: "未命名视频"
            val parsedRating = VideoItem.parseLeadingRating(fileName)
            val baseName = VideoItem.extractBaseName(fileName)

            val item = VideoItem(
                id = id,
                uri = entry.file.uri.toString(),
                name = fileName,
                baseName = baseName,
                relativePath = (entry.pathParts + listOfNotNull(entry.file.name)).joinToString("/"),
                sizeBytes = entry.file.length(),
                durationMs = readDuration(entry.file.uri),
                lastModified = entry.file.lastModified(),
                rating = parsedRating ?: old?.rating ?: 0,
                scoreValue = old?.scoreValue ?: 0.0,
                manualOrder = old?.manualOrder ?: nextOrder++
            )

            if (old == null) {
                ordered.add(item)
            } else {
                val index = ordered.indexOfFirst { it.id == id }
                if (index >= 0) {
                    ordered[index] = item
                }
            }
        }

        return ordered
    }

    /**
     * Recompute scores for all videos and rename their files accordingly.
     * Returns the updated list with new names, IDs, and URIs.
     */
    fun recomputeScoresAndRename(
        treeUri: Uri,
        videos: MutableList<VideoItem>
    ): MutableList<VideoItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return videos

        // Sort by manualOrder for suffix assignment
        val ordered = videos.sortedBy { it.manualOrder }
        val bucketCounts = mutableMapOf<String, Int>()

        for (video in ordered) {
            val bucketKey = "%.1f".format(video.rating.coerceIn(0, 10).toDouble())
            val used = bucketCounts.getOrDefault(bucketKey, 0)
            val suffix = 99 - used
            bucketCounts[bucketKey] = used + 1
            video.scoreValue = VideoItem.composeDisplayScore(video.rating, suffix)
        }

        // Sort by rating desc, then score desc for display order
        val displayOrdered = ordered.sortedWith(
            compareByDescending<VideoItem> { it.rating }
                .thenByDescending { it.scoreValue }
        )
        displayOrdered.forEachIndexed { index, video ->
            video.manualOrder = index + 1
        }

        // Rename files
        for (video in displayOrdered) {
            renameVideoFile(root, video)
        }

        return videos
    }

    /**
     * Rename a single video file to 【score】baseName.ext format.
     */
    fun renameVideoFile(treeRoot: DocumentFile, video: VideoItem): Boolean {
        val ext = VideoItem.getExtension(video.name)
        val newName = "【${VideoItem.formatScore(video.scoreValue)}】${video.baseName}$ext"

        if (newName == video.name) return true

        // Find the file in the tree
        val docFile = findDocumentFile(treeRoot, video.relativePath) ?: return false

        return try {
            if (docFile.renameTo(newName)) {
                video.name = newName
                video.uri = docFile.uri.toString()
                video.id = buildId(docFile)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoImporter", "rename failed: ${video.name} -> $newName", e)
            false
        }
    }

    private fun findDocumentFile(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split("/")
        var current: DocumentFile? = root
        for (part in parts) {
            current = current?.findFile(part) ?: return null
        }
        return current
    }

    private fun walk(directory: DocumentFile, parents: List<String>): List<VideoEntry> {
        val results = mutableListOf<VideoEntry>()
        directory.listFiles().forEach { child ->
            when {
                child.isDirectory -> {
                    results += walk(child, parents + listOfNotNull(child.name))
                }
                child.isFile && isVideo(child.name) -> {
                    results += VideoEntry(child, parents)
                }
            }
        }
        return results
    }

    private fun isVideo(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: return false
        return ext in videoExtensions
    }

    private fun buildId(file: DocumentFile): String {
        return "${file.uri}-${file.length()}-${file.lastModified()}"
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } ?: 0L
        }.getOrDefault(0L).also {
            runCatching { retriever.release() }
        }
    }

    private data class VideoEntry(
        val file: DocumentFile,
        val pathParts: List<String>
    )
}

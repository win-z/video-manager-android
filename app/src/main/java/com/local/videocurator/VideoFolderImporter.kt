package com.local.videocurator

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class VideoFolderImporter(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "flv", "mpeg", "mpg")

    /**
     * 快速导入：优先使用 MediaStore 系统索引（毫秒级，包含时长），
     * 失败时回退到 DocumentFile 遍历（慢但兼容所有路径）。
     */
    fun importFromTree(
        treeUri: Uri,
        existing: List<VideoItem>,
        onProgress: ((scanned: Int) -> Unit)? = null
    ): MutableList<VideoItem> {
        val mediaStoreResult = tryMediaStoreImport(treeUri, existing, onProgress)
        if (mediaStoreResult != null) return mediaStoreResult
        return documentFileImport(treeUri, existing, onProgress)
    }

    // ── MediaStore 路径（快速）────────────────────────────────────────────────

    private fun tryMediaStoreImport(
        treeUri: Uri,
        existing: List<VideoItem>,
        onProgress: ((Int) -> Unit)?
    ): MutableList<VideoItem>? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderPath = docId.substringAfter(':', "").trimEnd('/')
        if (folderPath.isEmpty()) return null
        queryMediaStore(folderPath, existing, onProgress)
    }.getOrNull()

    private fun queryMediaStore(
        folderPrefix: String,
        existing: List<VideoItem>,
        onProgress: ((Int) -> Unit)?
    ): MutableList<VideoItem> {
        val existingByUri = existing.associateBy { it.uri }
        val ordered = existing.toMutableList()
        var nextOrder = (existing.maxOfOrNull { it.manualOrder } ?: 0) + 1

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )

        // 匹配该文件夹及所有子文件夹
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$folderPrefix/%")

        val cursor = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        ) ?: return ordered

        var count = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

            while (c.moveToNext()) {
                val mediaId = c.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId
                )
                val uriStr = contentUri.toString()
                val name = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                val duration = c.getLong(durCol)
                val modifiedMs = c.getLong(modCol) * 1000L
                val relPath = c.getString(pathCol) ?: ""

                val old = existingByUri[uriStr]
                val id = "$uriStr-$size-$modifiedMs"
                val parsedRating = VideoItem.parseLeadingRating(name)
                val parsedScoreValue = VideoItem.parseLeadingScoreValue(name)
                val resolvedRating = parsedRating ?: old?.rating ?: VideoItem.DEFAULT_RATING
                val resolvedScoreValue = parsedScoreValue
                    ?: old?.scoreValue?.takeIf { it > 0.0 }
                    ?: VideoItem.composeDisplayScore(resolvedRating, 99)

                val item = VideoItem(
                    id = id,
                    uri = uriStr,
                    name = name,
                    baseName = VideoItem.extractBaseName(name),
                    relativePath = buildRelativePath(folderPrefix, relPath, name),
                    sizeBytes = size,
                    durationMs = duration,
                    lastModified = modifiedMs,
                    rating = resolvedRating,
                    scoreValue = resolvedScoreValue,
                    manualOrder = old?.manualOrder ?: nextOrder++
                )

                val idx = ordered.indexOfFirst { it.uri == uriStr }
                if (idx >= 0) ordered[idx] = item else ordered.add(item)

                count++
                if (count % 50 == 0) onProgress?.invoke(count)
            }
        }

        onProgress?.invoke(count)
        return ordered
    }

    // ── DocumentFile 回退路径（兼容，无 MediaStore 权限时使用）──────────────

    private fun documentFileImport(
        treeUri: Uri,
        existing: List<VideoItem>,
        onProgress: ((Int) -> Unit)?
    ): MutableList<VideoItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return existing.toMutableList()
        val existingMap = existing.associateBy { it.id }.toMutableMap()
        val ordered = existing.toMutableList()
        var nextOrder = (existing.maxOfOrNull { it.manualOrder } ?: 0) + 1
        var count = 0

        walk(root, emptyList()).forEach { entry ->
            val id = buildId(entry.file)
            val old = existingMap[id]
            val fileName = entry.file.name ?: "未命名视频"
            val parsedRating = VideoItem.parseLeadingRating(fileName)
            val parsedScoreValue = VideoItem.parseLeadingScoreValue(fileName)
            val resolvedRating = parsedRating ?: old?.rating ?: VideoItem.DEFAULT_RATING
            val resolvedScoreValue = parsedScoreValue
                ?: old?.scoreValue?.takeIf { it > 0.0 }
                ?: VideoItem.composeDisplayScore(resolvedRating, 99)

            val item = VideoItem(
                id = id,
                uri = entry.file.uri.toString(),
                name = fileName,
                baseName = VideoItem.extractBaseName(fileName),
                relativePath = (entry.pathParts + listOfNotNull(entry.file.name)).joinToString("/"),
                sizeBytes = entry.file.length(),
                durationMs = 0L,  // 不在此阶段读取，避免卡顿
                lastModified = entry.file.lastModified(),
                rating = resolvedRating,
                scoreValue = resolvedScoreValue,
                manualOrder = old?.manualOrder ?: nextOrder++
            )

            val idx = ordered.indexOfFirst { it.id == id }
            if (idx >= 0) ordered[idx] = item else ordered.add(item)

            count++
            if (count % 50 == 0) onProgress?.invoke(count)
        }

        onProgress?.invoke(count)
        return ordered
    }

    // ── 重算评分 & 重命名 ─────────────────────────────────────────────────────

    fun recomputeScoresAndRename(
        treeUri: Uri,
        videos: MutableList<VideoItem>,
        onProgress: ((renamed: Int, total: Int) -> Unit)? = null
    ): MutableList<VideoItem> {
        val ordered = videos.sortedBy { it.manualOrder }
        val bucketCounts = mutableMapOf<String, Int>()

        for (video in ordered) {
            val bucketKey = "%.1f".format(video.rating.coerceIn(0, 10).toDouble())
            val used = bucketCounts.getOrDefault(bucketKey, 0)
            val suffix = 99 - used
            bucketCounts[bucketKey] = used + 1
            video.scoreValue = VideoItem.composeDisplayScore(video.rating, suffix)
        }

        val displayOrdered = ordered.sortedWith(
            compareByDescending<VideoItem> { it.rating }.thenByDescending { it.scoreValue }
        )
        displayOrdered.forEachIndexed { index, video -> video.manualOrder = index + 1 }

        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        val total = displayOrdered.size
        displayOrdered.forEachIndexed { index, video ->
            renameVideoFile(root, video)
            if ((index + 1) % 20 == 0 || index + 1 == total) {
                onProgress?.invoke(index + 1, total)
            }
        }

        return videos
    }

    fun renameVideoFile(treeRoot: DocumentFile?, video: VideoItem): Boolean {
        val ext = VideoItem.getExtension(video.name)
        val newName = "【${VideoItem.formatScore(video.scoreValue)}】${video.baseName}$ext"
        if (newName == video.name) return true

        // 优先用 MediaStore 重命名（快）
        val uri = runCatching { Uri.parse(video.uri) }.getOrNull() ?: return false
        if (tryMediaStoreRename(uri, video, newName)) return true

        // 回退到 SAF 重命名
        if (treeRoot == null) return false
        val docFile = findDocumentFile(treeRoot, video.relativePath) ?: return false
        return runCatching {
            if (docFile.renameTo(newName)) {
                val parentPath = video.relativePath.substringBeforeLast('/', "")
                video.name = newName
                video.relativePath = if (parentPath.isNotBlank()) "$parentPath/$newName" else newName
                video.uri = docFile.uri.toString()
                video.id = buildId(docFile)
                true
            } else false
        }.getOrDefault(false)
    }

    private fun tryMediaStoreRename(uri: Uri, video: VideoItem, newName: String): Boolean {
        if (uri.authority?.contains("media") != true) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, newName)
            }
            val rows = resolver.update(uri, values, null, null)
            if (rows > 0) {
                val parentPath = video.relativePath.substringBeforeLast('/', "")
                video.name = newName
                video.relativePath = if (parentPath.isNotBlank()) "$parentPath/$newName" else newName
                true
            } else false
        }.getOrDefault(false)
    }

    private fun buildRelativePath(folderPrefix: String, mediaRelativePath: String, name: String): String {
        val normalizedPrefix = folderPrefix.trim('/')
        val normalizedMediaPath = mediaRelativePath.trim('/').removeSuffix("/")
        val relativeDir = when {
            normalizedMediaPath.isBlank() -> ""
            normalizedMediaPath == normalizedPrefix -> ""
            normalizedMediaPath.startsWith("$normalizedPrefix/") ->
                normalizedMediaPath.removePrefix("$normalizedPrefix/").trim('/')
            else -> normalizedMediaPath
        }
        return listOfNotNull(relativeDir.takeIf { it.isNotBlank() }, name).joinToString("/")
    }

    private fun findDocumentFile(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath
            .split("/")
            .filter { it.isNotBlank() }
            .toMutableList()
        if (parts.firstOrNull() == root.name) {
            parts.removeAt(0)
        }
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
                child.isDirectory -> results += walk(child, parents + listOfNotNull(child.name))
                child.isFile && isVideo(child.name) -> results += VideoEntry(child, parents)
            }
        }
        return results
    }

    private fun isVideo(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: return false
        return ext in videoExtensions
    }

    private fun buildId(file: DocumentFile): String =
        "${file.uri}-${file.length()}-${file.lastModified()}"

    private data class VideoEntry(val file: DocumentFile, val pathParts: List<String>)
}

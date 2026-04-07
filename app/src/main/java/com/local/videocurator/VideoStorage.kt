package com.local.videocurator

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class VideoStorage(context: Context) {
    private val prefs = context.getSharedPreferences("video_curator_prefs", Context.MODE_PRIVATE)

    fun loadVideos(): MutableList<VideoItem> {
        val raw = prefs.getString(KEY_VIDEOS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                val name = item.getString("name")
                val parsedRating = VideoItem.parseLeadingRating(name)
                val storedRating = item.optInt("rating", VideoItem.DEFAULT_RATING)
                val resolvedRating = parsedRating ?: storedRating.takeIf { it in 1..10 } ?: VideoItem.DEFAULT_RATING
                val parsedScoreValue = VideoItem.parseLeadingScoreValue(name)
                val storedScoreValue = item.optDouble("scoreValue", 0.0)
                val resolvedScoreValue = parsedScoreValue
                    ?: storedScoreValue.takeIf { it > 0.0 }
                    ?: VideoItem.composeDisplayScore(resolvedRating, 99)
                VideoItem(
                    id = item.getString("id"),
                    uri = item.getString("uri"),
                    name = name,
                    baseName = item.optString("baseName", VideoItem.extractBaseName(name)),
                    relativePath = item.getString("relativePath"),
                    sizeBytes = item.getLong("sizeBytes"),
                    durationMs = item.getLong("durationMs"),
                    lastModified = item.getLong("lastModified"),
                    rating = resolvedRating,
                    scoreValue = resolvedScoreValue,
                    manualOrder = item.optInt("manualOrder", index + 1)
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun saveVideos(videos: List<VideoItem>) {
        val array = JSONArray()
        videos.forEach { video ->
            array.put(
                JSONObject().apply {
                    put("id", video.id)
                    put("uri", video.uri)
                    put("name", video.name)
                    put("baseName", video.baseName)
                    put("relativePath", video.relativePath)
                    put("sizeBytes", video.sizeBytes)
                    put("durationMs", video.durationMs)
                    put("lastModified", video.lastModified)
                    put("rating", video.rating)
                    put("scoreValue", video.scoreValue)
                    put("manualOrder", video.manualOrder)
                }
            )
        }
        prefs.edit().putString(KEY_VIDEOS, array.toString()).apply()
    }

    fun saveSortMode(sortMode: SortMode) {
        prefs.edit().putString(KEY_SORT_MODE, sortMode.name).apply()
    }

    fun loadSortMode(): SortMode = SortMode.fromName(prefs.getString(KEY_SORT_MODE, null))

    fun saveViewMode(viewMode: VideoAdapter.ViewMode) {
        prefs.edit().putString(KEY_VIEW_MODE, viewMode.name).apply()
    }

    fun loadViewMode(): VideoAdapter.ViewMode {
        val name = prefs.getString(KEY_VIEW_MODE, null) ?: return VideoAdapter.ViewMode.GRID
        return runCatching { VideoAdapter.ViewMode.valueOf(name) }.getOrDefault(VideoAdapter.ViewMode.GRID)
    }

    fun saveGridSize(size: Int) {
        prefs.edit().putInt(KEY_GRID_SIZE, size).apply()
    }

    /** spanCount: 2=中(默认), 3=小, 1=大 */
    fun loadGridSize(): Int = prefs.getInt(KEY_GRID_SIZE, 2)

    fun saveTreeUri(uri: Uri) {
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun loadTreeUri(): Uri? {
        val raw = prefs.getString(KEY_TREE_URI, null) ?: return null
        return Uri.parse(raw)
    }

    companion object {
        private const val KEY_VIDEOS = "videos"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_GRID_SIZE = "grid_size"
        private const val KEY_TREE_URI = "tree_uri"
    }
}

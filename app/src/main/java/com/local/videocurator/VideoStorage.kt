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
                VideoItem(
                    id = item.getString("id"),
                    uri = item.getString("uri"),
                    name = name,
                    baseName = item.optString("baseName", VideoItem.extractBaseName(name)),
                    relativePath = item.getString("relativePath"),
                    sizeBytes = item.getLong("sizeBytes"),
                    durationMs = item.getLong("durationMs"),
                    lastModified = item.getLong("lastModified"),
                    rating = item.optInt("rating", 0),
                    scoreValue = item.optDouble("scoreValue", 0.0),
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
        private const val KEY_TREE_URI = "tree_uri"
    }
}

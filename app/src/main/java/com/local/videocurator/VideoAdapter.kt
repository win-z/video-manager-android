package com.local.videocurator

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.local.videocurator.databinding.ItemVideoCardBinding
import java.text.DateFormat
import java.util.concurrent.Executors

class VideoAdapter(
    private val resolver: ContentResolver,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    interface Callbacks {
        fun onRate(videoId: String, rating: Int)
        fun onRemove(videoId: String)
        fun canDrag(): Boolean
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val items = mutableListOf<VideoItem>()
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    fun submitList(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        notifyDataSetChanged()
    }

    fun swap(from: Int, to: Int) {
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun currentItems(): List<VideoItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(private val binding: ItemVideoCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.titleText.text = video.name
            val scoreLine = if (video.scoreValue > 0) " · 评分 ${VideoItem.formatScore(video.scoreValue)}" else ""
            binding.metaText.text = "${video.relativePath}\n${Formatter.formatFileSize(video.sizeBytes)}$scoreLine · 修改于 ${dateFormat.format(video.lastModified)}"
            binding.durationText.text = Formatter.formatDuration(video.durationMs)
            binding.dragTipText.visibility = if (callbacks.canDrag()) View.VISIBLE else View.GONE
            binding.removeButton.setOnClickListener { callbacks.onRemove(video.id) }

            bindRating(binding.ratingContainer, video)
            loadThumbnail(video.uri.toUri(), binding.thumbnailImage)
        }

        private fun bindRating(container: ViewGroup, video: VideoItem) {
            container.removeAllViews()
            repeat(10) { index ->
                val rating = index + 1
                val button = ImageButton(container.context).apply {
                    layoutParams = ViewGroup.LayoutParams(64, 64)
                    background = null
                    setImageResource(if (rating <= video.rating) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                    contentDescription = "${video.name} ${rating} 星"
                    setOnClickListener { callbacks.onRate(video.id, rating) }
                }
                container.addView(button)
            }
        }

        private fun loadThumbnail(uri: Uri, target: ImageView) {
            target.tag = uri.toString()
            target.setImageResource(android.R.color.transparent)
            executor.execute {
                val bitmap = loadBitmap(uri)
                target.post {
                    if (target.tag == uri.toString()) {
                        if (bitmap != null) {
                            target.setImageBitmap(bitmap)
                        } else {
                            target.setImageResource(android.R.color.transparent)
                            target.setBackgroundResource(R.drawable.bg_thumbnail)
                        }
                    }
                }
            }
        }

        private fun loadBitmap(uri: Uri): Bitmap? {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(uri, Size(720, 405), null)
                } else {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(pfd.fileDescriptor)
                        val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        retriever.release()
                        frame
                    }
                }
            }.getOrNull()
        }
    }
}

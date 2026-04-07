package com.local.videocurator

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.local.videocurator.databinding.ItemVideoCardBinding
import com.local.videocurator.databinding.ItemVideoListBinding
import java.util.concurrent.Executors

class VideoAdapter(
    private val resolver: ContentResolver,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onRate(videoId: String, rating: Int)
        fun onRemove(videoId: String)
        fun onPlay(video: VideoItem)
        fun onEdit(videoId: String)
        fun canDrag(): Boolean
    }

    enum class ViewMode { GRID, LIST }

    private val executor = Executors.newFixedThreadPool(2)
    private val items = mutableListOf<VideoItem>()
    var viewMode: ViewMode = ViewMode.GRID
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_LIST = 1
    }

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

    override fun getItemViewType(position: Int): Int =
        if (viewMode == ViewMode.LIST) TYPE_LIST else TYPE_GRID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LIST) {
            val binding = ItemVideoListBinding.inflate(inflater, parent, false)
            ListViewHolder(binding)
        } else {
            val binding = ItemVideoCardBinding.inflate(inflater, parent, false)
            GridViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val video = items[position]
        when (holder) {
            is GridViewHolder -> holder.bind(video)
            is ListViewHolder -> holder.bind(video)
        }
    }

    override fun getItemCount(): Int = items.size

    // ── Grid ViewHolder ──────────────────────────────────────────────────────

    inner class GridViewHolder(private val binding: ItemVideoCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.root.setOnClickListener { callbacks.onPlay(video) }
            binding.titleText.text = video.baseName
            bindRating(binding.ratingContainer, video)
            binding.scoreText.text = VideoItem.formatScore(video.scoreValue)
            loadThumbnail(video.uri.toUri(), binding.thumbnailImage)
        }
    }

    // ── List ViewHolder ──────────────────────────────────────────────────────

    inner class ListViewHolder(private val binding: ItemVideoListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.root.setOnClickListener { callbacks.onPlay(video) }
            binding.titleText.text = video.baseName
            bindRating(binding.ratingContainer, video)
            binding.scoreText.text = VideoItem.formatScore(video.scoreValue)
            binding.editButton.setOnClickListener { callbacks.onEdit(video.id) }
            loadThumbnail(video.uri.toUri(), binding.thumbnailImage)
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private fun bindRating(container: ViewGroup, video: VideoItem) {
        container.removeAllViews()
        repeat(10) { index ->
            val rating = index + 1
            val button = ImageButton(container.context).apply {
                layoutParams = ViewGroup.LayoutParams(28, 28)
                background = null
                setImageResource(if (rating <= video.rating) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                contentDescription = "${video.name} ${rating} 星"
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                isClickable = false
                isFocusable = false
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

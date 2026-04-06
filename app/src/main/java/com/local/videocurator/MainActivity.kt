package com.local.videocurator

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.local.videocurator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), VideoAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: VideoStorage
    private lateinit var importer: VideoFolderImporter
    private lateinit var adapter: VideoAdapter

    private val allVideos = mutableListOf<VideoItem>()
    private var sortMode = SortMode.MANUAL
    private var currentQuery = ""
    private var currentTreeUri: Uri? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            importFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = VideoStorage(this)
        importer = VideoFolderImporter(this)
        sortMode = storage.loadSortMode()
        currentTreeUri = storage.loadTreeUri()
        allVideos += storage.loadVideos()

        setupList()
        setupHeader()
        setupFilters()
        render()
    }

    private fun setupList() {
        adapter = VideoAdapter(contentResolver, this)
        binding.videoRecycler.layoutManager = GridLayoutManager(this, 2)
        binding.videoRecycler.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = sortMode == SortMode.MANUAL

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (sortMode != SortMode.MANUAL) return false
                adapter.swap(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (sortMode == SortMode.MANUAL) {
                    val reordered = adapter.currentItems()
                    reordered.forEachIndexed { index, video ->
                        allVideos.find { it.id == video.id }?.manualOrder = index + 1
                    }
                    recomputeAndRename()
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.videoRecycler)
    }

    private fun setupHeader() {
        binding.importButton.setOnClickListener {
            folderPicker.launch(null)
        }
        binding.clearButton.setOnClickListener {
            if (allVideos.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("清空视频库")
                .setMessage("确认移除当前列表中的视频和评分吗？")
                .setPositiveButton("清空") { _, _ ->
                    allVideos.clear()
                    persistVideos()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupFilters() {
        val labels = SortMode.entries.map { it.label }
        binding.sortDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.sortDropdown.setText(sortMode.label, false)
        binding.sortDropdown.setOnItemClickListener { _, _, position, _ ->
            sortMode = SortMode.entries[position]
            storage.saveSortMode(sortMode)
            render()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                render()
            }
        })
    }

    private fun importFolder(uri: Uri) {
        // Request both READ and WRITE permissions for rename support
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }

        currentTreeUri = uri
        storage.saveTreeUri(uri)

        binding.importButton.isEnabled = false
        binding.importButton.text = "导入中..."

        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val imported = importer.importFromTree(uri, allVideos)
                // Recompute scores and rename files
                importer.recomputeScoresAndRename(uri, imported)
                imported
            }
            allVideos.clear()
            allVideos += updated
            persistVideos()
            Toast.makeText(this@MainActivity, "已导入 ${allVideos.size} 个视频", Toast.LENGTH_SHORT).show()
            render()
        }.invokeOnCompletion {
            runOnUiThread {
                binding.importButton.isEnabled = true
                binding.importButton.text = getString(R.string.import_folder)
            }
        }
    }

    /**
     * Recompute scores and rename files in background after rating or reorder changes.
     */
    private fun recomputeAndRename() {
        val treeUri = currentTreeUri
        persistVideos()
        render()

        if (treeUri == null) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                importer.recomputeScoresAndRename(treeUri, allVideos)
            }
            persistVideos()
            render()
        }
    }

    private fun render() {
        val query = currentQuery.trim().lowercase()
        val visible = sortVideos(allVideos)
            .filter { video ->
                query.isBlank() ||
                    video.name.lowercase().contains(query) ||
                    video.relativePath.lowercase().contains(query)
            }

        adapter.submitList(visible)
        binding.emptyText.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.countText.text = "${allVideos.size} 个视频"
        val average = if (allVideos.isEmpty()) 0.0 else allVideos.sumOf { it.rating }.toDouble() / allVideos.size
        binding.ratingText.text = "均分 %.1f".format(average)
    }

    private fun sortVideos(input: List<VideoItem>): List<VideoItem> {
        return when (sortMode) {
            SortMode.MANUAL -> input.sortedBy { it.manualOrder }
            SortMode.NAME_ASC -> input.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> input.sortedByDescending { it.name.lowercase() }
            SortMode.RATING_DESC -> input.sortedWith(compareByDescending<VideoItem> { it.rating }.thenBy { it.manualOrder })
            SortMode.RATING_ASC -> input.sortedWith(compareBy<VideoItem> { it.rating }.thenBy { it.manualOrder })
            SortMode.DURATION_DESC -> input.sortedWith(compareByDescending<VideoItem> { it.durationMs }.thenBy { it.manualOrder })
            SortMode.DURATION_ASC -> input.sortedWith(compareBy<VideoItem> { it.durationMs }.thenBy { it.manualOrder })
            SortMode.MODIFIED_DESC -> input.sortedWith(compareByDescending<VideoItem> { it.lastModified }.thenBy { it.manualOrder })
            SortMode.MODIFIED_ASC -> input.sortedWith(compareBy<VideoItem> { it.lastModified }.thenBy { it.manualOrder })
        }
    }

    private fun persistVideos() {
        storage.saveVideos(allVideos.sortedBy { it.manualOrder })
    }

    override fun onRate(videoId: String, rating: Int) {
        allVideos.find { it.id == videoId }?.rating = rating
        recomputeAndRename()
    }

    override fun onRemove(videoId: String) {
        val index = allVideos.indexOfFirst { it.id == videoId }
        if (index >= 0) {
            allVideos.removeAt(index)
            persistVideos()
            render()
        }
    }

    override fun canDrag(): Boolean = sortMode == SortMode.MANUAL
}

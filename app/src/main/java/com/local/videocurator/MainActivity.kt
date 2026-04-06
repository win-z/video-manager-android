package com.local.videocurator

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var viewMode = VideoAdapter.ViewMode.GRID
    private var currentQuery = ""
    private var currentTreeUri: Uri? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不管是否授权，继续正常使用 SAF */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = VideoStorage(this)
        importer = VideoFolderImporter(this)
        sortMode = storage.loadSortMode()
        viewMode = storage.loadViewMode()
        currentTreeUri = storage.loadTreeUri()
        allVideos += storage.loadVideos()

        requestMediaPermissionIfNeeded()
        setupList()
        setupHeader()
        setupFilters()
        render()
    }

    /** 申请 READ_MEDIA_VIDEO（Android 13+）或 READ_EXTERNAL_STORAGE，让 MediaStore 查询更顺畅 */
    private fun requestMediaPermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            mediaPermissionLauncher.launch(permission)
        }
    }

    private fun setupList() {
        adapter = VideoAdapter(contentResolver, this)
        adapter.viewMode = viewMode
        val layoutManager = GridLayoutManager(this, if (viewMode == VideoAdapter.ViewMode.GRID) 2 else 1)
        binding.videoRecycler.layoutManager = layoutManager
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
        binding.importButton.setOnClickListener { folderPicker.launch(null) }
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

        updateViewToggleState()
        binding.gridViewButton.setOnClickListener { setViewMode(VideoAdapter.ViewMode.GRID) }
        binding.listViewButton.setOnClickListener { setViewMode(VideoAdapter.ViewMode.LIST) }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                render()
            }
        })
    }

    private fun setViewMode(mode: VideoAdapter.ViewMode) {
        viewMode = mode
        adapter.viewMode = mode
        val lm = binding.videoRecycler.layoutManager as? GridLayoutManager
        lm?.spanCount = if (mode == VideoAdapter.ViewMode.GRID) 2 else 1
        storage.saveViewMode(mode)
        updateViewToggleState()
    }

    private fun updateViewToggleState() {
        binding.gridViewButton.alpha = if (viewMode == VideoAdapter.ViewMode.GRID) 1.0f else 0.35f
        binding.listViewButton.alpha = if (viewMode == VideoAdapter.ViewMode.LIST) 1.0f else 0.35f
    }

    private fun importFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }

        currentTreeUri = uri
        storage.saveTreeUri(uri)

        binding.importButton.isEnabled = false
        binding.importButton.text = "扫描中…"

        lifecycleScope.launch {
            // ── 阶段 1：快速扫描，立刻显示 ──────────────────────────────────
            val scanned = withContext(Dispatchers.IO) {
                importer.importFromTree(uri, allVideos) { count ->
                    runOnUiThread {
                        binding.importButton.text = "已找到 $count 个…"
                    }
                }
            }
            allVideos.clear()
            allVideos += scanned
            persistVideos()
            render()  // ← 扫描完立刻显示，不等待重命名
            Toast.makeText(
                this@MainActivity,
                "已找到 ${allVideos.size} 个视频，后台处理中…",
                Toast.LENGTH_SHORT
            ).show()

            // ── 阶段 2：后台重算评分 & 重命名（不阻塞 UI）───────────────────
            binding.importButton.text = "重命名…"
            withContext(Dispatchers.IO) {
                importer.recomputeScoresAndRename(uri, allVideos) { renamed, total ->
                    runOnUiThread {
                        binding.importButton.text = "重命名 $renamed/$total"
                    }
                }
            }
            persistVideos()
            render()

            binding.importButton.isEnabled = true
            binding.importButton.text = getString(R.string.import_folder)
            Toast.makeText(
                this@MainActivity,
                "✅ 完成，共 ${allVideos.size} 个视频",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

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
        val visible = sortVideos(allVideos).filter { video ->
            query.isBlank() ||
                video.name.lowercase().contains(query) ||
                video.relativePath.lowercase().contains(query)
        }

        adapter.submitList(visible)
        binding.emptyText.visibility =
            if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.countText.text = "${allVideos.size} 个视频"
        val average = if (allVideos.isEmpty()) 0.0
        else allVideos.sumOf { it.rating }.toDouble() / allVideos.size
        binding.ratingText.text = "均分 %.1f".format(average)
    }

    private fun sortVideos(input: List<VideoItem>): List<VideoItem> = when (sortMode) {
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

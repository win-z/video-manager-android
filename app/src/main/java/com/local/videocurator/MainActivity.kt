package com.local.videocurator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.local.videocurator.databinding.ActivityMainBinding
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var gridSpanCount = 2   // 默认 M 档
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
        gridSpanCount = storage.loadGridSize()
        currentTreeUri = storage.loadTreeUri()
        allVideos += storage.loadVideos()

        requestMediaPermissionIfNeeded()
        setupList()
        setupHeader()
        render()
    }

    /** 申请 READ_MEDIA_VIDEO（Android 13+）或 READ_EXTERNAL_STORAGE */
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
        val spanCount = if (viewMode == VideoAdapter.ViewMode.GRID) gridSpanCount else 1
        val layoutManager = GridLayoutManager(this, spanCount)
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
        // ── 导入 ──
        binding.importButton.setOnClickListener { folderPicker.launch(null) }

        // ── 清空 ──
        binding.clearButton.setOnClickListener {
            if (allVideos.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
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

        // ── 网格 / 列表切换 ──
        updateViewToggleState()
        binding.gridViewButton.setOnClickListener { setViewMode(VideoAdapter.ViewMode.GRID) }
        binding.listViewButton.setOnClickListener { setViewMode(VideoAdapter.ViewMode.LIST) }

        // ── 网格图标大小 S / M / L ──
        updateGridSizeState()
        binding.sizeSmallButton.setOnClickListener  { setGridSize(3) }   // S = 3 列（小图）
        binding.sizeMediumButton.setOnClickListener { setGridSize(2) }   // M = 2 列（中图）
        binding.sizeLargeButton.setOnClickListener  { setGridSize(1) }   // L = 1 列（大图）
    }

    private fun setViewMode(mode: VideoAdapter.ViewMode) {
        viewMode = mode
        adapter.viewMode = mode
        val spanCount = if (mode == VideoAdapter.ViewMode.GRID) gridSpanCount else 1
        val lm = binding.videoRecycler.layoutManager as? GridLayoutManager
        lm?.spanCount = spanCount
        storage.saveViewMode(mode)
        updateViewToggleState()
        updateGridSizeState()
    }

    private fun setGridSize(spanCount: Int) {
        gridSpanCount = spanCount
        storage.saveGridSize(spanCount)
        if (viewMode == VideoAdapter.ViewMode.GRID) {
            val lm = binding.videoRecycler.layoutManager as? GridLayoutManager
            lm?.spanCount = spanCount
            adapter.notifyDataSetChanged()
        }
        updateGridSizeState()
    }

    private fun updateViewToggleState() {
        binding.gridViewButton.alpha = if (viewMode == VideoAdapter.ViewMode.GRID) 1.0f else 0.35f
        binding.listViewButton.alpha = if (viewMode == VideoAdapter.ViewMode.LIST) 1.0f else 0.35f
    }

    private fun updateGridSizeState() {
        val inGrid = viewMode == VideoAdapter.ViewMode.GRID
        binding.sizeSmallButton.alpha  = if (inGrid && gridSpanCount == 3) 1.0f else if (inGrid) 0.4f else 0.2f
        binding.sizeMediumButton.alpha = if (inGrid && gridSpanCount == 2) 1.0f else if (inGrid) 0.4f else 0.2f
        binding.sizeLargeButton.alpha  = if (inGrid && gridSpanCount == 1) 1.0f else if (inGrid) 0.4f else 0.2f
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
            render()
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

    /**
     * 用户手动修改星级后：
     * 1. 更新 rating
     * 2. 立即重算所有视频的 scoreValue（按 manualOrder 排序，维持桶内序号）
     * 3. 触发重命名
     */
    private fun recomputeAndRename() {
        // 立即重算 scoreValue，让 UI 能马上看到正确评分
        val ordered = allVideos.sortedBy { it.manualOrder }
        val bucketCounts = mutableMapOf<String, Int>()
        for (video in ordered) {
            val bucketKey = "%.1f".format(video.rating.coerceIn(0, 10).toDouble())
            val used = bucketCounts.getOrDefault(bucketKey, 0)
            video.scoreValue = VideoItem.composeDisplayScore(video.rating, 99 - used)
            bucketCounts[bucketKey] = used + 1
        }

        persistVideos()
        render()

        val treeUri = currentTreeUri ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                importer.recomputeScoresAndRename(treeUri, allVideos)
            }
            persistVideos()
            render()
        }
    }

    private fun render() {
        adapter.submitList(sortVideos(allVideos))
        binding.emptyText.visibility =
            if (allVideos.isEmpty()) View.VISIBLE else View.GONE
        // 更新工具栏上的状态文字
        binding.countText.text = if (allVideos.isNotEmpty()) "${allVideos.size} 个视频" else ""
    }

    private fun sortVideos(input: List<VideoItem>): List<VideoItem> = when (sortMode) {
        SortMode.MANUAL       -> input.sortedBy { it.manualOrder }
        SortMode.NAME_ASC     -> input.sortedBy { it.name.lowercase() }
        SortMode.NAME_DESC    -> input.sortedByDescending { it.name.lowercase() }
        SortMode.RATING_DESC  -> input.sortedWith(compareByDescending<VideoItem> { it.rating }.thenBy { it.manualOrder })
        SortMode.RATING_ASC   -> input.sortedWith(compareBy<VideoItem> { it.rating }.thenBy { it.manualOrder })
        SortMode.DURATION_DESC -> input.sortedWith(compareByDescending<VideoItem> { it.durationMs }.thenBy { it.manualOrder })
        SortMode.DURATION_ASC  -> input.sortedWith(compareBy<VideoItem> { it.durationMs }.thenBy { it.manualOrder })
        SortMode.MODIFIED_DESC -> input.sortedWith(compareByDescending<VideoItem> { it.lastModified }.thenBy { it.manualOrder })
        SortMode.MODIFIED_ASC  -> input.sortedWith(compareBy<VideoItem> { it.lastModified }.thenBy { it.manualOrder })
    }

    private fun persistVideos() {
        storage.saveVideos(allVideos.sortedBy { it.manualOrder })
    }

    // ── VideoAdapter.Callbacks ────────────────────────────────────────────────

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

    /**
     * 点击视频 -> 用系统播放器打开
     * ACTION_VIEW + video MIME 类型会弹出"选择应用"对话框（仅本次 / 总是）
     */
    override fun onPlay(video: VideoItem) {
        val uri = runCatching { Uri.parse(video.uri) }.getOrNull() ?: run {
            Toast.makeText(this, "无法解析视频路径", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "未找到视频播放器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun canDrag(): Boolean = sortMode == SortMode.MANUAL
}

package com.kafinet.asannet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityFileManagerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * مدیریت فایل: گشتن در حافظه‌ی گوشی، باز کردن فایل، حذف، تغییر نام، پوشه‌ی جدید،
 * کپی/جابه‌جایی، اشتراک‌گذاری، جستجو، زیپ/استخراج، مرتب‌سازی و انتخاب چندتایی.
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: FileManagerAdapter

    private data class Volume(val label: String, val root: File)

    private var volumes: List<Volume> = emptyList()
    private var currentVolume: Volume? = null
    private var current: File? = null

    private var sortKey = SORT_NAME
    private var sortAsc = true
    private var showHidden = false

    private var searchMode = false
    private var listJob: Job? = null
    private var searchJob: Job? = null

    private var busy = false

    companion object {
        private const val PREFS = "kafinet_fm"
        private const val SORT_NAME = 0
        private const val SORT_DATE = 1
        private const val SORT_SIZE = 2
        private const val SORT_TYPE = 3
        private const val REQ_LEGACY = 4411
        private const val SEARCH_LIMIT = 500

        // کلیپ‌بورد داخلی (کپی/جابه‌جایی) — تا وقتی برنامه باز است می‌ماند
        private var clipFiles: List<File> = emptyList()
        private var clipMove: Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sortKey = p.getInt("sort_key", SORT_NAME)
        sortAsc = p.getBoolean("sort_asc", true)
        showHidden = p.getBoolean("show_hidden", false)

        adapter = FileManagerAdapter(
            onClick = { row -> onRowClick(row) },
            onLongClick = { row ->
                if (!busy) {
                    adapter.toggle(row)
                    updateSelectionUi()
                }
            }
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnSearch.setOnClickListener { toggleSearch() }
        binding.btnMore.setOnClickListener { showHeaderMenu() }
        binding.btnGrant.setOnClickListener { requestAccess() }

        binding.actCopy.setOnClickListener { startClip(move = false) }
        binding.actMove.setOnClickListener { startClip(move = true) }
        binding.actDelete.setOnClickListener { confirmDelete() }
        binding.actShare.setOnClickListener { FileOps.shareFiles(this, adapter.selectedFiles()) }
        binding.actMore.setOnClickListener { showSelectionMenu() }

        binding.btnPaste.setOnClickListener { pasteHere() }
        binding.btnPasteCancel.setOnClickListener {
            clipFiles = emptyList()
            updatePasteBar()
        }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty().trim())
            }
        })
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            binding.layoutPermission.visibility = View.GONE
            if (volumes.isEmpty()) {
                setupVolumes()
            } else {
                loadCurrent()
            }
        } else {
            binding.layoutPermission.visibility = View.VISIBLE
        }
        updatePasteBar()
    }

    // ---------------------------------------------------------------- دسترسی

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_LEGACY
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LEGACY && hasAccess()) {
            binding.layoutPermission.visibility = View.GONE
            if (volumes.isEmpty()) setupVolumes()
        }
    }

    // ---------------------------------------------------------------- حافظه‌ها

    private fun setupVolumes() {
        val list = ArrayList<Volume>()
        val internal = Environment.getExternalStorageDirectory()
        list.add(Volume(getString(R.string.fm_internal), internal))
        try {
            getExternalFilesDirs(null).forEach { f ->
                if (f == null) return@forEach
                val idx = f.absolutePath.indexOf("/Android/data")
                if (idx > 0) {
                    val root = File(f.absolutePath.substring(0, idx))
                    if (root.absolutePath != internal.absolutePath && root.exists() && list.none { it.root == root }) {
                        list.add(Volume(getString(R.string.fm_sdcard), root))
                    }
                }
            }
        } catch (e: Exception) {
            // مهم نیست؛ فقط حافظه‌ی داخلی نشان داده می‌شود
        }
        volumes = list
        buildChips()
        currentVolume = list[0]
        navigate(list[0].root)
    }

    private fun volumeOf(dir: File): Volume {
        val path = dir.absolutePath
        return volumes
            .filter { path == it.root.absolutePath || path.startsWith(it.root.absolutePath + File.separator) }
            .maxByOrNull { it.root.absolutePath.length } ?: volumes[0]
    }

    private fun buildChips() {
        val box = binding.chipsContainer
        box.removeAllViews()
        fun chip(label: String, dir: File) {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 13f
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tv.setBackgroundResource(R.drawable.bg_fm_chip)
            val h = (14 * resources.displayMetrics.density).toInt()
            val v = (7 * resources.displayMetrics.density).toInt()
            tv.setPadding(h, v, h, v)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
            tv.setOnClickListener {
                if (busy) return@setOnClickListener
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                closeSearch()
                adapter.clearSelection()
                navigate(dir)
            }
            box.addView(tv, lp)
        }
        volumes.forEach { chip(it.label, it.root) }
        chip(getString(R.string.fm_downloads), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        chip(getString(R.string.fm_camera), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
        chip(getString(R.string.fm_pictures), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
        chip(getString(R.string.fm_documents), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        chip(getString(R.string.fm_music), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
        chip(getString(R.string.fm_movies), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
    }

    // ---------------------------------------------------------------- ناوبری و لیست

    private fun navigate(dir: File) {
        current = dir
        currentVolume = volumeOf(dir)
        adapter.clearSelection()
        updateSelectionUi()
        loadCurrent()
    }

    private fun loadCurrent() {
        val dir = current ?: return
        if (searchMode && binding.editSearch.text.toString().trim().length >= 2) return
        listJob?.cancel()
        binding.progress.visibility = View.VISIBLE
        listJob = lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { listDir(dir) }
            if (!isActive) return@launch
            binding.progress.visibility = View.GONE
            adapter.showPath = false
            adapter.submit(rows)
            binding.txtEmpty.setText(R.string.fm_empty)
            binding.layoutEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            updateHeader(rows.size)
        }
    }

    private fun listDir(dir: File): List<FileRow> {
        val children = dir.listFiles() ?: return emptyList()
        val rows = ArrayList<FileRow>(children.size)
        for (c in children) {
            if (!showHidden && c.name.startsWith(".")) continue
            val isDir = c.isDirectory
            val count = if (isDir) {
                (c.list() ?: emptyArray()).count { showHidden || !it.startsWith(".") }
            } else 0
            rows.add(FileRow(c, isDir, if (isDir) 0L else c.length(), c.lastModified(), count))
        }
        return sortRows(rows)
    }

    private fun sortRows(rows: List<FileRow>): List<FileRow> {
        val base: Comparator<FileRow> = when (sortKey) {
            SORT_DATE -> compareBy { it.modified }
            SORT_SIZE -> compareBy { it.size }
            SORT_TYPE -> compareBy<FileRow> { it.file.extension.lowercase() }.thenBy { it.file.name.lowercase() }
            else -> compareBy { it.file.name.lowercase() }
        }
        val cmp = if (sortAsc) base else base.reversed()
        // پوشه‌ها همیشه اول
        return rows.sortedWith(compareByDescending<FileRow> { it.isDir }.then(cmp))
    }

    private fun updateHeader(count: Int) {
        val dir = current ?: return
        val vol = currentVolume
        binding.txtTitle.text = if (vol != null && dir.absolutePath == vol.root.absolutePath) vol.label else dir.name
        binding.txtPath.text = dir.absolutePath
        val ft = FileOps.freeAndTotal(dir)
        val storage = if (ft != null) {
            getString(R.string.fm_free_of_total, FileOps.formatSize(ft.first), FileOps.formatSize(ft.second))
        } else ""
        val items = getString(R.string.fm_items_count, PersianDateUtils.toPersianDigits(count.toString()))
        binding.txtStorage.text = if (storage.isEmpty()) items else "$items  •  $storage"
    }

    private fun onRowClick(row: FileRow) {
        if (busy) return
        if (adapter.isSelecting) {
            adapter.toggle(row)
            updateSelectionUi()
            return
        }
        if (row.isDir) {
            hideKeyboard()
            closeSearch()
            navigate(row.file)
        } else {
            openOrAsk(row.file)
        }
    }

    private fun openOrAsk(file: File) {
        if (file.extension.equals("zip", true)) {
            StyledDialog.Builder(this)
                .setTitle(file.name)
                .setItems(arrayOf(getString(R.string.fm_unzip_here), getString(R.string.fm_open_with))) { _, which ->
                    if (which == 0) unzipHere(file) else FileOps.openFile(this, file)
                }
                .setNegativeButton(R.string.fm_cancel, null)
                .show()
        } else {
            FileOps.openFile(this, file)
        }
    }

    // ---------------------------------------------------------------- برگشت

    private fun handleBack() {
        when {
            busy -> {}
            adapter.isSelecting -> {
                adapter.clearSelection()
                updateSelectionUi()
            }
            searchMode -> {
                closeSearch()
                loadCurrent()
            }
            else -> {
                val dir = current
                val vol = currentVolume
                if (dir != null && vol != null && dir.absolutePath != vol.root.absolutePath && dir.parentFile != null) {
                    navigate(dir.parentFile!!)
                } else {
                    finish()
                }
            }
        }
    }

    // ---------------------------------------------------------------- جستجو

    private fun toggleSearch() {
        if (searchMode) {
            closeSearch()
            loadCurrent()
        } else {
            searchMode = true
            binding.editSearch.visibility = View.VISIBLE
            binding.editSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        if (!searchMode) return
        searchMode = false
        searchJob?.cancel()
        binding.editSearch.setText("")
        binding.editSearch.visibility = View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
    }

    private fun scheduleSearch(query: String) {
        if (!searchMode) return
        searchJob?.cancel()
        if (query.length < 2) {
            loadCurrent()
            return
        }
        val dir = current ?: return
        searchJob = lifecycleScope.launch {
            delay(350)
            binding.progress.visibility = View.VISIBLE
            val found = withContext(Dispatchers.IO) {
                val files = FileOps.search(dir, query, showHidden, SEARCH_LIMIT) { !isActive }
                sortRows(files.map { f ->
                    val isDir = f.isDirectory
                    FileRow(f, isDir, if (isDir) 0L else f.length(), f.lastModified(),
                        if (isDir) (f.list()?.size ?: 0) else 0)
                })
            }
            if (!isActive) return@launch
            binding.progress.visibility = View.GONE
            adapter.showPath = true
            adapter.submit(found)
            binding.txtEmpty.setText(R.string.fm_search_empty)
            binding.layoutEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            binding.txtStorage.text = getString(R.string.fm_items_count, PersianDateUtils.toPersianDigits(found.size.toString()))
        }
    }

    // ---------------------------------------------------------------- انتخاب

    private fun updateSelectionUi() {
        val sel = adapter.isSelecting
        binding.barSelection.visibility = if (sel) View.VISIBLE else View.GONE
        binding.btnSearch.visibility = if (sel) View.GONE else View.VISIBLE
        if (sel) {
            binding.txtTitle.text = getString(
                R.string.fm_selected_count, PersianDateUtils.toPersianDigits(adapter.selectedCount.toString())
            )
        } else {
            val dir = current
            val vol = currentVolume
            if (dir != null) {
                binding.txtTitle.text =
                    if (vol != null && dir.absolutePath == vol.root.absolutePath) vol.label else dir.name
            }
        }
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val show = clipFiles.isNotEmpty() && !adapter.isSelecting
        binding.barPaste.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val n = PersianDateUtils.toPersianDigits(clipFiles.size.toString())
            binding.txtPasteInfo.text = getString(
                if (clipMove) R.string.fm_paste_move_info else R.string.fm_paste_copy_info, n
            )
        }
    }

    private fun startClip(move: Boolean) {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) return
        clipFiles = files
        clipMove = move
        closeSearch()
        adapter.clearSelection()
        updateSelectionUi()
        loadCurrent()
    }

    // ---------------------------------------------------------------- چسباندن (کپی / جابه‌جایی)

    private fun pasteHere() {
        val dir = current ?: return
        val files = clipFiles
        if (files.isEmpty() || busy) return
        val move = clipMove
        if (files.any { it.isDirectory && FileOps.isInside(dir, it) }) {
            Toast.makeText(this, R.string.fm_folder_into_itself, Toast.LENGTH_SHORT).show()
            return
        }
        runBusy {
            var ok = true
            for (f in files) {
                if (!f.exists()) { ok = false; continue }
                if (move) {
                    if (f.parentFile?.canonicalPath == dir.canonicalPath) continue
                    if (!FileOps.moveTo(f, dir)) ok = false
                } else {
                    if (FileOps.copyRecursive(f, dir) == null) ok = false
                }
            }
            if (ok) {
                clipFiles = emptyList()
            }
            ok
        }
    }

    // ---------------------------------------------------------------- حذف

    private fun confirmDelete() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) return
        val n = PersianDateUtils.toPersianDigits(files.size.toString())
        StyledDialog.Builder(this)
            .setTitle(R.string.fm_delete)
            .setMessage(getString(R.string.fm_delete_confirm, n))
            .setPositiveButton(R.string.fm_delete) { _, _ ->
                runBusy {
                    var ok = true
                    files.forEach { if (!FileOps.deleteRecursive(it)) ok = false }
                    ok
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- منوها

    private fun showHeaderMenu() {
        if (adapter.isSelecting) {
            showSelectionMenu()
            return
        }
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        items.add(getString(R.string.fm_new_folder)); actions.add { newFolderDialog() }
        if (clipFiles.isNotEmpty()) {
            items.add(getString(R.string.fm_paste_now)); actions.add { pasteHere() }
        }
        items.add(getString(R.string.fm_sort)); actions.add { sortDialog() }
        items.add(getString(if (showHidden) R.string.fm_hide_hidden else R.string.fm_show_hidden))
        actions.add {
            showHidden = !showHidden
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("show_hidden", showHidden).apply()
            loadCurrent()
        }
        items.add(getString(R.string.fm_select_all)); actions.add {
            adapter.selectAll()
            updateSelectionUi()
        }
        StyledDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun showSelectionMenu() {
        val sel = adapter.selectedFiles()
        if (sel.isEmpty()) return
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (sel.size == 1) {
            items.add(getString(R.string.fm_rename)); actions.add { renameDialog(sel[0]) }
        }
        items.add(getString(R.string.fm_zip)); actions.add { zipSelected(sel) }
        if (sel.size == 1) {
            items.add(getString(R.string.fm_properties)); actions.add { showProperties(sel[0]) }
        }
        items.add(getString(R.string.fm_select_all)); actions.add {
            adapter.selectAll()
            updateSelectionUi()
        }
        StyledDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun sortDialog() {
        val names = arrayOf(
            getString(R.string.fm_sort_name),
            getString(R.string.fm_sort_date),
            getString(R.string.fm_sort_size),
            getString(R.string.fm_sort_type),
            getString(if (sortAsc) R.string.fm_sort_asc else R.string.fm_sort_desc)
        )
        StyledDialog.Builder(this)
            .setTitle(R.string.fm_sort)
            .setItems(names) { _, which ->
                if (which < 4) sortKey = which else sortAsc = !sortAsc
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("sort_key", sortKey).putBoolean("sort_asc", sortAsc).apply()
                loadCurrent()
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- نام‌ها: پوشه‌ی جدید و تغییر نام

    private fun inputView(initial: String): EditText {
        val et = EditText(this)
        et.setText(initial)
        et.hint = getString(R.string.fm_name_hint)
        et.setSingleLine(true)
        et.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        et.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted))
        et.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        et.gravity = Gravity.START
        if (initial.isNotEmpty()) {
            val dot = initial.lastIndexOf('.')
            et.setSelection(0, if (dot > 0) dot else initial.length)
        }
        return et
    }

    private fun wrap(v: View): FrameLayout {
        val box = FrameLayout(this)
        val pad = (4 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        box.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')

    private fun newFolderDialog() {
        val dir = current ?: return
        val et = inputView("")
        StyledDialog.Builder(this)
            .setTitle(R.string.fm_new_folder)
            .setView(wrap(et))
            .setPositiveButton(R.string.fm_ok) { _, _ ->
                val name = et.text.toString().trim()
                when {
                    !validName(name) -> toast(R.string.fm_name_invalid)
                    File(dir, name).exists() -> toast(R.string.fm_name_exists)
                    else -> {
                        if (File(dir, name).mkdirs()) loadCurrent() else toast(R.string.fm_failed)
                    }
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
        et.postDelayed({
            et.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun renameDialog(file: File) {
        val et = inputView(file.name)
        StyledDialog.Builder(this)
            .setTitle(R.string.fm_rename)
            .setView(wrap(et))
            .setPositiveButton(R.string.fm_ok) { _, _ ->
                val name = et.text.toString().trim()
                val target = File(file.parentFile, name)
                when {
                    !validName(name) -> toast(R.string.fm_name_invalid)
                    name == file.name -> {}
                    target.exists() -> toast(R.string.fm_name_exists)
                    else -> {
                        if (file.renameTo(target)) {
                            adapter.clearSelection()
                            updateSelectionUi()
                            if (searchMode) scheduleSearch(binding.editSearch.text.toString().trim()) else loadCurrent()
                        } else toast(R.string.fm_failed)
                    }
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
        et.postDelayed({
            et.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    // ---------------------------------------------------------------- زیپ / استخراج / مشخصات

    private fun zipSelected(files: List<File>) {
        val dir = current ?: return
        val base = if (files.size == 1) files[0].nameWithoutExtension.ifEmpty { files[0].name } else dir.name.ifEmpty { "archive" }
        val dest = FileOps.uniqueDest(dir, "$base.zip")
        runBusy(successMsg = R.string.fm_zip_done) { FileOps.zip(files, dest) }
    }

    private fun unzipHere(zip: File) {
        val dir = zip.parentFile ?: return
        val target = FileOps.uniqueDest(dir, zip.nameWithoutExtension.ifEmpty { "extracted" })
        runBusy(failMsg = R.string.fm_unzip_failed) { FileOps.unzip(zip, target) }
    }

    private fun showProperties(file: File) {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val size = withContext(Dispatchers.IO) { FileOps.folderSize(file) { !isActive } }
            val count = if (file.isDirectory) withContext(Dispatchers.IO) { countAll(file) } else 0
            binding.progress.visibility = View.GONE
            val sb = StringBuilder()
            sb.append(getString(R.string.fm_prop_name)).append(": ").append(file.name).append("\n")
            sb.append(getString(R.string.fm_prop_path)).append(": ").append(file.absolutePath).append("\n")
            sb.append(getString(R.string.fm_prop_type)).append(": ")
                .append(if (file.isDirectory) getString(R.string.fm_folder) else FileOps.mimeOf(file)).append("\n")
            sb.append(getString(R.string.fm_prop_size)).append(": ").append(FileOps.formatSize(size)).append("\n")
            if (file.isDirectory) {
                sb.append(getString(R.string.fm_prop_contents)).append(": ")
                    .append(getString(R.string.fm_items_count, PersianDateUtils.toPersianDigits(count.toString()))).append("\n")
            }
            sb.append(getString(R.string.fm_prop_date)).append(": ").append(FileOps.formatDate(file.lastModified()))
            StyledDialog.Builder(this@FileManagerActivity)
                .setTitle(R.string.fm_properties)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.fm_ok, null)
                .show()
        }
    }

    private fun countAll(dir: File): Int {
        var n = 0
        dir.listFiles()?.forEach {
            n++
            if (it.isDirectory) n += countAll(it)
        }
        return n
    }

    // ---------------------------------------------------------------- اجرای کار سنگین پس‌زمینه

    private fun runBusy(
        successMsg: Int = R.string.fm_done,
        failMsg: Int = R.string.fm_failed,
        work: () -> Boolean
    ) {
        if (busy) return
        busy = true
        binding.progress.visibility = View.VISIBLE
        toast(R.string.fm_working)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { work() } catch (e: Exception) { false }
            }
            busy = false
            binding.progress.visibility = View.GONE
            toast(if (ok) successMsg else failMsg)
            adapter.clearSelection()
            updateSelectionUi()
            if (searchMode && binding.editSearch.text.toString().trim().length >= 2) {
                scheduleSearch(binding.editSearch.text.toString().trim())
            } else {
                loadCurrent()
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}

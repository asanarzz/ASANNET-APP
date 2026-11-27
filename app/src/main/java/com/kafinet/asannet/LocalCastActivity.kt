package com.kafinet.asannet

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.os.Environment
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivityLocalCastBinding
import com.kafinet.asannet.databinding.ItemCastMediaBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** یک عکس/ویدیو/آهنگ از حافظه‌ی گوشی. */
data class CastMedia(val uri: Uri, val file: File, val title: String, val kind: FileKind)

/**
 * ارسال عکس، ویدیو و موزیکِ گوشی به تلویزیون (شبیه AllCast).
 * کاملاً مستقل از پخش زنده‌ی شبکه‌ها: از LocalCastEngine استفاده می‌کند، نه DlnaCaster.
 */
class LocalCastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalCastBinding
    private lateinit var adapter: CastMediaAdapter
    private lateinit var fileAdapter: FileManagerAdapter
    private var tab = FileKind.IMAGE

    // تب «پوشه‌ها»: پوشه‌ی فعلی (FileKind.FOLDER یعنی تب پوشه‌ها انتخاب شده)
    private val rootDir: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = rootDir

    companion object {
        /** مسیر یک فایل که از مدیریت فایل فرستاده می‌شود تا مستقیم روی تلویزیون پخش شود. */
        const val EXTRA_FILE = "extra_file"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalCastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CastMediaAdapter { media ->
            if (media.kind == FileKind.FOLDER) openFolder(media.file) else onMediaClick(media)
        }
        // تب «پوشه‌ها»: همان آداپتر مدیریت فایل با نوع نمایش قابل تغییر (پیش‌فرض: جزئیات)
        fileAdapter = FileManagerAdapter(
            onClick = { row ->
                if (row.isDir) openFolder(row.file)
                else onMediaClick(CastMedia(Uri.fromFile(row.file), row.file, row.file.name, FileOps.kindOf(row.file)))
            },
            onLongClick = { }
        )
        binding.recyclerMedia.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerMedia.adapter = adapter
        binding.btnView.setOnClickListener {
            FileViewMode.showDialog(this) { applyFolderView() }
        }

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val up = currentDir.parentFile
                if (tab == FileKind.FOLDER && currentDir.absolutePath != rootDir.absolutePath && up != null) {
                    openFolder(up)
                } else {
                    finish()
                }
            }
        })
        binding.btnTv.setOnClickListener { showTvPicker(null) }
        binding.btnStop.setOnClickListener {
            Thread { LocalCastEngine.stopByUser() }.start()
            LocalCastService.stop(this)
            Toast.makeText(this, R.string.lc_stopped, Toast.LENGTH_SHORT).show()
        }
        binding.tabPhotos.setOnClickListener { selectTab(FileKind.IMAGE) }
        binding.tabVideos.setOnClickListener { selectTab(FileKind.VIDEO) }
        binding.tabMusic.setOnClickListener { selectTab(FileKind.AUDIO) }
        binding.tabFiles.setOnClickListener { selectTab(FileKind.FOLDER) }

        updateTvLabel()
        selectTab(FileKind.IMAGE)

        // پخش مستقیم یک فایل که از داخل مدیریت فایل انتخاب شده
        val path = intent.getStringExtra(EXTRA_FILE)
        if (path != null && savedInstanceState == null) {
            val f = File(path)
            val kind = FileOps.kindOf(f)
            if (f.isFile && (kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO)) {
                onMediaClick(CastMedia(Uri.fromFile(f), f, f.name, kind))
            }
        }
    }

    private fun updateTvLabel() {
        val d = LocalCastEngine.selected
        binding.txtTv.text = if (d == null) getString(R.string.lc_no_tv) else getString(R.string.lc_tv_selected, d.name)
    }

    private fun selectTab(kind: FileKind) {
        tab = kind
        fun style(v: TextView, on: Boolean) {
            if (on) {
                v.setBackgroundResource(R.drawable.bg_fm_button)
                v.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                v.setBackgroundResource(R.drawable.bg_fm_chip)
                v.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            }
        }
        style(binding.tabPhotos, kind == FileKind.IMAGE)
        style(binding.tabVideos, kind == FileKind.VIDEO)
        style(binding.tabMusic, kind == FileKind.AUDIO)
        style(binding.tabFiles, kind == FileKind.FOLDER)
        binding.rowPath.visibility = if (kind == FileKind.FOLDER) View.VISIBLE else View.GONE
        if (kind == FileKind.FOLDER) {
            applyFolderView()
        } else {
            binding.recyclerMedia.layoutManager = GridLayoutManager(this, 3)
            binding.recyclerMedia.adapter = adapter
        }
        loadMedia(kind)
    }

    private fun applyFolderView() {
        fileAdapter.style = FileViewMode.style(this)
        fileAdapter.scale = FileViewMode.scale(this)
        binding.recyclerMedia.layoutManager = FileViewMode.layoutManager(this)
        binding.recyclerMedia.adapter = fileAdapter
    }

    // ---------------------------------------------------------------- فهرست رسانه‌ها

    private fun openFolder(dir: File) {
        currentDir = dir
        loadMedia(FileKind.FOLDER)
    }

    /** پوشه‌ها + فقط فایل‌های عکس/ویدیو/موزیک داخل پوشه‌ی فعلی. */
    private fun listFolder(dir: File): List<FileRow> {
        val children = dir.listFiles() ?: return emptyList()
        val folders = ArrayList<FileRow>()
        val medias = ArrayList<FileRow>()
        for (c in children.sortedBy { it.name.lowercase() }) {
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) {
                val count = (c.list() ?: emptyArray()).count { !it.startsWith(".") }
                folders.add(FileRow(c, true, 0L, c.lastModified(), count))
            } else {
                val k = FileOps.kindOf(c)
                if (k == FileKind.IMAGE || k == FileKind.VIDEO || k == FileKind.AUDIO) {
                    medias.add(FileRow(c, false, c.length(), c.lastModified(), 0))
                }
            }
        }
        return folders + medias
    }

    private fun loadMedia(kind: FileKind) {
        binding.progress.visibility = View.VISIBLE
        binding.txtEmpty.visibility = View.GONE
        if (kind == FileKind.FOLDER) {
            binding.txtPath.text = currentDir.absolutePath
            lifecycleScope.launch {
                val rows = withContext(Dispatchers.IO) { listFolder(currentDir) }
                if (tab != FileKind.FOLDER) return@launch
                binding.progress.visibility = View.GONE
                fileAdapter.submit(rows)
                binding.txtEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
            return
        }
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { queryMedia(kind) }
            if (kind != tab) return@launch
            binding.progress.visibility = View.GONE
            adapter.submit(list)
            binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryMedia(kind: FileKind): List<CastMedia> {
        val base: Uri
        var selection: String? = null
        when (kind) {
            FileKind.IMAGE -> base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            FileKind.VIDEO -> base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> {
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                selection = MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " +
                    MediaStore.Audio.Media.IS_ALARM + "=0 AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0"
            }
        }
        val out = ArrayList<CastMedia>()
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA
            )
            contentResolver.query(base, projection, selection, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")
                ?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext() && out.size < 4000) {
                        val path = c.getString(iData) ?: continue
                        val f = File(path)
                        if (!f.isFile) continue
                        out.add(CastMedia(ContentUris.withAppendedId(base, c.getLong(iId)), f, c.getString(iName) ?: f.name, kind))
                    }
                }
        } catch (e: Exception) {
            // دسترسی نیست یا خطای پایگاه رسانه؛ لیست خالی نشان داده می‌شود
        }
        return out
    }

    // ---------------------------------------------------------------- ارسال

    private fun onMediaClick(media: CastMedia) {
        val device = LocalCastEngine.selected
        if (device == null) {
            showTvPicker { d -> sendTo(d, media) }
        } else {
            sendTo(device, media)
        }
    }

    private fun sendTo(device: LocalCastEngine.Device, media: CastMedia) {
        Toast.makeText(this, getString(R.string.lc_sending, device.name), Toast.LENGTH_SHORT).show()
        Thread {
            var error: String? = null
            try {
                LocalCastEngine.castFile(device, media.file, media.title)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (error == null) {
                    LocalCastService.start(this)
                    Toast.makeText(this, R.string.lc_started, Toast.LENGTH_SHORT).show()
                } else {
                    StyledDialog.Builder(this)
                        .setTitle(R.string.lc_title)
                        .setMessage(getString(R.string.lc_failed, error))
                        .setPositiveButton(R.string.fm_ok, null)
                        .show()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- انتخاب تلویزیون

    private fun showTvPicker(onPicked: ((LocalCastEngine.Device) -> Unit)?) {
        val local = LocalCastEngine.localIpv4()
        if (local == null) {
            StyledDialog.Builder(this)
                .setTitle(R.string.lc_wifi_title)
                .setMessage(R.string.lc_wifi_msg)
                .setPositiveButton(R.string.fm_ok, null)
                .show()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val statusText = TextView(this).apply {
            text = getString(R.string.lc_scanning)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
        }
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(statusText)
            addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(8) })
        }

        val cancelled = AtomicBoolean(false)
        val dialog = StyledDialog.Builder(this)
            .setTitle(R.string.lc_pick_tv)
            .setView(root)
            .setPositiveButton(R.string.lc_rescan, null)
            .setNegativeButton(R.string.lc_close, null)
            .create()
        dialog.setOnDismissListener { cancelled.set(true) }
        dialog.show()

        val shown = HashSet<String>()
        var scanId = 0

        fun addRow(device: LocalCastEngine.Device) {
            if (!shown.add(device.controlUrl)) return
            val row = TextView(this).apply {
                text = "📺  " + device.name
                textSize = 17f
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                setPadding(dp(8), dp(14), dp(8), dp(14))
                isClickable = true
                setOnClickListener {
                    cancelled.set(true)
                    LocalCastEngine.selected = device
                    updateTvLabel()
                    dialog.dismiss()
                    if (onPicked != null) {
                        onPicked.invoke(device)
                    } else {
                        // فقط اتصال: لوگوی آسان‌نت روی تلویزیون بماند تا فایل پخش شود
                        val app = applicationContext
                        Thread {
                            try {
                                LocalCastEngine.showLogo(app, device)
                                runOnUiThread { LocalCastService.start(app) }
                            } catch (ignored: Exception) {
                            }
                        }.start()
                    }
                }
            }
            listBox.addView(row)
        }

        fun startScan() {
            scanId += 1
            val my = scanId
            cancelled.set(false)
            shown.clear()
            listBox.removeAllViews()
            statusText.text = getString(R.string.lc_scanning)
            progress.visibility = View.VISIBLE
            Thread {
                var found = 0
                LocalCastEngine.discover(this, local, cancelled) { device ->
                    found += 1
                    runOnUiThread {
                        if (my == scanId && dialog.isShowing) {
                            addRow(device)
                            statusText.text = getString(R.string.lc_choose)
                        }
                    }
                }
                runOnUiThread {
                    if (my == scanId && dialog.isShowing && !cancelled.get()) {
                        progress.visibility = View.GONE
                        if (found == 0) {
                            statusText.text = "تلویزیونی پیدا نشد. مطمئن شو تلویزیون روشن است و به همان Wi-Fi گوشی وصل است." +
                                (if (LocalCastEngine.lastDiag.isNotEmpty()) "\n\n" + LocalCastEngine.lastDiag else "")
                        }
                    }
                }
            }.start()
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            cancelled.set(true)
            startScan()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        startScan()
    }
}

class CastMediaAdapter(private val onClick: (CastMedia) -> Unit) : RecyclerView.Adapter<CastMediaAdapter.VH>() {

    private var items: List<CastMedia> = emptyList()

    inner class VH(val b: ItemCastMediaBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCastMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val dm = parent.context.resources.displayMetrics
        b.cellRoot.layoutParams.height = (dm.widthPixels / 3f - 14 * dm.density).toInt()
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val b = holder.b
        b.txtTitle.text = m.title
        b.txtTitle.visibility = if (m.kind == FileKind.IMAGE) View.GONE else View.VISIBLE
        b.txtPlay.visibility = if (m.kind == FileKind.VIDEO) View.VISIBLE else View.GONE
        if (m.kind == FileKind.FOLDER) {
            Glide.with(b.imgThumb).clear(b.imgThumb)
            b.imgThumb.setImageDrawable(null)
            b.txtNote.text = "📁"
            b.txtNote.visibility = View.VISIBLE
            b.txtTitle.visibility = View.VISIBLE
            b.cellRoot.setBackgroundColor(0xFF7C3AED.toInt())
        } else if (m.kind == FileKind.AUDIO) {
            Glide.with(b.imgThumb).clear(b.imgThumb)
            b.imgThumb.setImageDrawable(null)
            b.txtNote.text = "♪"
            b.txtNote.visibility = View.VISIBLE
            b.cellRoot.setBackgroundColor(0xFFEA580C.toInt())
        } else {
            b.txtNote.visibility = View.GONE
            b.cellRoot.setBackgroundColor(0xFF6B7280.toInt())
            Glide.with(b.imgThumb).load(m.uri).centerCrop().into(b.imgThumb)
        }
        b.cellRoot.setOnClickListener { onClick(m) }
    }

    fun submit(list: List<CastMedia>) {
        items = list
        notifyDataSetChanged()
    }
}

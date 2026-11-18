package com.example.boxplayer

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class Entry(val file: File?, val label: String, val kind: Int)

class MainActivity : AppCompatActivity() {

    companion object {
        val EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mid")
        const val OFF = 0
        const val ONE = 1
        const val FOLDER = 2
        const val ALL = 3
        const val KIND_UP = 0
        const val KIND_DIR = 1
        const val KIND_FILE = 2
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var cover: ImageView
    private lateinit var titleV: TextView
    private lateinit var artistV: TextView
    private lateinit var timeV: TextView
    private lateinit var repeatLabel: TextView
    private lateinit var pathV: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var rv: RecyclerView

    private val adapter = Adapter()
    private val ui = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var ready = false
    private var current: File? = null
    private var currentDir: File? = null
    private var queue: List<File> = emptyList()
    private var repeatMode = OFF
    private var metaToken = 0
    private val allCache = ConcurrentHashMap<String, List<File>>()
    private val scanning = ConcurrentHashMap<String, Boolean>()

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && ready) {
                try {
                    seek.progress = p.currentPosition
                    updateTime()
                } catch (e: Exception) {
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("boxplayer", MODE_PRIVATE)
        cover = findViewById(R.id.cover)
        titleV = findViewById(R.id.title)
        artistV = findViewById(R.id.artist)
        timeV = findViewById(R.id.time)
        repeatLabel = findViewById(R.id.repeatLabel)
        pathV = findViewById(R.id.path)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        btnRepeat = findViewById(R.id.btnRepeat)
        rv = findViewById(R.id.rv)

        repeatMode = prefs.getInt("repeat", OFF)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(1) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { prev() }
        btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 4
            prefs.edit().putInt("repeat", repeatMode).apply()
            updateRepeatUi()
            if (repeatMode == ALL) current?.let { ensureScan(it) }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && ready) {
                    try { mp?.seekTo(p) } catch (e: Exception) {}
                    updateTime()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        updateRepeatUi()
        ui.post(ticker)

        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startBrowsing()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startBrowsing()
        else toast("Cần cấp quyền đọc bộ nhớ để duyệt nhạc")
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        mp?.release()
        mp = null
        super.onDestroy()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> step(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    // ---------- Duyệt thư mục ----------

    private fun internalRoot(): File = Environment.getExternalStorageDirectory()

    private fun roots(): List<Pair<File, String>> {
        val res = ArrayList<Pair<File, String>>()
        val internal = internalRoot()
        res.add(internal to "Bộ nhớ trong")
        val internalCanon = try { internal.canonicalPath } catch (e: Exception) { internal.path }
        File("/storage").listFiles()?.forEach {
            val canon = try { it.canonicalPath } catch (e: Exception) { it.path }
            if (it.isDirectory && it.name != "emulated" && it.name != "self" &&
                canon != internalCanon && it.listFiles() != null
            ) res.add(it to "USB / thẻ nhớ (${it.name})")
        }
        return res
    }

    private fun startBrowsing() {
        val last = prefs.getString("last_dir", null)?.let { File(it) }
        val music = File(internalRoot(), "Music")
        val start = when {
            last != null && last.isDirectory && last.canRead() -> last
            music.isDirectory -> music
            else -> internalRoot()
        }
        loadDir(start)
    }

    private fun tracksIn(dir: File?): List<File> {
        val fs = dir?.listFiles() ?: return emptyList()
        return fs.filter { it.isFile && it.extension.lowercase() in EXT }.sortedBy { it.name.lowercase() }
    }

    private fun loadDir(dir: File?, focusFile: File? = null) {
        currentDir = dir
        val items = ArrayList<Entry>()
        if (dir == null) {
            pathV.text = "Chọn ổ lưu trữ"
            roots().forEach { items.add(Entry(it.first, it.second, KIND_DIR)) }
        } else {
            pathV.text = dir.absolutePath
            prefs.edit().putString("last_dir", dir.absolutePath).apply()
            items.add(Entry(null, ".. (lên thư mục cha)", KIND_UP))
            val all = dir.listFiles()?.filter { !it.name.startsWith(".") && it.canRead() } ?: emptyList()
            all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                .forEach { items.add(Entry(it, it.name, KIND_DIR)) }
            tracksIn(dir).forEach { items.add(Entry(it, it.name, KIND_FILE)) }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        var idx = if (focusFile != null) items.indexOfFirst { it.file == focusFile } else -1
        if (idx < 0) idx = if (items.size > 1 && dir != null) 1 else 0
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
        rv.postDelayed({ rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }, 80)
    }

    private fun goUp() {
        val d = currentDir ?: return
        val p = d.parentFile
        if (roots().any { it.first == d } || p == null || !p.canRead()) loadDir(null, d) else loadDir(p, d)
    }

    private fun onEntry(e: Entry) {
        when (e.kind) {
            KIND_UP -> goUp()
            KIND_DIR -> loadDir(e.file)
            else -> e.file?.let { play(it) }
        }
    }

    // ---------- Phát nhạc ----------

    private fun play(f: File) {
        val old = current
        current = f
        queue = tracksIn(f.parentFile)
        if (repeatMode == ALL) ensureScan(f)
        ready = false
        seek.progress = 0
        try {
            mp?.release()
            val p = MediaPlayer()
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(f.absolutePath)
            p.setOnPreparedListener { m ->
                ready = true
                seek.max = m.duration
                seek.keyProgressIncrement = 5000
                m.start()
                updatePlayIcon()
                updateTime()
            }
            p.setOnCompletionListener { onComplete() }
            p.setOnErrorListener { _, _, _ ->
                ready = false
                toast("Không phát được: " + f.name)
                updatePlayIcon()
                true
            }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) {
            toast("Lỗi mở file: " + f.name)
        }
        showMeta(f)
        updatePlayIcon()
        adapter.refresh(old, f)
    }

    private fun onComplete() {
        when (repeatMode) {
            ONE -> { mp?.seekTo(0); mp?.start() }
            FOLDER, ALL -> step(1)
            else -> updatePlayIcon()
        }
    }

    private fun trackList(cur: File): List<File> =
        if (repeatMode == ALL) (rootOf(cur)?.let { allCache[it.path] } ?: queue) else queue

    private fun step(dir: Int) {
        val cur = current ?: return
        val list = trackList(cur)
        if (list.isEmpty()) return
        val i = list.indexOf(cur).let { if (it < 0) 0 else it }
        play(list[(i + dir + list.size) % list.size])
    }

    private fun prev() {
        val p = mp
        val far = try { p != null && ready && p.currentPosition > 3000 } catch (e: Exception) { false }
        if (far) p?.seekTo(0) else step(-1)
    }

    private fun togglePlay() {
        val p = mp
        if (p == null || !ready) {
            if (current == null) toast("Chọn một bài ở danh sách bên phải")
            return
        }
        try { if (p.isPlaying) p.pause() else p.start() } catch (e: Exception) {}
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = try { mp?.isPlaying == true } catch (e: Exception) { false }
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateTime() {
        val p = mp ?: return
        if (!ready) return
        try { timeV.text = fmt(p.currentPosition) + " / " + fmt(p.duration) } catch (e: Exception) {}
    }

    private fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun updateRepeatUi() {
        val (txt, color) = when (repeatMode) {
            ONE -> "Lặp 1 bài" to 0xFF82B1FF.toInt()
            FOLDER -> "Lặp cả thư mục" to 0xFFFFD54F.toInt()
            ALL -> "Lặp tất cả thư mục" to 0xFF69F0AE.toInt()
            else -> "Không lặp (hết bài thì dừng)" to 0xFF888888.toInt()
        }
        btnRepeat.setImageResource(if (repeatMode == ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.setColorFilter(color)
        repeatLabel.text = txt
        repeatLabel.setTextColor(color)
    }

    // ---------- Quét tất cả bài trong ổ lưu trữ (cho chế độ lặp tất cả) ----------

    private fun rootOf(f: File): File? = roots().map { it.first }.firstOrNull { f.path.startsWith(it.path + "/") }

    private fun ensureScan(f: File) {
        val root = rootOf(f) ?: return
        if (allCache.containsKey(root.path)) return
        if (scanning.putIfAbsent(root.path, true) != null) return
        Thread {
            val out = ArrayList<File>()
            scan(root, 0, out)
            allCache[root.path] = out.sortedBy { it.path.lowercase() }
            scanning.remove(root.path)
        }.start()
    }

    private fun scan(d: File, depth: Int, out: MutableList<File>) {
        if (depth > 12) return
        val fs = d.listFiles() ?: return
        for (x in fs) {
            if (x.name.startsWith(".")) continue
            if (x.isDirectory) {
                if (x.name != "Android") scan(x, depth + 1, out)
            } else if (x.extension.lowercase() in EXT) out.add(x)
        }
    }

    // ---------- Ảnh bìa + tag ----------

    private fun showMeta(f: File) {
        titleV.text = f.nameWithoutExtension
        artistV.text = f.parentFile?.name ?: ""
        cover.setImageResource(R.drawable.ic_cover)
        val token = ++metaToken
        Thread {
            var title: String? = null
            var artist: String? = null
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val pic = r.embeddedPicture
                if (pic != null) bmp = decode(pic)
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) {}
            }
            if (bmp == null) bmp = folderCover(f.parentFile)
            val b = bmp
            val t = title
            val a = artist
            runOnUiThread {
                if (token == metaToken) {
                    if (!t.isNullOrBlank()) titleV.text = t
                    if (!a.isNullOrBlank()) artistV.text = a
                    if (b != null) cover.setImageBitmap(b)
                }
            }
        }.start()
    }

    private fun sample(w: Int): Int {
        var s = 1
        while (w / s > 1000) s *= 2
        return s
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    private fun folderCover(dir: File?): Bitmap? {
        if (dir == null) return null
        val names = listOf("cover", "folder", "front", "album", "albumart")
        val exts = listOf("jpg", "jpeg", "png")
        for (n in names) for (x in exts) {
            val f = File(dir, "$n.$x")
            if (f.isFile) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth) }
                val b = BitmapFactory.decodeFile(f.path, o2)
                if (b != null) return b
            }
        }
        return null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Adapter ----------

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val text: TextView = v.findViewById(R.id.text)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<Entry> = emptyList()

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_entry, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.text.text = e.label
            h.icon.setImageResource(if (e.kind == KIND_FILE) R.drawable.ic_music else R.drawable.ic_folder)
            val playing = e.kind == KIND_FILE && e.file == current
            val c = when {
                playing -> 0xFF82B1FF.toInt()
                e.kind == KIND_FILE -> 0xFFEEEEEE.toInt()
                else -> 0xFFFFD54F.toInt()
            }
            h.text.setTextColor(c)
            h.icon.setColorFilter(c)
            h.itemView.setOnClickListener { onEntry(e) }
        }

        fun refresh(a: File?, b: File?) {
            items.forEachIndexed { i, e ->
                if (e.file != null && (e.file == a || e.file == b)) notifyItemChanged(i)
            }
        }
    }
}

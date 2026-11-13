package app.flit

/**
 * One item queued for sending. Identity is [key], so the same file selected from two tabs
 * (for example Images and Internal storage) is only ever stored once.
 *
 * [rel] is the relative path used on the Receiver (folder structure is preserved for folder sends).
 * [splits] lists the extra APK paths of a split-APK app (empty for ordinary files).
 */
class Item(
    val key: String,
    val name: String,
    val size: Long,
    val path: String,
    val type: Int,
    val rel: String = name,
    val splits: List<String> = emptyList()
)

/**
 * The single global selection shared by every Sender tab. Main-thread only:
 * background loaders must post results to the main thread before calling in.
 */
object Sel {

    fun interface Listener {
        fun onChanged()
    }

    private val map = LinkedHashMap<String, Item>()
    private val folders = HashSet<String>()
    private val listeners = ArrayList<Listener>()

    var totalSize: Long = 0L
        private set

    val count: Int
        get() = map.size

    fun fileKey(path: String): String = "f:$path"
    fun appKey(pkg: String): String = "a:$pkg"

    fun has(key: String): Boolean = map.containsKey(key)

    fun snapshot(): List<Item> = ArrayList(map.values)

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Adds or removes the item. Returns true if it is selected afterwards. */
    fun toggle(item: Item): Boolean {
        return if (map.containsKey(item.key)) {
            removeSilently(item.key)
            notifyChanged()
            false
        } else {
            addSilently(item)
            notifyChanged()
            true
        }
    }

    fun remove(key: String) {
        if (removeSilently(key)) notifyChanged()
    }

    /** True when [items] is not empty and every one of them is selected. */
    fun allSelected(items: List<Item>): Boolean = items.isNotEmpty() && items.all { has(it.key) }

    /** Selects (or deselects) all [items] with one notification. */
    fun setMany(items: List<Item>, on: Boolean) {
        for (i in items) if (on) addSilently(i) else removeSilently(i.key)
        notifyChanged()
    }

    fun clear() {
        if (map.isEmpty() && folders.isEmpty()) return
        map.clear()
        folders.clear()
        totalSize = 0L
        notifyChanged()
    }

    // ---- Folder selection (Internal storage tab) -----------------------------

    fun isFolderSelected(path: String): Boolean = folders.contains(path)

    /** Paths of the folders currently marked as selected. */
    fun selectedFolders(): List<String> = ArrayList(folders)

    /** Marks the folder and adds all its (already expanded) files, skipping duplicates. */
    fun addFolder(path: String, files: List<Item>) {
        folders.add(path)
        for (f in files) addSilently(f)
        notifyChanged()
    }

    /** Unmarks the folder (and nested marked folders) and removes every file beneath it. */
    fun removeFolder(path: String) {
        val prefix = "$path/"
        folders.remove(path)
        folders.removeAll { it.startsWith(prefix) }
        val doomed = ArrayList<String>()
        for (it in map.values) if (it.path.startsWith(prefix)) doomed.add(it.key)
        for (k in doomed) removeSilently(k, false)
        notifyChanged()
    }

    // ---- Internals -----------------------------------------------------------

    private fun addSilently(item: Item) {
        if (map.containsKey(item.key)) return
        map[item.key] = item
        totalSize += item.size
    }

    /** [clearFolders]: unchecking a single file also unchecks folders that contained it. */
    private fun removeSilently(key: String, clearFolders: Boolean = true): Boolean {
        val it = map.remove(key) ?: return false
        totalSize -= it.size
        if (totalSize < 0L) totalSize = 0L
        if (clearFolders && folders.isNotEmpty()) {
            val p = it.path
            folders.removeAll { f -> p.startsWith("$f/") }
        }
        return true
    }

    private fun notifyChanged() {
        for (l in ArrayList(listeners)) l.onChanged()
    }
}
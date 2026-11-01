package app.flit

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One History entry. History is independent of the physical file. */
data class Hist(
    val id: Long,
    val dir: Int,
    val name: String,
    val path: String,
    val size: Long,
    val type: Int,
    val root: String,
    val time: Long,
    val pkg: String,
    val vcode: Long,
    val peer: String
)

/** Resume journal entry for one partially transferred file. */
class Part(
    val key: String,
    val session: String,
    val rel: String,
    val tmpPath: String,
    val finalPath: String,
    val size: Long,
    val mtime: Long,
    val chunkSize: Int,
    val bitmap: ByteArray,
    val hashes: ByteArray,
    val updated: Long
)

/** A transfer session, used to restore state after Android kills the process. */
class Sess(
    val id: String,
    val role: Int,
    val peer: String,
    val state: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val files: Int,
    val started: Long
)

/**
 * SQLite storage: History, session journal and partial-file journal (full schema up front).
 * Methods are synchronous: call them from a background thread, or use [async], which runs on
 * a single dedicated thread so writes keep their order.
 */
object Db {

    const val RECEIVED = 0
    const val SENT = 1

    const val ROLE_RECEIVER = 0
    const val ROLE_SENDER = 1

    const val SESS_ACTIVE = 0
    const val SESS_ENDED = 1

    private const val NAME = "flit.db"
    private const val VERSION = 1

    private const val HCOLS = "id,dir,name,path,size,type,root,time,pkg,vcode,peer"
    private const val PCOLS = "pkey,session,rel,tmp_path,final_path,size,mtime,chunk_size,bitmap,hashes,updated"
    private const val SCOLS = "id,role,peer,state,total_bytes,done_bytes,files,started"

    private lateinit var helper: Helper

    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "flit-db") }

    fun init(ctx: Context) {
        helper = Helper(ctx.applicationContext)
    }

    /** Runs [work] on the database thread, then delivers the result on the main thread. */
    fun <T : Any> async(work: () -> T, done: (T) -> Unit) {
        io.execute {
            val r = try {
                work()
            } catch (e: Exception) {
                Log.e("FlitDb", "async work failed", e)
                return@execute
            }
            FlitApp.main.post { done(r) }
        }
    }

    /** Fire-and-forget write on the database thread. */
    fun post(work: () -> Unit) {
        io.execute {
            try {
                work()
            } catch (e: Exception) {
                Log.e("FlitDb", "post work failed", e)
            }
        }
    }

    // ---- History -------------------------------------------------------------

    fun addHistory(h: Hist): Long {
        val cv = ContentValues()
        cv.put("dir", h.dir)
        cv.put("name", h.name)
        cv.put("path", h.path)
        cv.put("size", h.size)
        cv.put("type", h.type)
        cv.put("root", h.root)
        cv.put("time", h.time)
        cv.put("pkg", h.pkg)
        cv.put("vcode", h.vcode)
        cv.put("peer", h.peer)
        return helper.writableDatabase.insert("history", null, cv)
    }

    /** Newest first. [dir] is [RECEIVED] or [SENT]. */
    fun history(dir: Int): List<Hist> {
        val out = ArrayList<Hist>()
        helper.readableDatabase.rawQuery(
            "SELECT $HCOLS FROM history WHERE dir=? ORDER BY time DESC, id DESC LIMIT 2000",
            arrayOf(dir.toString())
        ).use { c -> while (c.moveToNext()) out.add(readHist(c)) }
        return out
    }

    fun deleteHistory(id: Long) {
        helper.writableDatabase.delete("history", "id=?", arrayOf(id.toString()))
    }

    /** Received files with this size and type: duplicate-detection candidates. */
    fun receivedCandidates(size: Long, type: Int): List<Hist> {
        val out = ArrayList<Hist>()
        helper.readableDatabase.rawQuery(
            "SELECT $HCOLS FROM history WHERE dir=0 AND size=? AND type=? ORDER BY time DESC LIMIT 50",
            arrayOf(size.toString(), type.toString())
        ).use { c -> while (c.moveToNext()) out.add(readHist(c)) }
        return out
    }

    private fun readHist(c: Cursor) = Hist(
        c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4),
        c.getInt(5), c.getString(6), c.getLong(7), c.getString(8), c.getLong(9), c.getString(10)
    )

    // ---- Sessions ------------------------------------------------------------

    fun upsertSession(s: Sess) {
        val cv = ContentValues()
        cv.put("id", s.id)
        cv.put("role", s.role)
        cv.put("peer", s.peer)
        cv.put("state", s.state)
        cv.put("total_bytes", s.totalBytes)
        cv.put("done_bytes", s.doneBytes)
        cv.put("files", s.files)
        cv.put("started", s.started)
        helper.writableDatabase.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateSessionProgress(id: String, doneBytes: Long) {
        val cv = ContentValues()
        cv.put("done_bytes", doneBytes)
        helper.writableDatabase.update("sessions", cv, "id=?", arrayOf(id))
    }

    fun endSession(id: String) {
        val cv = ContentValues()
        cv.put("state", SESS_ENDED)
        helper.writableDatabase.update("sessions", cv, "id=?", arrayOf(id))
    }

    /** Sessions still marked active: after a restart these were interrupted by Android. */
    fun activeSessions(): List<Sess> {
        val out = ArrayList<Sess>()
        helper.readableDatabase.rawQuery(
            "SELECT $SCOLS FROM sessions WHERE state=? ORDER BY started DESC",
            arrayOf(SESS_ACTIVE.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Sess(
                        c.getString(0), c.getInt(1), c.getString(2), c.getInt(3),
                        c.getLong(4), c.getLong(5), c.getInt(6), c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    /** Removes the session row (its partial files are handled by the caller). */
    fun deleteSession(id: String) {
        helper.writableDatabase.delete("sessions", "id=?", arrayOf(id))
    }

    // ---- Partial-file journal ------------------------------------------------

    fun upsertPart(p: Part) {
        val cv = ContentValues()
        cv.put("pkey", p.key)
        cv.put("session", p.session)
        cv.put("rel", p.rel)
        cv.put("tmp_path", p.tmpPath)
        cv.put("final_path", p.finalPath)
        cv.put("size", p.size)
        cv.put("mtime", p.mtime)
        cv.put("chunk_size", p.chunkSize)
        cv.put("bitmap", p.bitmap)
        cv.put("hashes", p.hashes)
        cv.put("updated", p.updated)
        helper.writableDatabase.insertWithOnConflict("parts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun part(key: String): Part? {
        helper.readableDatabase.rawQuery("SELECT $PCOLS FROM parts WHERE pkey=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) readPart(c) else null
        }
    }

    fun partsOfSession(session: String): List<Part> {
        val out = ArrayList<Part>()
        helper.readableDatabase.rawQuery("SELECT $PCOLS FROM parts WHERE session=?", arrayOf(session))
            .use { c -> while (c.moveToNext()) out.add(readPart(c)) }
        return out
    }

    /** Parts not touched since [before] (epoch ms): purge candidates (48 h policy). */
    fun partsOlderThan(before: Long): List<Part> {
        val out = ArrayList<Part>()
        helper.readableDatabase.rawQuery(
            "SELECT $PCOLS FROM parts WHERE updated<?", arrayOf(before.toString())
        ).use { c -> while (c.moveToNext()) out.add(readPart(c)) }
        return out
    }

    fun deletePart(key: String) {
        helper.writableDatabase.delete("parts", "pkey=?", arrayOf(key))
    }

    fun deletePartsOfSession(session: String) {
        helper.writableDatabase.delete("parts", "session=?", arrayOf(session))
    }

    private fun readPart(c: Cursor) = Part(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
        c.getLong(5), c.getLong(6), c.getInt(7), c.getBlob(8), c.getBlob(9), c.getLong(10)
    )

    // ---- Helper --------------------------------------------------------------

    private class Helper(c: Context) : SQLiteOpenHelper(c, NAME, null, VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "dir INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "path TEXT NOT NULL," +
                    "size INTEGER NOT NULL," +
                    "type INTEGER NOT NULL," +
                    "root TEXT NOT NULL DEFAULT ''," +
                    "time INTEGER NOT NULL," +
                    "pkg TEXT NOT NULL DEFAULT ''," +
                    "vcode INTEGER NOT NULL DEFAULT 0," +
                    "peer TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL("CREATE INDEX history_dir_time ON history(dir, time DESC)")
            db.execSQL("CREATE INDEX history_size_type ON history(size, type)")

            db.execSQL(
                "CREATE TABLE sessions (" +
                    "id TEXT PRIMARY KEY," +
                    "role INTEGER NOT NULL," +
                    "peer TEXT NOT NULL DEFAULT ''," +
                    "state INTEGER NOT NULL," +
                    "total_bytes INTEGER NOT NULL DEFAULT 0," +
                    "done_bytes INTEGER NOT NULL DEFAULT 0," +
                    "files INTEGER NOT NULL DEFAULT 0," +
                    "started INTEGER NOT NULL)"
            )

            db.execSQL(
                "CREATE TABLE parts (" +
                    "pkey TEXT PRIMARY KEY," +
                    "session TEXT NOT NULL," +
                    "rel TEXT NOT NULL," +
                    "tmp_path TEXT NOT NULL," +
                    "final_path TEXT NOT NULL," +
                    "size INTEGER NOT NULL," +
                    "mtime INTEGER NOT NULL," +
                    "chunk_size INTEGER NOT NULL," +
                    "bitmap BLOB," +
                    "hashes BLOB," +
                    "updated INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX parts_session ON parts(session)")
        }

        // Development stage: no released versions exist, so a schema change just rebuilds.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS history")
            db.execSQL("DROP TABLE IF EXISTS sessions")
            db.execSQL("DROP TABLE IF EXISTS parts")
            onCreate(db)
        }
    }
}
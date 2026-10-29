package cz.antonin.rovergnss.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Objekt lokality (0.11.0): náčrt půdorysu, umístěné body a kóty pásmem. Obsah je JSON v [data] ([ObjSketch]) –
 * malý graf, mění se celý najednou.
 */
@Entity(
    tableName = "site_object",
    foreignKeys = [ForeignKey(entity = Job::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")],
)
data class SiteObject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val name: String,
    val createdMs: Long = System.currentTimeMillis(),
    val data: String = ObjSketch().toJson(),
)

/** Náčrt objektu (z JSON). */
val SiteObject.sketch: ObjSketch get() = ObjSketchJson.parse(data)

/** Uložení náčrtu do JSON (sloupec site_object.data). */
fun ObjSketch.toJson(): String {
    val o = JSONObject().put("closed", closed).put("nextId", nextId)
    o.put("vertices", JSONArray().also { a -> vertices.forEach { a.put(JSONObject().put("id", it.id).put("x", it.x).put("y", it.y)) } })
    o.put("places", JSONArray().also { a ->
        places.forEach { p ->
            a.put(
                JSONObject().put("id", p.id).put("pointId", p.pointId).put("type", p.type)
                    .put("vertex", p.vertex ?: JSONObject.NULL).put("edge", p.edge ?: JSONObject.NULL)
                    .put("mount", p.mount).put("x", p.x).put("y", p.y)
            )
        }
    })
    o.put("tapes", JSONArray().also { a -> tapes.forEach { a.put(JSONObject().put("id", it.id).put("a", it.a).put("b", it.b).put("len", it.len)) } })
    o.put("angles", JSONArray().also { a ->
        angles.forEach { a.put(JSONObject().put("id", it.id).put("vertex", it.vertex).put("method", it.method).put("a", it.a).put("b", it.b).put("c", it.c)) }
    })
    o.put("square", square)
    o.put("right", JSONObject().also { r -> right.forEach { (k, v) -> r.put(k.toString(), v) } })
    return o.toString()
}

/** Načtení náčrtu z JSON; chybný = prázdný. */
object ObjSketchJson {
    private fun intOrNull(j: JSONObject, k: String): Int? = if (!j.has(k) || j.isNull(k)) null else j.optInt(k)

    fun parse(s: String): ObjSketch = try {
        val o = JSONObject(s)
        val va = o.optJSONArray("vertices") ?: JSONArray()
        val pa = o.optJSONArray("places") ?: JSONArray()
        val ta = o.optJSONArray("tapes") ?: JSONArray()
        ObjSketch(
            vertices = (0 until va.length()).map { va.getJSONObject(it).let { j -> ObjVertex(j.getInt("id"), j.getDouble("x"), j.getDouble("y")) } },
            closed = o.optBoolean("closed"),
            places = (0 until pa.length()).map {
                pa.getJSONObject(it).let { j ->
                    ObjPlace(
                        j.getInt("id"), j.optLong("pointId"), j.optInt("type"), intOrNull(j, "vertex"), intOrNull(j, "edge"),
                        j.optInt("mount", ObjMount.FREE), j.optDouble("x"), j.optDouble("y"),
                    )
                }
            },
            tapes = (0 until ta.length()).map { ta.getJSONObject(it).let { j -> ObjTape(j.getInt("id"), j.getString("a"), j.getString("b"), j.getDouble("len")) } },
            nextId = o.optInt("nextId", 1),
            angles = (o.optJSONArray("angles") ?: JSONArray()).let { aa ->
                (0 until aa.length()).map { aa.getJSONObject(it).let { j ->
                    ObjAngle(j.getInt("id"), j.getInt("vertex"), j.optInt("method"), j.getDouble("a"), j.getDouble("b"), j.getDouble("c"))
                } }
            },
            square = o.optBoolean("square"),
            right = o.optJSONObject("right")?.let { r -> r.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to r.optInt(k) } }.toMap() } ?: emptyMap(),
        )
    } catch (e: Exception) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        ObjSketch()
    }

    /** Poslední chyba čtení (diagnostika). */
    @Volatile var lastError: String? = null
}

package cz.antonin.rovergnss.data

/** Vrchol náčrtu: [id] stálé, [x], [y] souřadnice náčrtu (bez měřítka, y dolů jako na obrazovce). */
data class ObjVertex(val id: Int, val x: Double, val y: Double)

/** Typ umístěného bodu. */
object ObjPointType {
    const val CORNER = 0   // roh (na vrcholu)
    const val WALL = 1     // na stěně (na úsečce, např. dveře)
    const val AXIS = 2     // v ose stěny (mimo obrys, na prodloužení stěny)
    const val CONTROL = 3  // kontrolní bod (bez vztahu k budově)
    val NAMES = listOf("roh", "na stěně", "v ose stěny", "kontrolní")
}

/** Přiložení krabičky (posun antény od rohu / stěny, 4,5 cm od hran). */
object ObjMount {
    const val OUTER = 0    // vnější roh
    const val INNER = 1    // vnitřní roh
    const val WALL = 2     // hranou na stěně
    const val FREE = 3     // volně / na tyči – bez posunu
    val NAMES = listOf("vnější roh", "vnitřní roh", "na stěně", "volně")
    const val OFFSET_M = 0.045
}

/**
 * Bod umístěný v náčrtu. [pointId] bod lokality (měření REC, zadaný, ČÚZK); 0 = čeká na měření.
 * [vertex] id vrcholu (roh), [edge] id počátečního vrcholu stěny (na stěně, v ose stěny), [x], [y] místo v náčrtu.
 */
data class ObjPlace(
    val id: Int,
    val pointId: Long,
    val type: Int,
    val vertex: Int? = null,
    val edge: Int? = null,
    val mount: Int = ObjMount.FREE,
    val x: Double = 0.0,
    val y: Double = 0.0,
)

/** Kóta pásmem mezi dvěma prvky: odkaz „v:id“ (vrchol) nebo „p:id“ (umístěný bod). */
data class ObjTape(val id: Int, val a: String, val b: String, val len: Double)

/** Obsah objektu. [closed] = obrys uzavřený (poslední vrchol spojen s prvním). */
data class ObjSketch(
    val vertices: List<ObjVertex> = emptyList(),
    val closed: Boolean = false,
    val places: List<ObjPlace> = emptyList(),
    val tapes: List<ObjTape> = emptyList(),
    val nextId: Int = 1,
) {
    /** Stěny: dvojice (počáteční, koncový) index vrcholu. */
    fun edges(): List<Pair<Int, Int>> {
        val n = vertices.size
        if (n < 2) return emptyList()
        val l = (0 until n - 1).map { it to it + 1 }.toMutableList()
        if (closed && n >= 3) l.add(n - 1 to 0)
        return l
    }

    fun vIndex(id: Int?): Int = vertices.indexOfFirst { it.id == id }

    /** Index stěny začínající vrcholem s id [startId] (−1 = není). */
    fun edgeIndex(startId: Int?): Int {
        val i = vIndex(startId)
        if (i < 0) return -1
        return edges().indexOfFirst { it.first == i }
    }
}

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
    val label: String = "",   // plánovaný název (pomocný bod P1…), dokud není změřen
)

/** Kóta pásmem mezi dvěma prvky: odkaz „v:id“ (vrchol) nebo „p:id“ (umístěný bod). */
data class ObjTape(val id: Int, val a: String, val b: String, val len: Double)

/**
 * Úhel rohu pásmem (trojúhelník, 0.11.7) ve vrcholu [vertex]: [a], [b] odměřeno od rohu, [c] mezi konci.
 * [method] 0 = podél obou stěn (vnitřní roh, nebo uvnitř budovy) – změřený úhel = úhel mezi stěnami;
 * 1 = jedna stěna prodloužená za roh (vnější roh zvenku) – úhel mezi stěnami = 180° − změřený.
 */
data class ObjAngle(val id: Int, val vertex: Int, val method: Int, val a: Double, val b: Double, val c: Double) {
    /** Změřený úhel trojúhelníku [°] (null = délky netvoří trojúhelník). */
    val measured: Double?
        get() {
            val cs = (a * a + b * b - c * c) / (2 * a * b)
            return if (a <= 0 || b <= 0 || cs < -1.0001 || cs > 1.0001) null else Math.toDegrees(Math.acos(cs.coerceIn(-1.0, 1.0)))
        }

    /** Úhel mezi směry stěn z rohu [°] (90 = pravý). */
    val wallAngle: Double? get() = measured?.let { if (method == 1) 180 - it else it }
}

/** Pravý úhel ve vrcholu: automaticky podle náčrtu (u pravoúhlé budovy, 90° ± 15°), vždy, nikdy. */
object ObjRight {
    const val AUTO = 0
    const val YES = 1
    const val NO = 2
    val NAMES = listOf("auto", "ano", "ne")
}

/**
 * Obsah objektu. [closed] = obrys uzavřený (poslední vrchol spojen s prvním). [square] = budova převážně pravoúhlá
 * (vrcholy nakreslené zhruba pravoúhle dostanou vazbu 90°), [right] = volba u vrcholu (id → [ObjRight]).
 */
data class ObjSketch(
    val vertices: List<ObjVertex> = emptyList(),
    val closed: Boolean = false,
    val places: List<ObjPlace> = emptyList(),
    val tapes: List<ObjTape> = emptyList(),
    val nextId: Int = 1,
    val angles: List<ObjAngle> = emptyList(),
    val square: Boolean = false,
    val right: Map<Int, Int> = emptyMap(),
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

    /** Sousední vrcholy (indexy) vrcholu i podle stěn; null = koncový vrchol otevřeného obrysu. */
    fun neighbors(i: Int): Pair<Int, Int>? {
        val n = vertices.size
        if (n < 3) return null
        val prev = if (i > 0) i - 1 else if (closed) n - 1 else return null
        val next = if (i < n - 1) i + 1 else if (closed) 0 else return null
        return prev to next
    }

    /** Úhel mezi stěnami ve vrcholu i podle náčrtu [°]. */
    fun sketchAngle(i: Int): Double? {
        val (p, q) = neighbors(i) ?: return null
        val v = vertices[i]
        val a1 = Math.atan2(vertices[p].y - v.y, vertices[p].x - v.x)
        val a2 = Math.atan2(vertices[q].y - v.y, vertices[q].x - v.x)
        var d = Math.toDegrees(Math.abs(a1 - a2))
        if (d > 180) d = 360 - d
        return d
    }

    /** Vrchol i má vazbu pravého úhlu (volba u vrcholu, jinak u pravoúhlé budovy podle náčrtu 90° ± 20°). */
    fun isRight(i: Int): Boolean {
        val id = vertices.getOrNull(i)?.id ?: return false
        if (neighbors(i) == null) return false
        return when (right[id] ?: ObjRight.AUTO) {
            ObjRight.YES -> true
            ObjRight.NO -> false
            else -> square && (sketchAngle(i)?.let { kotlin.math.abs(it - 90) <= 15 } == true)
        }
    }

    /** Index stěny začínající vrcholem s id [startId] (−1 = není). */
    fun edgeIndex(startId: Int?): Int {
        val i = vIndex(startId)
        if (i < 0) return -1
        return edges().indexOfFirst { it.first == i }
    }
}

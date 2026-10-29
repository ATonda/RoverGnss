package cz.antonin.rovergnss.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Uživatelský profil – sada nastavení pro způsob práce (např. „Výtyčka 2,000 m“, „Stativ 1,532 m“).
 * Přepíná se v Živě; při výběru se přepne i profil roveru ([roverProfile] ≥ 0).
 */
data class UserProfile(
    val name: String,
    val antennaH: Double = 0.0,      // výška antény nad značkou [m]
    val recordEvery: Int = 1,        // záznam trasy: každá n-tá epocha
    val recordRaw: Boolean = true,   // záznam trasy: i surový proud
    val measureSec: Int = 180,       // měření kontrolního bodu [s], 0 = ručně
    val measStatic: Boolean = true,  // X20 při měření ve statickém modelu
    val measRaw: Boolean = false,    // RAWX/SFRBX do .ubx
    val surveyMaxSec: Int = 60,      // zaměření bodu: nejdéle [s]
    val surveySigma: Double = 0.02,  // zaměření bodu: konec při σ vodorovně ≤ [m]
    val surveyMinEpochs: Int = 10,   // zaměření bodu: aspoň epoch
    val stakeSec: Int = 5,           // vytyčení: průměrování polohy [s]
    val roverProfile: Int = -1,      // profil roveru (index), -1 = neměnit – starší profily, nahrazeno [roverName]
    val roverName: String = "",      // nastavení přijímače: název profilu v roveru, "" = neměnit
    // měření bodu tlačítkem REC (0.10.0)
    val pointMinFixSec: Int = 30,    // aspoň s RTK fix
    val pointSigmaH: Double = 0.02,  // σ průměru vodorovně [m]
    val pointSigmaU: Double = 0.03,  // σ průměru výšky [m]
    val pointJumpH: Double = 0.05,   // skok během fixu vodorovně [m]
    val pointJumpV: Double = 0.10,   // skok během fixu ve výšce [m]
    val pointTimeoutSec: Int = 180,  // „nejde změřit“ po [s]
    val pointVerify: Boolean = false, // ověření fixu (nové určení fixu)
) {
    /** Cíl měření bodu (REC). */
    val pointGoal: PointGoal
        get() = PointGoal(pointMinFixSec, pointSigmaH, pointSigmaU, pointJumpH, pointJumpV, pointTimeoutSec, pointVerify)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("antennaH", antennaH).put("recordEvery", recordEvery).put("recordRaw", recordRaw)
        .put("measureSec", measureSec).put("measStatic", measStatic).put("measRaw", measRaw)
        .put("surveyMaxSec", surveyMaxSec).put("surveySigma", surveySigma).put("surveyMinEpochs", surveyMinEpochs)
        .put("stakeSec", stakeSec).put("roverProfile", roverProfile).put("roverName", roverName)
        .put("pointMinFixSec", pointMinFixSec).put("pointSigmaH", pointSigmaH).put("pointSigmaU", pointSigmaU)
        .put("pointJumpH", pointJumpH).put("pointJumpV", pointJumpV).put("pointTimeoutSec", pointTimeoutSec)
        .put("pointVerify", pointVerify)

    companion object {
        fun fromJson(o: JSONObject) = UserProfile(
            name = o.optString("name", "Profil"),
            antennaH = o.optDouble("antennaH", 0.0),
            recordEvery = o.optInt("recordEvery", 1),
            recordRaw = o.optBoolean("recordRaw", true),
            measureSec = o.optInt("measureSec", 180),
            measStatic = o.optBoolean("measStatic", true),
            measRaw = o.optBoolean("measRaw", false),
            surveyMaxSec = o.optInt("surveyMaxSec", 60),
            surveySigma = o.optDouble("surveySigma", 0.02),
            surveyMinEpochs = o.optInt("surveyMinEpochs", 10),
            stakeSec = o.optInt("stakeSec", 5),
            roverProfile = o.optInt("roverProfile", -1),
            roverName = o.optString("roverName", ""),
            pointMinFixSec = o.optInt("pointMinFixSec", 30),
            pointSigmaH = o.optDouble("pointSigmaH", 0.02),
            pointSigmaU = o.optDouble("pointSigmaU", 0.03),
            pointJumpH = o.optDouble("pointJumpH", 0.05),
            pointJumpV = o.optDouble("pointJumpV", 0.10),
            pointTimeoutSec = o.optInt("pointTimeoutSec", 180),
            pointVerify = o.optBoolean("pointVerify", false),
        )

        /** Výchozí profily (aplikace 0.8.2); stejné názvy mají profily v roveru (zapíše je aplikace). */
        fun defaults(): List<UserProfile> = listOf(
            UserProfile(name = "Automat", antennaH = 0.04, recordEvery = 1, recordRaw = true, measureSec = 180,
                measStatic = true, measRaw = false, roverName = "Automat"),
            UserProfile(name = "Chůze", antennaH = 0.04, recordEvery = 1, recordRaw = true, measureSec = 180,
                measStatic = true, measRaw = false, roverName = "Chůze"),
            UserProfile(name = "Měření", antennaH = 0.04, recordEvery = 1, recordRaw = true, measureSec = 300,
                measStatic = true, measRaw = true, roverName = "Měření"),
            UserProfile(name = "Auto", antennaH = 0.04, recordEvery = 5, recordRaw = false, measureSec = 0,
                measStatic = true, measRaw = false, roverName = "Auto"),
        )

        fun listToJson(list: List<UserProfile>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<UserProfile> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

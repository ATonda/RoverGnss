package cz.antonin.rovergnss.data

import android.content.Context
import cz.antonin.rovergnss.ntrip.NtripProfile
import org.json.JSONArray
import org.json.JSONObject

/** Nastavení aplikace (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("rovergnss", Context.MODE_PRIVATE)

    var roverAddress: String?
        get() = sp.getString("roverAddress", null)
        set(v) = sp.edit().putString("roverAddress", v).apply()

    /** Aktivní zakázka (0 = ještě nezvolená) */
    var activeJobId: Long
        get() = sp.getLong("activeJobId", 0)
        set(v) = sp.edit().putLong("activeJobId", v).apply()

    /** Poslední pohled mapy [lat, lon, zoom] (null = ještě nebyl) */
    var mapCamera: DoubleArray?
        get() = sp.getString("mapCamera", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 3 }?.toDoubleArray()
        set(v) = sp.edit().putString("mapCamera", v?.joinToString(",")).apply()

    /** Mapa sleduje polohu roveru */
    var mapFollow: Boolean
        get() = sp.getBoolean("mapFollow", true)
        set(v) = sp.edit().putBoolean("mapFollow", v).apply()

    /** Mapa přes celou obrazovku (bez panelu údajů a lišty karet) */
    var mapFull: Boolean
        get() = sp.getBoolean("mapFull", false)
        set(v) = sp.edit().putBoolean("mapFull", v).apply()

    /** Natočení mapy: 0 sever nahoře, 1 směr pohybu nahoře, 2 volně */
    var mapRotate: Int
        get() = sp.getInt("mapRotate", 0)
        set(v) = sp.edit().putInt("mapRotate", v).apply()




    /** Vrstva bodových polí ČÚZK na mapě */
    var showCuzk: Boolean
        get() = sp.getBoolean("showCuzk", false)
        set(v) = sp.edit().putBoolean("showCuzk", v).apply()

    var ntripIndex: Int
        get() = sp.getInt("ntripIndex", 0)
        set(v) = sp.edit().putInt("ntripIndex", v).apply()

    /** 0 OpenFreeMap (vektor), 1 ČÚZK ortofoto, 2 ČÚZK ZTM */
    var mapStyle: Int
        get() = sp.getInt("mapStyle", 0)
        set(v) = sp.edit().putInt("mapStyle", v).apply()




    // ---------------------------------------------------------------- uživatelské profily
    /** Uživatelské profily; při prvním spuštění se založí „Výchozí“ z dřívějších samostatných hodnot. */
    var userProfiles: List<UserProfile>
        get() {
            val l = sp.getString("userProfiles", null)?.let { UserProfile.listFromJson(it) } ?: emptyList()
            if (l.isNotEmpty()) return l
            return listOf(
                UserProfile(
                    name = "Výchozí",
                    antennaH = sp.getFloat("antennaH", 0f).toDouble(),
                    recordEvery = sp.getInt("recordEvery", 1),
                    recordRaw = sp.getBoolean("recordRaw", true),
                    measureSec = sp.getInt("measureSec", 180),
                    measStatic = sp.getBoolean("measStatic", true),
                    measRaw = sp.getBoolean("measRaw", false),
                )
            )
        }
        set(v) = sp.edit().putString("userProfiles", UserProfile.listToJson(v)).apply()

    var userIndex: Int
        get() = sp.getInt("userIndex", 0)
        set(v) = sp.edit().putInt("userIndex", v).apply()

    /** Aktivní uživatelský profil. */
    val user: UserProfile
        get() = userProfiles.let { it.getOrElse(userIndex) { _ -> it.first() } }

    /** Změna aktivního profilu. */
    fun updateUser(f: (UserProfile) -> UserProfile) {
        val l = userProfiles.toMutableList()
        val i = userIndex.coerceIn(0, l.size - 1)
        l[i] = f(l[i])
        userProfiles = l
    }

    // hodnoty z aktivního uživatelského profilu (čtení i zápis)
    var antennaH: Double
        get() = user.antennaH
        set(v) = updateUser { it.copy(antennaH = v) }
    var recordEvery: Int
        get() = user.recordEvery
        set(v) = updateUser { it.copy(recordEvery = v) }
    var recordRaw: Boolean
        get() = user.recordRaw
        set(v) = updateUser { it.copy(recordRaw = v) }
    var measureSec: Int
        get() = user.measureSec
        set(v) = updateUser { it.copy(measureSec = v) }
    val measStatic: Boolean get() = user.measStatic
    val measRaw: Boolean get() = user.measRaw

    /** Poslední název zaměřeného bodu (pro číslování dalšího). */
    var lastSurveyName: String?
        get() = sp.getString("lastSurveyName", null)
        set(v) = sp.edit().putString("lastSurveyName", v).apply()

    /** Panel Živě: false = okamžité hodnoty, true = průměr od posledního ustálení. */
    var liveAverage: Boolean
        get() = sp.getBoolean("liveAverage", false)
        set(v) = sp.edit().putBoolean("liveAverage", v).apply()

    /** Pořadí vytyčování: 0 podle názvu, 1 nejbližší další. */
    var stakeOrder: Int
        get() = sp.getInt("stakeOrder", 0)
        set(v) = sp.edit().putInt("stakeOrder", v).apply()

    var ntripProfiles: List<NtripProfile>
        get() {
            val s = sp.getString("ntripProfiles", null) ?: return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    NtripProfile(
                        name = o.optString("name"),
                        host = o.optString("host"),
                        port = o.optInt("port", 2101),
                        mount = o.optString("mount"),
                        user = o.optString("user"),
                        pass = o.optString("pass"),
                        ggaSec = o.optInt("ggaSec", 10),
                        v2 = o.optBoolean("v2", false),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(list) {
            val a = JSONArray()
            list.forEach { p ->
                a.put(
                    JSONObject()
                        .put("name", p.name).put("host", p.host).put("port", p.port).put("mount", p.mount)
                        .put("user", p.user).put("pass", p.pass).put("ggaSec", p.ggaSec).put("v2", p.v2)
                )
            }
            sp.edit().putString("ntripProfiles", a.toString()).apply()
        }
}

package selo.Nachipa01.domain

import kotlin.math.*

/**
 * The 20 supported cities and their neighbourhoods (generated from functions/src/data/cities.json — keep in sync;
 * CitiesTest checks they match). Same city detection and stand-in positions as functions/src/parcel-rules.ts.
 */
data class City(val id: String, val name: String, val province: String, val lat: Double, val lng: Double, val radiusKm: Double, val neighbourhoods: List<String>)

object Cities {
    val ALL = listOf(
        City("lusaka", "Lusaka", "Lusaka", -15.4167, 28.2833, 18.0, listOf("Avondale", "Barlastone", "Bauleni", "Cathedral Hill", "Chainda", "Chaisa", "Chalala", "Chawama", "Chazanga", "Chelston", "Chilenje", "Chipata Compound", "Chudleigh", "Emmasdale", "Fairview", "Foxdale", "Garden", "George", "Handsworth", "Ibex Hill", "Jack", "John Laing", "Kabanana", "Kabulonga", "Kabwata", "Kalikiliki", "Kalingalinga", "Kalundu", "Kamanga", "Kamwala", "Kanyama", "Kaunda Square", "Leopards Hill", "Libala", "Lilanda", "Linda", "Longacres", "Lusaka CBD", "Lusaka West", "Madras", "Makeni", "Mandevu", "Marapodi", "Matero", "Meanwood", "Misisi", "Mtendere", "Munali", "Mutendere", "New Kasama", "Ng'ombe", "Northmead", "Nyumba Yanga", "Olympia", "PHI", "Prospect Hill", "Rhodes Park", "Roma", "Roma Park", "Salama Park", "State Lodge", "Thorn Park", "Twin Palm", "Villa Elizabetta", "Woodlands", "Woodlands Extension")),
        City("kitwe", "Kitwe", "Copperbelt", -12.8024, 28.2132, 14.0, listOf("Buchi", "Bulangililo", "Chachacha", "Chamboli", "Chimwemwe", "Garneton", "Ipusukilo", "Kamitondo", "Kandabwe", "Kawama", "Kitwe CBD", "Kwacha", "Luangwa", "Mindolo", "Mufuchani", "Mulenga", "Ndeke", "Ndeke Village", "Nkana East", "Nkana West", "Parklands", "Race Course", "Riverside", "Twatasha", "Wusakile")),
        City("livingstone", "Livingstone", "Southern", -17.8419, 25.8543, 10.0, listOf("Airport Area", "Dambwa Central", "Dambwa North", "Dambwa South", "Elephant's Walk", "Highlands", "Libuyu", "Linda", "Livingstone CBD", "Malota", "Maramba", "Mwandi Road", "Ngwenya", "Nottie Broadie", "Zambezi Sawmills")),
        City("choma", "Choma", "Southern", -16.8065, 26.953, 7.0, listOf("Choma CBD", "Highlands", "Kalundu", "Lusumpuko", "Mapanza Road", "Masuku", "Muchila", "Mwiinga", "Railway", "Shampande")),
        City("chingola", "Chingola", "Copperbelt", -12.529, 27.8834, 9.0, listOf("Buntungwa", "Chikola", "Chingola CBD", "Chiwempala", "Kabundi", "Kasompe", "Lulamba", "Mutenda", "Nchanga North", "Nchanga South", "Riverside", "Sabina")),
        City("chililabombwe", "Chililabombwe", "Copperbelt", -12.3667, 27.8333, 7.0, listOf("Chililabombwe CBD", "Hillcrest", "Kakoso", "Kasumbalesa Road", "Kawama", "Lubengele", "Mapalo", "Mine Township")),
        City("ndola", "Ndola", "Copperbelt", -12.9587, 28.6366, 14.0, listOf("Bwana Mkubwa", "Chifubu", "Chipulukusu", "Dola Hill", "Hillcrest", "Itawa", "Kabushi", "Kanini", "Kansenshi", "Kawama", "Lubuto", "Masala", "Mushili", "Ndeke", "Ndola CBD", "Nkwazi", "Northrise", "Pamodzi", "Skyways", "Twapia")),
        City("solwezi", "Solwezi", "North-Western", -12.1833, 26.4, 9.0, listOf("Kabitaka", "Kandundu", "Kanuma", "Kimasala", "Kyawama", "Mapunga", "Messengers", "Mitec", "Mushitala", "Shilenda", "Solwezi CBD")),
        City("chipata", "Chipata", "Eastern", -13.6333, 32.65, 9.0, listOf("Chipata CBD", "Chipata Motel Area", "Dilika", "Hill Side", "Kanjala", "Kapata", "Magazine", "Mchini", "Moth", "Mtore", "Navutika")),
        City("mazabuka", "Mazabuka", "Southern", -15.856, 27.748, 8.0, listOf("Hillview", "Kabobola", "Kaonga", "Lubombo", "Mazabuka CBD", "Mugoto", "Musikili", "Nakambala", "Ndeke")),
        City("katete", "Katete", "Eastern", -14.05, 32.05, 6.0, listOf("Chikhuni", "Chimtende", "Kapoche", "Katete CBD", "Mtandaza", "St Francis Area")),
        City("mufulira", "Mufulira", "Copperbelt", -12.55, 28.24, 9.0, listOf("Butondo", "Chibolele", "Clarke Township", "Kamuchanga", "Kansuswa", "Kantanshi", "Kawama", "Mokambo Road", "Mufulira CBD", "Murundu")),
        City("mongu", "Mongu", "Western", -15.25, 23.1333, 8.0, listOf("Imwiko", "Kalinga", "Katongo", "Lealui Road", "Lewanika", "Limulunga Road", "Mongu CBD", "Mulambwa", "Namushakende")),
        City("kabwe", "Kabwe", "Central", -14.4469, 28.4464, 10.0, listOf("Bwacha", "Chililalila", "Highridge", "Kabwe CBD", "Kalonga", "Kasanda", "Katondo", "Luangwa", "Makandanyama", "Mine Township", "Mukobeko", "Nakoli", "Ngungu", "Railway")),
        City("kafue", "Kafue", "Lusaka", -15.769, 28.1814, 8.0, listOf("Chanyanya", "Kafue CBD", "Kafue Estates", "Kafue Gorge Road", "Lukolongo", "Mtendere", "Nakambala", "Shikoswe", "Zambia Compound")),
        City("chilanga", "Chilanga", "Lusaka", -15.56, 28.27, 7.0, listOf("Cement Factory Area", "Chilanga CBD", "Kafue Road", "Kalundu", "Lilayi", "Mount Makulu", "Mwembeshi Road", "Nyemba")),
        City("chongwe", "Chongwe", "Lusaka", -15.3292, 28.682, 7.0, listOf("Chalimbana", "Chongwe CBD", "Kanakantapa", "Lwimba", "Ngwerere", "Palabana", "Silverest")),
        City("kapiri_mposhi", "Kapiri Mposhi", "Central", -13.97, 28.68, 7.0, listOf("Chibwe", "Kapiri Boma", "Kapiri CBD", "Kawama", "Lusaka Road", "Mukonchi Road", "Tazara Area")),
        City("serenje", "Serenje", "Central", -13.2325, 30.2352, 6.0, listOf("Boma", "Chitambo Road", "Kabundi", "Mapepala", "Muchinda", "Serenje CBD")),
        City("mkushi", "Mkushi", "Central", -13.62, 29.39, 6.0, listOf("Chalata Road", "Fiwila Road", "Kalwa", "Masansa Road", "Mkushi Boma", "Mkushi CBD")),
    )
    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    fun km(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371.0; val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** Nearest city centre among cities whose service radius covers the point, else null (outside the service area). */
    fun cityOf(lat: Double, lng: Double): City? = ALL.filter { km(lat, lng, it.lat, it.lng) <= it.radiusKm }.minByOrNull { km(lat, lng, it.lat, it.lng) }

    /** Deterministic stand-in position for a neighbourhood (FNV-1a, identical to the server). */
    fun neighbourhoodPoint(city: City, neighbourhood: String): Pair<Double, Double> {
        var h = 2166136261L
        for (ch in neighbourhood) { h = ((h xor ch.code.toLong()) * 16777619L) and 0xFFFFFFFFL }
        val ang = Math.toRadians((h % 360).toDouble())
        val dist = (0.2 + ((h ushr 9) % 60) / 100.0) * city.radiusKm * 0.6
        return (city.lat + (dist / 111) * cos(ang)) to (city.lng + (dist / (111 * cos(Math.toRadians(city.lat)))) * sin(ang))
    }

    fun nearestNeighbourhood(lat: Double, lng: Double): String? {
        val c = cityOf(lat, lng) ?: return null
        return c.neighbourhoods.minByOrNull { n -> val (a, b) = neighbourhoodPoint(c, n); km(lat, lng, a, b) }
    }

    fun sameCityProblem(aLat: Double, aLng: Double, bLat: Double, bLng: Double): String? {
        val a = cityOf(aLat, aLng) ?: return "SeloNaChipa doesn't deliver from that pickup location yet."
        val b = cityOf(bLat, bLng)
        return if (b?.id != a.id) "SeloNaChipa only supports same-city deliveries. Please enter a ${a.name} address." else null
    }
}

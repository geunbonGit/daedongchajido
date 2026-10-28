package com.odomap.core.region

// Region entries of one drive (validate_regions.py sequence(), the demo-region-sequence.json rule):
//  - a fix outside every region (sea, simplified coast, inland water) keeps the current state;
//  - the first located fix of the drive sets the start region at once;
//  - another region becomes current only after it was located continuously for dwellS (a fix located in any other region — the current one
//    included — restarts the wait; fixes outside every region do not), so border flicker (강남대로: 서초/강남) never announces;
//  - the entry time is the first fix of that continuous stay; firstVisit = not in the visited set (earlier drives + this drive so far).

/**
 * Feed one drive's fixes in time order ([push]); one tracker per drive. [visited] = region codes visited before this drive.
 * Not thread-safe (confine to the recording thread).
 */
class RegionTracker(val index: RegionIndex, visited: Set<String> = emptySet(), val dwellS: Int = 30) {
    /** A confirmed entry. start = the region the drive started in (confirmed without waiting). */
    data class Entered(val region: Region, val firstVisit: Boolean, val atMs: Long, val start: Boolean)

    private val dwellMs = dwellS * 1000L
    private val visitedCodes = LinkedHashSet(visited)
    private val entryList = ArrayList<Entered>()
    private val passedList = ArrayList<Region>()
    private val newList = ArrayList<Region>()
    private var cand: Region? = null
    private var candT = 0L

    /** confirmed current region (null before the first located fix) */
    var current: Region? = null
        private set

    /** fixes that fell outside every region */
    var unclassified: Int = 0
        private set

    /** visited region codes: the given set plus this drive's entries */
    val visited: Set<String> get() = visitedCodes

    /** every confirmed entry in order (a region can come back: 강남 → 서초 → 강남) */
    val entries: List<Entered> get() = entryList

    /** regions entered this drive, first-entry order, each once (share card "passed", first visits included) */
    val passed: List<Region> get() = passedList

    /** first visits of this drive, in order */
    val newRegions: List<Region> get() = newList

    /** One fix (ms epoch, degrees). Returns the entry it confirmed, if any. */
    fun push(t: Long, lat: Double, lng: Double): Entered? {
        val region = index.regionAt(lat, lng)
        if (region == null) {
            unclassified += 1
            return null
        }
        if (region === current) {
            cand = null
            return null
        }
        if (region !== cand) {
            cand = region
            candT = t
        }
        val start = current == null
        if (!start && t - candT < dwellMs) return null
        val entry = Entered(region, region.code !in visitedCodes, candT, start)
        if (entry.firstVisit) {
            visitedCodes.add(region.code)
            newList.add(region)
        }
        if (passedList.none { it === region }) passedList.add(region)
        entryList.add(entry)
        current = region
        cand = null
        return entry
    }
}

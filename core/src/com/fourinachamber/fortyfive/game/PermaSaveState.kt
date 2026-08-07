package com.fourinachamber.fortyfive.game

import com.badlogic.gdx.Gdx
import com.fourinachamber.fortyfive.archipelago.APClient
import com.fourinachamber.fortyfive.archipelago.APDataStorage
import com.fourinachamber.fortyfive.utils.FortyFiveLogger
import com.fourinachamber.fortyfive.utils.templateParam
import onj.builder.buildOnjObject
import onj.parser.OnjParser
import onj.parser.OnjParserException
import onj.parser.OnjSchemaParser
import onj.schema.OnjSchema
import onj.value.OnjArray
import onj.value.OnjObject
import kotlin.random.Random

object PermaSaveState {

    const val permaSaveStateVersion: Int = 0
    const val logTag = "PermaSaveState"
    const val saveFilePath: String = "saves/perma_savefile.onj"
    const val defaultSaveFilePath: String = "saves/default_perma_savefile.onj"
    const val apCacheDefaultSaveFilePath: String = "saves/APCache/default_perma_savefile.onj"

    private val savefileSchema: OnjSchema by lazy {
        OnjSchemaParser.parseFile(Gdx.files.internal("onjschemas/perma_savefile.onjschema").file())
    }

    private var saveFileDirty: Boolean = false

    private var currentRandom: Long = 0

    var apItemLocations: MutableList<String> = mutableListOf()
        set(value) {
            field = value
            saveFileDirty = true
        }

    var apSeed: Long? = null
        set(value) {
            field = value
            saveFileDirty = true
        }

    var receivedItemIndices: MutableSet<Int> = mutableSetOf()

    val lastReceivedItemIndex: Int get() = receivedItemIndices.maxOrNull() ?: -1

    fun addReceivedIndex(index: Int) {
        receivedItemIndices.add(index)
        saveFileDirty = true
    }

    var townsUnlockedCount: Int = 0
        set(value) {
            field = value
            saveFileDirty = true
        }

    var enemiesDefeated: Int = 0
        set(value) {
            field = value
            saveFileDirty = true
        }

    var collection: List<String> = mutableListOf()
        set(value) {
            field = value
            saveFileDirty = true
        }

    var playerHasCompletedTutorial: Boolean = false
        set(value) {
            field = value
            saveFileDirty = true
        }

    var hasSeenInDevPopup: Boolean = false
        set(value) {
            field = value
            saveFileDirty = true
        }

    private var _visitedAreas: MutableSet<String> = mutableSetOf()

    val visitedAreas: Set<String>
        get() = _visitedAreas

    var playerFoughtMultipleEnemies: Boolean = false
        set(value) {
            field = value
            saveFileDirty = true
        }

    var statTotalMoneyEarned: Int by templateParam("stat.totalCashCollected", 0)
    var statEncountersWon: Int by templateParam("stat.encountersWon", 0)
    var statBulletsShot: Int by templateParam("stat.bulletsShot", 0)
    var statUsedReserves: Int by templateParam("stat.usedReserves", 0)
    var statEnemiesDefeated: Int by templateParam("stat.enemiesDefeated", 0)
    var statCardsLastRun: List<String> = listOf()

    fun read() {
        FortyFiveLogger.debug(logTag, "reading SaveState")

        val file = Gdx.files.local(saveFilePath).file()
        if (!file.exists()) copyDefaultFile()

        var obj = try {
            OnjParser.parseFile(file)
        } catch (e: OnjParserException) {
            FortyFiveLogger.debug(logTag, "Savefile invalid: ${e.message}")
            copyDefaultFile()
            OnjParser.parseFile(file)
        }

        val version = (obj as? OnjObject)?.getOr<Long?>("version", null)?.toInt()
        if (version?.equals(permaSaveStateVersion)?.not() ?: true) {
            FortyFiveLogger.warn(logTag, "incompatible perma_savefile found: version is $version; expected: $permaSaveStateVersion")
            copyDefaultFile()
            obj = OnjParser.parseFile(file)
        }

        val result = savefileSchema.check(obj)
        if (result != null) {
            FortyFiveLogger.debug(logTag, "Savefile invalid: $result")
            copyDefaultFile()
            obj = OnjParser.parseFile(Gdx.files.local(saveFilePath).file())
            savefileSchema.assertMatches(obj)
        }

        obj as OnjObject

        currentRandom = obj.get<Long?>("lastRandom") ?: Random.nextLong()
        playerHasCompletedTutorial = obj.get<Boolean>("playerHasCompletedTutorial")
        collection = obj.get<OnjArray>("collection").value.map { it.value as String }
        playerFoughtMultipleEnemies = obj.get<Boolean>("playerFoughtMultipleEnemies")
        hasSeenInDevPopup = obj.getOr("hasSeenInDevPopup", false)
        _visitedAreas = obj.get<OnjArray>("visitedAreas").value.map { it.value as String }.toMutableSet()
        apItemLocations = obj.getOr<OnjArray?>("apItemLocations", null)
            ?.value?.map { it.value as String }?.toMutableList()
            ?: mutableListOf()
        receivedItemIndices = obj.getOr<OnjArray?>("receivedItemIndices", null)
            ?.value?.map { (it.value as Long).toInt() }?.toMutableSet()
            ?: mutableSetOf()
        townsUnlockedCount = obj.getOr<Long?>("townsUnlockedCount", null)?.toInt() ?: 0
        enemiesDefeated = obj.getOr<Long?>("enemiesDefeated", null)?.toInt() ?: 0

        saveFileDirty = false
    }

    fun hasVisitedArea(area: String) = area in visitedAreas

    fun visitedNewArea(area: String) {
        _visitedAreas.add(area)
    }

    fun write() {
        if (!saveFileDirty) return
        val obj = buildOnjObject {
            "version" with permaSaveStateVersion
            "lastRandom" with currentRandom
            "collection" with collection
            "playerHasCompletedTutorial" with playerHasCompletedTutorial
            "visitedAreas" with _visitedAreas
            "playerFoughtMultipleEnemies" with playerFoughtMultipleEnemies
            "hasSeenInDevPopup" with hasSeenInDevPopup
            if (APClient.isArchipelago) {
                "apItemLocations" with apItemLocations
                "lastReceivedItemIndex" with lastReceivedItemIndex
                "receivedItemIndices" with receivedItemIndices.map { it.toLong() }
                "townsUnlockedCount" with townsUnlockedCount
                "enemiesDefeated" with enemiesDefeated
            }
        }
        Gdx.files.local(saveFilePath).file().writeText(obj.toString())
        saveFileDirty = false
        if (APClient.isArchipelago) APDataStorage.pushPerma()
    }

    fun newRun() {
        saveFileDirty = true
        currentRandom = Random.nextLong()
    }

    fun reset() {
        FortyFiveLogger.debug(logTag, "resetting perma_savefile")
        copyDefaultFile()
        read()
    }

    fun runRandom(i: Int): Long {
        val random = Random(currentRandom)
        repeat(i) { random.nextLong() } // TODO: find better solution
        return random.nextLong()
    }

    @Suppress("UNCHECKED_CAST")
    fun applyFromStorage(data: Map<String, Any>) {
        fun strings(key: String) = (data[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        fun int(key: String, def: Int) = (data[key] as? Double)?.toInt() ?: def
        fun bool(key: String, def: Boolean) = (data[key] as? Boolean) ?: def

        receivedItemIndices = ((data["receivedItemIndices"] as? List<*>)
            ?.filterIsInstance<Double>()?.map { it.toInt() } ?: emptyList()).toMutableSet()
        apItemLocations = strings("apItemLocations").toMutableList()
        townsUnlockedCount = int("townsUnlockedCount", 0)
        enemiesDefeated = int("enemiesDefeated", 0)
        collection = strings("collection")
        playerHasCompletedTutorial = bool("playerHasCompletedTutorial", false)
        _visitedAreas = strings("visitedAreas").toMutableSet()
        playerFoughtMultipleEnemies = bool("playerFoughtMultipleEnemies", false)
        hasSeenInDevPopup = bool("hasSeenInDevPopup", false)
    }

    private fun copyDefaultFile() {
        FortyFiveLogger.debug(logTag, "copying default perma save")
        val source = defaultSaveFilePath
        Gdx.files.local(source).copyTo(Gdx.files.local(saveFilePath))
    }

}
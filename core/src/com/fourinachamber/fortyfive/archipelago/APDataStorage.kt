package com.fourinachamber.fortyfive.archipelago

import com.fourinachamber.fortyfive.FortyFive
import com.fourinachamber.fortyfive.game.PermaSaveState
import com.fourinachamber.fortyfive.game.SaveState
import com.fourinachamber.fortyfive.game.UserPrefs
import com.fourinachamber.fortyfive.map.MapManager
import com.fourinachamber.fortyfive.utils.FortyFiveLogger
import io.github.archipelagomw.events.ArchipelagoEventListener
import io.github.archipelagomw.events.RetrievedEvent
import io.github.archipelagomw.network.client.SetPacket

object APDataStorage {

    private const val logTag = "APDataStorage"

    private var slotId: Int = 0
    private val permaKey get() = "fortyfive_perma_$slotId"
    private val saveKey get() = "fortyfive_save_$slotId"
    private var fetchRequestId: Int = -1

    @Volatile
    var applyingFromServer: Boolean = false
        private set

    fun init(slotId: Int) {
        this.slotId = slotId
    }

    fun fetch() {
        fetchRequestId = APClient.dataStorageGet(listOf(permaKey, saveKey)).getValue() ?: -1
        FortyFiveLogger.debug(logTag, "requested save state from AP server (slot=$slotId)")
    }

    fun pushPerma() {
        if (applyingFromServer) return
        val data = mapOf(
            "lastReceivedItemIndex" to PermaSaveState.lastReceivedItemIndex,
            "receivedItemIndices" to PermaSaveState.receivedItemIndices.toList(),
            "apItemLocations" to PermaSaveState.apItemLocations,
            "townsUnlockedCount" to PermaSaveState.townsUnlockedCount,
            "enemiesDefeated" to PermaSaveState.enemiesDefeated,
            "collection" to PermaSaveState.collection,
            "playerHasCompletedTutorial" to PermaSaveState.playerHasCompletedTutorial,
            "visitedAreas" to PermaSaveState.visitedAreas.toList(),
            "playerFoughtMultipleEnemies" to PermaSaveState.playerFoughtMultipleEnemies,
            "hasSeenInDevPopup" to PermaSaveState.hasSeenInDevPopup
        )
        val packet = SetPacket(permaKey, null)
        packet.addDataStorageOperation(SetPacket.Operation.REPLACE, data)
        APClient.dataStorageSet(packet)
        FortyFiveLogger.debug(logTag, "pushed PermaSaveState (index=${PermaSaveState.lastReceivedItemIndex})")
    }

    fun pushSave() {
        if (applyingFromServer) return
        val data = mapOf(
            "cards" to SaveState.cards,
            "playerLives" to SaveState.playerLives,
            "maxPlayerLives" to SaveState.maxPlayerLives,
            "playerMoney" to SaveState.playerMoney,
            "currentDifficulty" to SaveState.currentDifficulty,
            "currentMap" to SaveState.currentMap,
            "currentNode" to SaveState.currentNode,
            "totalMoneyEarned" to SaveState.totalMoneyEarned,
            "usedReserves" to SaveState.usedReserves,
            "enemiesDefeated" to SaveState.enemiesDefeated,
            "encountersWon" to SaveState.encountersWon,
            "bulletsShot" to SaveState.bulletsShot,
            "playerCompletedFirstTutorialEncounter" to SaveState.playerCompletedFirstTutorialEncounter,
            "lastReceivedItemIndex" to PermaSaveState.lastReceivedItemIndex
        )
        val packet = SetPacket(saveKey, null)
        packet.addDataStorageOperation(SetPacket.Operation.REPLACE, data)
        APClient.dataStorageSet(packet)
        FortyFiveLogger.debug(logTag, "pushed SaveState")
    }

    @ArchipelagoEventListener
    @Suppress("UNCHECKED_CAST")
    fun onRetrieved(event: RetrievedEvent) {
        if (event.requestID != fetchRequestId) return
        fetchRequestId = -1

        val permaData = if (event.containsKey(permaKey)) {
            event.getValueAsObject(permaKey, Map::class.java) as? Map<String, Any>
        } else null

        if (permaData == null) {
            FortyFiveLogger.debug(logTag, "no server state found; starting fresh")
            FortyFive.resetAll()
            APClient.fullyConnected.complete(Unit)
            return
        }

        val remoteIndex = (permaData["lastReceivedItemIndex"] as? Double)?.toInt() ?: -1
        val saveData = if (event.containsKey(saveKey)) {
            event.getValueAsObject(saveKey, Map::class.java) as? Map<String, Any>
        } else null

        PermaSaveState.read()
        SaveState.read()
        MapManager.read()
        UserPrefs.read()

        if (remoteIndex > PermaSaveState.lastReceivedItemIndex) {
            FortyFiveLogger.debug(logTag, "server state is newer (remote=$remoteIndex > local=${PermaSaveState.lastReceivedItemIndex}); applying")
            applyingFromServer = true
            PermaSaveState.applyFromStorage(permaData)
            PermaSaveState.write()
            if (saveData != null) {
                SaveState.applyFromStorage(saveData)
                SaveState.write()
            }
            applyingFromServer = false
        }

        APClient.fullyConnected.complete(Unit)
    }
}
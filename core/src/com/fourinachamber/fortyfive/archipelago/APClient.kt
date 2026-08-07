package com.fourinachamber.fortyfive.archipelago

import com.badlogic.gdx.Gdx
import com.fourinachamber.fortyfive.FortyFive
import com.fourinachamber.fortyfive.game.PermaSaveState
import com.fourinachamber.fortyfive.utils.FortyFiveLogger
import com.fourinachamber.fortyfive.utils.Timeline
import com.fourinachamber.fortyfive.screen.general.CenteredDragSource
import com.fourinachamber.fortyfive.rendering.NotificationOverlay
import io.github.archipelagomw.Client
import io.github.archipelagomw.ClientStatus
import io.github.archipelagomw.Print.APPrintJsonType
import io.github.archipelagomw.flags.ItemsHandling
import io.github.archipelagomw.events.ArchipelagoEventListener
import io.github.archipelagomw.events.ConnectionResultEvent
import io.github.archipelagomw.events.DeathLinkEvent
import io.github.archipelagomw.events.LocationInfoEvent
import io.github.archipelagomw.events.PrintJSONEvent
import io.github.archipelagomw.events.ReceiveItemEvent
import io.github.archipelagomw.events.RetrievedEvent
import io.github.archipelagomw.network.ConnectionResult
import io.github.archipelagomw.parts.NetworkItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class PendingAutoConnect(val host: String, val slot: String, val password: String?)

object APClient : Client() {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    const val logTag = "APClient"
    private const val GAME_NAME = "Forty-Five"
    private const val CONNECT_FILENAME = "forty-five-ap-connect.txt"
    private val AP_SAVE_FILES = listOf("savefile.onj", "perma_savefile.onj", "user_prefs.onj", "default_perma_savefile.onj")
    private val AP_MAP_DIRS = listOf("maps/roads", "maps/areas")

    // -------------------------------------------------------------------------
    // AP mode state (slot-data options received on connect)
    // -------------------------------------------------------------------------

    @Volatile
    var isArchipelago: Boolean = false

    var deathLinkMode: Int = 0  // 0 = none, 1 = lenient, 2 = torture
        private set

    val deathLinkEnabled: Boolean get() = deathLinkMode > 0

    var obscuredChoices: Boolean = false
        private set

    var hardMode: Boolean = false
        private set

    var goalCondition: Int = 0
        private set

    // -------------------------------------------------------------------------
    // Pending / in-flight state
    // -------------------------------------------------------------------------

    var pendingAutoConnect: PendingAutoConnect? = null
        private set

    var pendingLenientArea: String? = null

    private var slotCheckRequestId: Int = -1
    
    private var pendingSlot: Int = -1
    
    private var pendingFirstConnect: Boolean = false

    var connectionResultCallback: ((success: Boolean, errorMessage: String?) -> Unit)? = null

    val scoutedLocations: ConcurrentHashMap<Long, NetworkItem> = ConcurrentHashMap()

    // -------------------------------------------------------------------------
    // Coroutine infrastructure
    // -------------------------------------------------------------------------

    val fullyConnected = CompletableDeferred<Unit>()

    val waitItemsJob = Job()
    val waitItemsScope = CoroutineScope(waitItemsJob + Dispatchers.Default)

    val itemMutex = Mutex()

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    fun init() {
        System.setProperty("java.net.preferIPv4Addresses", "true")
        isArchipelago = false
        game = GAME_NAME
        itemsHandlingFlags = ItemsHandling.SEND_ITEMS or ItemsHandling.SEND_OWN_ITEMS or ItemsHandling.SEND_STARTING_INVENTORY
        eventManager.registerListener(this)
        eventManager.registerListener(APDataStorage)
        checkConnectFile()
    }

    private fun checkConnectFile() {
        val file = java.io.File(System.getProperty("user.home"), CONNECT_FILENAME)
        if (!file.exists()) return
        try {
            val props = file.readLines()
                .filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            val host = props["host"] ?: return
            val slot = props["slot"] ?: return
            val password = props["password"]?.takeIf { it.isNotBlank() }
            pendingAutoConnect = PendingAutoConnect(host, slot, password)
            FortyFiveLogger.debug(logTag, "found pending auto-connect: slot=$slot host=$host")
        } catch (e: Exception) {
            FortyFiveLogger.warn(logTag, "failed to read AP connect file: ${e.message}")
        } finally {
            file.delete()
        }
    }

    fun consumePendingAutoConnect() {
        pendingAutoConnect = null
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    fun connect(address: String, slotName: String, password: String? = null) {
        setName(slotName)
        if (password != null) setPassword(password)
        super.connect(address)
    }

    fun swapSaveFiles() {
        for (name in AP_SAVE_FILES) {
            val active = Gdx.files.local("saves/$name")
            val cached = Gdx.files.local("saves/APCache/$name")
            val temp = Gdx.files.local("saves/APCache/$name.tmp")
            if (active.exists()) active.moveTo(temp)
            if (cached.exists()) cached.moveTo(active)
            if (temp.exists()) temp.moveTo(cached)
        }
        for (dir in AP_MAP_DIRS) swapMapDirectory(dir)
        FortyFiveLogger.debug(logTag, "swapped save files with APCache")
    }

    private fun swapMapDirectory(dirPath: String) {
        val active = Gdx.files.internal(dirPath).file()
        val cache = Gdx.files.local("saves/APCache/$dirPath").file()
        val temp = Gdx.files.local("saves/APCache/${dirPath}.tmp").file()

        temp.deleteRecursively()
        if (active.exists()) active.copyRecursively(temp, overwrite = true)
        active.listFiles()?.forEach { it.deleteRecursively() }
        if (cache.exists()) cache.copyRecursively(active, overwrite = true)
        cache.deleteRecursively()
        if (temp.exists()) temp.renameTo(cache)
    }

    // -------------------------------------------------------------------------
    // Archipelago event listeners
    // -------------------------------------------------------------------------

    @ArchipelagoEventListener
    @Suppress("UNCHECKED_CAST")
    fun onConnectionResult(event: ConnectionResultEvent) {
        if (event.result == ConnectionResult.Success) {
            val slotData = event.getSlotData(Map::class.java) as? Map<String, Any>
            val deathLinkOn = when (val v = slotData?.get("death_link")) {
                is Number -> v.toInt() == 1
                is Boolean -> v
                else -> false
            }
            deathLinkMode = if (!deathLinkOn) 0 else when (val v = slotData?.get("death_link_type")) {
                is Number -> v.toInt().coerceIn(0, 1) + 1  // 0=lenient→1, 1=torture→2
                else -> 1
            }
            setDeathLinkEnabled(deathLinkOn)
            obscuredChoices = when (val v = slotData?.get("obscured_choices")) {
                is Number -> v.toInt() == 1
                is Boolean -> v
                else -> false
            }
            hardMode = when (val v = slotData?.get("hard_mode")) {
                is Number -> v.toInt() == 1
                is Boolean -> v
                else -> false
            }
            goalCondition = when (val v = slotData?.get("goal_condition")) {
                is Number -> v.toInt()
                else -> 0
            }
            FortyFiveLogger.debug(logTag, "connected to Archipelago as slot ${event.slot}; checking slot occupancy")
            pendingFirstConnect = !isArchipelago
            pendingSlot = event.slot
            slotCheckRequestId = dataStorageGet(listOf("client_status_0_${event.slot}")).getValue() ?: -1
        } else {
            FortyFiveLogger.warn(logTag, "connection failed: ${event.result}")
            val errorMessage = event.result.toString()
            Gdx.app.postRunnable {
                connectionResultCallback?.invoke(false, errorMessage)
                connectionResultCallback = null
            }
            waitItemsJob.cancel()
            fullyConnected.cancel()
        }
    }

    @ArchipelagoEventListener
    fun onSlotCheckRetrieved(event: RetrievedEvent) {
        if (event.requestID != slotCheckRequestId) return
        slotCheckRequestId = -1
        val status = (event.getObject("client_status_0_$pendingSlot") as? Double)?.toInt() ?: 0
        if (status == ClientStatus.CLIENT_PLAYING.value) {
            FortyFiveLogger.warn(logTag, "slot $pendingSlot is already occupied")
            Gdx.app.postRunnable {
                connectionResultCallback?.invoke(false, "Another person is already connected on this slot.")
                connectionResultCallback = null
            }
            waitItemsJob.cancel()
            fullyConnected.cancel()
            return
        }
        isArchipelago = true
        val locationIds = ArrayList(ItemsAndLocations.LOCATIONS.values)
        scoutLocations(locationIds)
        if (pendingFirstConnect) swapSaveFiles()
        APDataStorage.init(pendingSlot)
        APDataStorage.fetch()
        connectionResultCallback?.invoke(true, null)
        connectionResultCallback = null
        setGameState(ClientStatus.CLIENT_PLAYING)
    }

    @ArchipelagoEventListener
    fun onLocationInfo(event: LocationInfoEvent) {
        for (item in event.locations) {
            scoutedLocations[item.locationID] = item
        }
        FortyFiveLogger.debug(logTag, "scouted ${event.locations.size} locations")
    }

    @ArchipelagoEventListener
    fun onItemReceived(event: ReceiveItemEvent) {
        waitItemsScope.launch {
            fullyConnected.await()
            itemMutex.withLock {
                FortyFiveLogger.debug(logTag, "onItemReceived fired: isArchipelago=$isArchipelago index=${event.index} item=${event.itemName}")
                if (!isArchipelago) return@launch
                val index = event.index.toInt()
                if (index in PermaSaveState.receivedItemIndices) {
                    FortyFiveLogger.debug(logTag, "skipping already-processed item at index $index: ${event.itemName}")
                    return@launch
                }
                PermaSaveState.addReceivedIndex(index)
                FortyFiveLogger.debug(logTag, "received item: ${event.itemName} from ${event.playerName} (index $index)")
                ItemsAndLocations.receiveItem(event.itemName, event.playerName, event.item.flags)
            }
        }
    }

    @ArchipelagoEventListener
    fun onDeathLinkReceived(event: DeathLinkEvent) {
        FortyFiveLogger.debug(logTag, "received death link from ${event.source}: ${event.cause}")
        applyDeathLink()
    }

    @ArchipelagoEventListener
    fun onPrintJSON(event: PrintJSONEvent) {
        if (event.type != APPrintJsonType.Goal) return
        val playerName = slotInfo[event.apPrint.slot]?.name ?: return
        if (playerName == myName) return
        Gdx.app.postRunnable {
            NotificationOverlay.show("\$yellow$$playerName\$yellow$ has finished their goal!")
        }
    }

    // -------------------------------------------------------------------------
    // Game actions
    // -------------------------------------------------------------------------

    fun sendGoalComplete() {
        setGameState(ClientStatus.CLIENT_GOAL)
        FortyFiveLogger.debug(logTag, "sent goal completion")
    }

    fun applyDeathLink() {
        Gdx.app.postRunnable {
            val game = FortyFive.currentGame
            if (game != null) {
                game.receiveDeathLink()
            } else {
                val rp = FortyFive.currentRenderPipeline
                if (rp != null) {
                    FortyFive.runGlobalTimeline(Timeline.timeline {
                        delayUntil { !CenteredDragSource.isDragging }
                        action { FortyFive.disableCurrentScreenInput() }
                        include(rp.getFadeToBlackTimeline(2000, stayBlack = true))
                        delay(500)
                        action { applyDeathLinkReset() }
                    })
                } else {
                    applyDeathLinkReset()
                }
            }
        }
    }

    private fun applyDeathLinkReset() {
        if (deathLinkMode == 1) {
            pendingLenientArea = PermaSaveState.visitedAreas.lastOrNull()
        }
        FortyFive.newRun(true)
    }

    // -------------------------------------------------------------------------
    // Connection overrides
    // -------------------------------------------------------------------------

    override fun onError(ex: Exception) {
        FortyFiveLogger.warn(logTag, "WebSocket error: ${ex::class.simpleName}: ${ex.message}\n${ex.stackTraceToString()}")
        val msg = ex.message ?: ""
        val message = if (ex is java.net.ConnectException || msg.contains("refused", ignoreCase = true) || msg.contains("unknown host", ignoreCase = true)) {
            "AP server does not exist"
        } else {
            msg.ifBlank { "Connection error" }
        }
        Gdx.app.postRunnable {
            connectionResultCallback?.invoke(false, message)
            connectionResultCallback = null
        }
    }

    override fun onClose(reason: String, attemptingReconnect: Int) {
        FortyFiveLogger.warn(logTag, "connection closed: $reason (reconnect attempt: $attemptingReconnect)")
        Gdx.app.postRunnable {
            connectionResultCallback?.invoke(false, reason)
            connectionResultCallback = null
        }
    }
}
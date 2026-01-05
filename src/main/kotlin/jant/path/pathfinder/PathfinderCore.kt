package jant.path.pathfinder

import jant.path.api.PathfinderAPI
import jant.path.api.PathfinderAPIInternal
import jant.path.api.PathfinderSession
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.tag.BlockTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.cobalt.api.event.impl.render.WorldRenderContext
import org.cobalt.api.pathfinder.IPathExec
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.util.PlayerUtils.position
import java.awt.Color
import java.io.File
import java.util.*
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.sqrt
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray

object PathfinderCore : IPathExec {

    private const val STUCK_THRESHOLD = 100
    private const val STUCK_AREA_RADIUS = 2
    private const val MAX_ITERATIONS = 15000  // Reduced for faster pathfinding
    private const val RECALC_COOLDOWN = 3000L  // 3 seconds cooldown between recalculations
    private const val MAX_FALL_DISTANCE = 20  // Reduced to explore fewer neighbors
    
    private const val JUMP_COST_MULTIPLIER = 1.5
    private const val DIAGONAL_TURN_COST = 0.3
    private const val CARDINAL_TURN_COST = 0.2
    private const val VERTICAL_CLIMB_COST = 2.5
    private const val VERTICAL_DROP_COST = 0.8
    
    private const val HEURISTIC_BIAS = 2  // Higher bias = faster but less optimal paths

    private const val DEBUG = true
    
    // === STATE VARIABLES ===
    
    private val mc = MinecraftClient.getInstance()
    
    /** Thread pool for async pathfinding calculations */
    private val pathfindingExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Pathfinding-Thread").apply { isDaemon = true }
    }
    
    /** Current async pathfinding task */
    private var pathCalculationFuture: CompletableFuture<List<BlockPos>?>? = null
    
    /** Flag to indicate if a path calculation is in progress */
    @Volatile
    private var isCalculating = false
    
    /** Flag to signal cancellation to background thread */
    @Volatile
    private var isCancelled = false
    
    /** Cached world snapshot for thread-safe access */
    private var worldSnapshot: WorldSnapshot? = null
    
    /** Currently calculated path as a list of block positions */
    private var currentPath: List<BlockPos>? = null
    
    /** Final destination block position */
    private var targetPos: BlockPos? = null
    
    /** Current waypoint index in the path */
    private var currentIndex = 0
    
    /** Timestamp of last path recalculation */
    private var lastRecalcTime = 0L
    
    /** Last recorded player position for stuck detection */
    private var lastPlayerPos: BlockPos? = null
    
    /** Initial position when player entered current area for stuck detection */
    private var stuckAreaOrigin: BlockPos? = null
    
    /** Counter for how many ticks the player has been in the same area */
    private var stuckTicks = 0
    
    /** Whether sneak should remain active (e.g., at edges) */
    private var shouldKeepSneak = false
    
    /** Current API session being executed (if using API) */
    private var currentApiSession: PathfinderSession? = null
    
    // Initialize API integration
    init {
        PathfinderAPIInternal.startPathfinding = { session ->
            currentApiSession = session
            setTarget(session.target)
        }
        PathfinderAPIInternal.stopPathfinding = {
            currentApiSession = null
            clearPath()
        }
    }

    /**
     * Checks if an ItemStack is an Aspect of the Void or Aspect of the End.
     * Searches for the item ID in NBT data.
     */
    private fun isAspectItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        
        try {
            // In Minecraft 1.21, custom NBT is stored in the DataComponentTypes.CUSTOM_DATA component
            val customDataType = net.minecraft.component.DataComponentTypes.CUSTOM_DATA
            val customData = stack.get(customDataType) ?: return false
            
            // Get the NBT compound from custom data using copyNbt()
            val nbt: NbtCompound = customData.copyNbt()
            
            // Check for ExtraAttributes.id field (Hypixel SkyBlock format)
            val extraAttrs = nbt.getCompound("ExtraAttributes")
            if (extraAttrs.isPresent) {
                val attrs = extraAttrs.get()
                val itemIdOpt = attrs.getString("id")
                if (itemIdOpt.isPresent) {
                    val itemId = itemIdOpt.get()
                    return itemId == "ASPECT_OF_THE_VOID" || itemId == "ASPECT_OF_THE_END"
                }
            }
            
            // Fallback: check direct id field
            val itemIdOpt = nbt.getString("id")
            if (itemIdOpt.isPresent) {
                val itemId = itemIdOpt.get()
                return itemId == "ASPECT_OF_THE_VOID" || itemId == "ASPECT_OF_THE_END"
            }
        } catch (e: Exception) {
            if (DEBUG) println("[Pathfinder] Error checking item NBT: ${e.message}")
        }
        
        return false
    }
    
    /**
     * Finds the hotbar slot containing an Aspect item.
     * Returns the slot index (0-8) or -1 if not found.
     */
    private fun findAspectItemSlot(player: ClientPlayerEntity): Int {
        val inventory = player.inventory
        
        // Check hotbar slots (0-8)
        for (slot in 0..8) {
            val stack = inventory.getStack(slot)
            if (isAspectItem(stack)) {
                return slot
            }
        }
        
        return -1
    }
    
    /**
     * Holds the Aspect item if available in hotbar.
     */
    private fun holdAspectItem(player: ClientPlayerEntity) {
        val aspectSlot = findAspectItemSlot(player)
        if (aspectSlot != -1 && player.inventory.selectedSlot != aspectSlot) {
            player.inventory.selectedSlot = aspectSlot
            if (DEBUG) println("[Pathfinder] Switched to Aspect item in slot $aspectSlot")
        }
    }

    /**
     * Gets the Jump Boost potion effect level (0 if no effect).
     * Jump Boost increases jump height by 0.5 blocks per level.
     */
    private fun getJumpBoostLevel(player: ClientPlayerEntity): Int {
        return player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.JUMP_BOOST)?.let { 
            it.amplifier + 1 
        } ?: 0
    }

    /**
     * Gets the Speed potion effect level (0 if no effect).
     * Speed increases movement speed and affects jump distance.
     */
    private fun getSpeedLevel(player: ClientPlayerEntity): Int {
        return player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED)?.let { 
            it.amplifier + 1 
        } ?: 0
    }

    /**
     * Calculates maximum vertical jump height based on Jump Boost effect.
     * @return Number of blocks the player can jump (1 + jump boost level, capped at 3)
     */
    private fun getMaxJumpHeight(player: ClientPlayerEntity): Int {
        val jumpBoost = getJumpBoostLevel(player)

        return (1 + jumpBoost).coerceAtMost(3)
    }

    /**
     * Calculates maximum horizontal jump distance based on Speed and Jump Boost.
     * @return Number of blocks the player can jump horizontally
     */
    private fun getMaxJumpDistance(player: ClientPlayerEntity): Int {
        val speedLevel = getSpeedLevel(player)
        val jumpBoost = getJumpBoostLevel(player)

        return 3 + speedLevel + (jumpBoost / 2)
    }

    /**
     * Sets a new pathfinding target and resets state.
     * Call this to start pathfinding to a new location.
     * 
     * @param target The block position to pathfind to
     */
    fun setTarget(target: BlockPos) {
        targetPos = target
        currentPath = null
        currentIndex = 0
        shouldKeepSneak = false
        mc.options.sneakKey.setPressed(false)
        
        // Start automatic chunk caching when pathfinding begins
        val world = mc.world
        if (world != null) {
            WorldSnapshot.startChunkCaching(world)
        }
    }

    /**
     * Stops pathfinding and clears all state.
     * Call this to cancel pathfinding.
     */
    fun clearPath() {
        // Signal cancellation to background thread
        isCancelled = true
        
        // Cancel any ongoing calculation
        pathCalculationFuture?.cancel(true)
        pathCalculationFuture = null
        isCalculating = false
        worldSnapshot = null
        
        currentPath = null
        targetPos = null
        currentIndex = 0

        mc.options.sneakKey.setPressed(false)
        shouldKeepSneak = false
        currentApiSession = null
        
        // Release look lock
        MovementController.stopRotation()
        
        // Stop chunk caching when pathfinding ends
        WorldSnapshot.stopChunkCaching()
    }

    /**
     * Checks if the pathfinder is currently active.
     * @return true if pathfinding to a target, false otherwise
     */
    fun isPathfinding(): Boolean = targetPos != null

    /**
     * Gets the current calculated path.
     * @return List of waypoints, or null if no path exists
     */
    fun getCurrentPath(): List<BlockPos>? = currentPath
    
    /**
     * Debug: Get info about the world cache
     */
    fun getDebugInfo(): String = WorldSnapshot.getDebugInfo()
    
    /**
     * Debug: Force load all chunks from disk
     */
    fun debugLoadAllChunks(): Pair<Int, Int> {
        val world = mc.world ?: return Pair(0, 0)
        return WorldSnapshot.debugLoadAllChunks(world)
    }

    /**
     * Main pathfinding tick update - called every game tick.
     * 
     * This function:
     * 1. Detects if the player is stuck and recalculates if needed
     * 2. Calculates initial path if none exists
     * 3. Advances through waypoints as player reaches them
     * 4. Implements waypoint skipping for smoother movement
     * 5. Calls MovementController to execute movement
     * 
     * CUSTOMIZATION:
     * - Modify waypoint reach detection (horizontalDist and vertical checks)
     * - Adjust lookAheadCount for different skip-ahead behavior
     * - Change clearance checking logic for different movement styles
     * 
     * @param player The client player entity
     */
    override fun onTick(player: ClientPlayerEntity) {
        val target = targetPos ?: return
        
        // Hold Aspect item if available
        holdAspectItem(player)
        
        // Tick gradual chunk loading (1 chunk per tick)
        val world = mc.world
        if (world != null) {
            WorldSnapshot.tickChunkLoading(world)
        }
        
        val currentTime = System.currentTimeMillis()
        val playerPos = BlockPos.ofFloored(player.x, player.y, player.z)

        // Check if player is within 5x5 area (2 blocks radius) of origin
        val origin = stuckAreaOrigin
        if (origin == null) {
            stuckAreaOrigin = playerPos
            stuckTicks = 0
        } else {
            val isInArea = kotlin.math.abs(playerPos.x - origin.x) <= STUCK_AREA_RADIUS &&
                          kotlin.math.abs(playerPos.z - origin.z) <= STUCK_AREA_RADIUS
            
            if (isInArea) {
                stuckTicks++
            } else {
                stuckAreaOrigin = playerPos
                stuckTicks = 0
            }
        }
        
        lastPlayerPos = playerPos

        // Only trigger recalculation if stuck AND cooldown has passed AND we have a path already
        val shouldRecalculate = stuckTicks > STUCK_THRESHOLD && 
                                currentPath != null && 
                                (currentTime - lastRecalcTime) > RECALC_COOLDOWN

        // Check if async calculation completed
        pathCalculationFuture?.let { future ->
            if (future.isDone) {
                try {
                    val calculatedPath = future.get()
                    currentPath = calculatedPath
                    currentIndex = 0
                    lastRecalcTime = currentTime
                    stuckTicks = 0
                    stuckAreaOrigin = playerPos
                    isCalculating = false
                    worldSnapshot = null
                    
                    if (calculatedPath == null) {
                        if (DEBUG) println("[Pathfinder] No path found to target")
                        currentApiSession?.let { session ->
                            PathfinderAPI.notifyPathFailed(session, "No path found to target")
                        }
                        clearPath()
                        return
                    }
                    
                    // Notify API that path was calculated
                    currentApiSession?.let { session ->
                        PathfinderAPI.notifyPathCalculated(session, calculatedPath)
                    }
                } catch (e: Exception) {
                    if (DEBUG) println("[Pathfinder] Path calculation error: ${e.message}")
                    isCalculating = false
                    worldSnapshot = null
                    currentApiSession?.let { session ->
                        PathfinderAPI.notifyPathFailed(session, "Calculation error: ${e.message}")
                    }
                    clearPath()
                    return
                }
                pathCalculationFuture = null
            }
        }

        // Start new calculation if needed and not already calculating
        if ((currentPath == null || shouldRecalculate) && !isCalculating) {
            if (shouldRecalculate) {
                if (DEBUG) println("[Pathfinder] Stuck detected, recalculating path")
                lastRecalcTime = currentTime  // Update recalc time to prevent rapid recalcs
            }
            
            // Check if world is available
            if (world == null) {
                if (DEBUG) println("[Pathfinder] World is null, cannot pathfind")
                return
            }
            
            isCalculating = true
            isCancelled = false  // Reset cancellation flag for new calculation
            
            // Queue chunks for gradual loading
            WorldSnapshot.queueChunksForLoading(playerPos, target)
            
            if (DEBUG) println("[Pathfinder] Queued chunks for loading. Waiting for chunks to load...")
        }
        
        // Start pathfinding once chunks are loaded
        if (isCalculating && WorldSnapshot.isReadyForPathfinding && pathCalculationFuture == null) {
            val pathWorld = mc.world ?: return
            
            if (DEBUG) println("[Pathfinder] Chunks loaded! Starting path calculation from $playerPos to $target")
            
            val startPos = playerPos
            val endPos = target
            pathCalculationFuture = CompletableFuture.supplyAsync({
                try {
                    // Check for cancellation
                    if (isCancelled) {
                        if (DEBUG) println("[Pathfinder-Thread] Cancelled before pathfinding")
                        return@supplyAsync null
                    }
                    
                    // Use the shared cache that was loaded gradually
                    val snapshot = WorldSnapshot.createFromSharedCache(pathWorld)
                    worldSnapshot = snapshot
                    
                    // Calculate path
                    if (DEBUG) println("[Pathfinder-Thread] Calculating path...")
                    val result = findPathThreaded(startPos, endPos)
                    
                    // Check for cancellation after pathfinding
                    if (isCancelled) {
                        if (DEBUG) println("[Pathfinder-Thread] Cancelled after pathfinding")
                        return@supplyAsync null
                    }
                    
                    if (DEBUG) println("[Pathfinder-Thread] Path calculated: ${result?.size ?: 0} waypoints")
                    result
                } catch (e: Exception) {
                    if (DEBUG) {
                        println("[Pathfinder-Thread] Error: ${e.message}")
                        e.printStackTrace()
                    }
                    null
                }
            }, pathfindingExecutor)
            
            if (DEBUG) println("[Pathfinder] Async calculation submitted")
        }

        val path = currentPath ?: return
        if (currentIndex >= path.size) {
            MovementController.stopMovement(player)
            
            targetPos?.let { dest ->
                if (isAtEdge(player, dest)) {
                    shouldKeepSneak = true
                    mc.options.sneakKey.setPressed(true)
                }
            }
            
            // Notify API that destination was reached
            currentApiSession?.let { session ->
                PathfinderAPI.notifyReachedDestination(session)
            }

            clearPath()
            return
        }

        val currentNode = path[currentIndex]

        val targetNode = if (!player.isOnGround && currentIndex + 1 < path.size) {

            path[currentIndex + 1]
        } else {
            currentNode
        }

        val targetVec = Vec3d(
            targetNode.x + 0.5,
            player.y,  
            targetNode.z + 0.5
        )
        val playerVec = Vec3d(player.x, player.y, player.z)

        val horizontalDist = kotlin.math.sqrt(
            (currentNode.x + 0.5 - playerVec.x) * (currentNode.x + 0.5 - playerVec.x) +
            (currentNode.z + 0.5 - playerVec.z) * (currentNode.z + 0.5 - playerVec.z)
        )

        val verticalDistance = kotlin.math.abs(currentNode.y - player.y)

        val playerBottomY = player.y
        val blockBottomY = currentNode.y.toDouble()
        val blockTopY = currentNode.y + 1.0

        val isAtBlock = horizontalDist < 0.5 && (
            playerBottomY >= blockBottomY - 0.1 || 
            (playerBottomY >= blockBottomY - 3.0 && player.isOnGround)
        )

        if (isAtBlock) {
            currentIndex++
        }

        if (currentIndex < path.size) {
            val waypointY = currentNode.y
            val passedVertically = playerBottomY > waypointY + 1.5 && horizontalDist < 2.0
            if (passedVertically) currentIndex++
        }

        // Ensure world is available for movement calculations
        val movementWorld = world ?: return
        val maxJumpDist = getMaxJumpDistance(player).toDouble()
        val maxJumpHeight = getMaxJumpHeight(player)
        val lookAheadCount = minOf(15, (maxJumpDist * 1.5).toInt() + 5)

        var bestSkipIndex = currentIndex
        for (i in minOf(currentIndex + lookAheadCount, path.size - 1) downTo (currentIndex + 1)) {
            val futureNode = path[i]
            val distToFuture = kotlin.math.sqrt(
                (futureNode.x + 0.5 - playerVec.x) * (futureNode.x + 0.5 - playerVec.x) +
                (futureNode.z + 0.5 - playerVec.z) * (futureNode.z + 0.5 - playerVec.z)
            )

            val verticalDiff = futureNode.y - player.y.toInt()

            val hasClearance = if (verticalDiff > 0) {
                verticalDiff <= maxJumpHeight
            } else {
                val maxY = player.y.toInt()
                val minY = futureNode.y
                val checkPos = BlockPos.ofFloored(futureNode.x.toDouble(), maxY.toDouble(), futureNode.z.toDouble())
                
                (minY..maxY + 2).all { y ->
                    val blockAbove = movementWorld.getBlockState(checkPos.withY(y))
                    isPassableBlock(blockAbove) || !blockAbove.isSolidBlock(movementWorld, checkPos.withY(y))
                }
            }

            val hasLineOfSight = hasLineOfSight(player.blockPos, futureNode)

            var has2BlockClearance = true
            val dx = futureNode.x - player.blockPos.x
            val dz = futureNode.z - player.blockPos.z
            val steps = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))

            if (steps > 0) {
                for (step in 0..steps) {
                    val progress = step.toDouble() / steps.toDouble()
                    val checkX = player.blockPos.x + (dx * progress).toInt()
                    val checkZ = player.blockPos.z + (dz * progress).toInt()
                    val checkY = player.blockPos.y

                    val checkPos = BlockPos(checkX, checkY, checkZ)
                    val blockAtHead = movementWorld.getBlockState(checkPos.up())
                    val blockAboveHead = movementWorld.getBlockState(checkPos.up(2))

                    if (!isPassableBlock(blockAboveHead) && isPassableBlock(blockAtHead)) {
                        has2BlockClearance = false
                        break
                    }
                }
            }

            val hasPhysicalPath = if (verticalDiff > 0) {

                isJumpPathClear(player.blockPos, futureNode)
            } else {

                hasLineOfSight
            }

            val canReachHeight = verticalDiff <= 0 || verticalDiff <= maxJumpHeight
            val inRange = distToFuture < maxJumpDist * 2.0

            if (inRange && canReachHeight && hasClearance && hasLineOfSight && hasPhysicalPath && has2BlockClearance) {
                bestSkipIndex = i
                break
            }
        }

        if (bestSkipIndex > currentIndex) {
            currentIndex = bestSkipIndex
            
            // Notify API of progress
            currentApiSession?.let { session ->
                session.callback.onProgress(session, currentIndex, path.size)
            }
        }

        val shouldJump = MovementController.shouldJump(player, currentNode)

        MovementController.setSprinting(!shouldJump)

        val maxFallDist = 3.0 + (getJumpBoostLevel(player) * 0.5)
        if (player.isOnGround || shouldJump || verticalDistance < maxFallDist) {
            MovementController.moveTowards(player, targetVec)
        }

        if (shouldJump) {
            MovementController.jump(player)
        } else {
            MovementController.resetJump()
        }
    }

    /**
     * Implements A* pathfinding algorithm to find optimal path from start to end.
     * 
     * A* ALGORITHM OVERVIEW:
     * 1. Maintains openSet (positions to explore) and closedSet (already explored)
     * 2. Always explores the node with lowest fCost (gCost + hCost)
     * 3. For each node, checks all neighbors and calculates their costs
     * 4. Updates neighbor costs if a better path is found
     * 5. Reconstructs path by following parent pointers when goal is reached
     * 
     * COST CALCULATION:
     * - gCost: actual distance traveled from start
     * - hCost: estimated distance to goal (heuristic)
     * - fCost: gCost + hCost (total estimated cost)
     * 
     * CUSTOMIZATION:
     * - Modify movement cost calculation to change path preferences
     * - Adjust turn costs to make paths straighter or more flexible
     * - Change wall proximity penalties to avoid tight spaces
     * 
     * @param start Starting block position
     * @param end Goal block position
     * @return List of waypoints from start to end, or null if no path exists
     */
    private fun findPath(start: BlockPos, end: BlockPos): List<BlockPos>? {
        val world = mc.world ?: return null
        val player = mc.player ?: return null
        
        val openSet = PriorityQueue<PathNode>(compareBy { it.fCost })
        val closedSet = mutableSetOf<BlockPos>()
        val allNodes = mutableMapOf<BlockPos, PathNode>()

        val startNode = PathNode(start, gCost = 0.0, hCost = heuristic(start, end))
        allNodes[start] = startNode
        openSet.add(startNode)

        var iterations = 0

        while (openSet.isNotEmpty() && iterations < MAX_ITERATIONS) {
            iterations++

            val current = openSet.poll()
            closedSet.add(current.pos)

            if (current.pos == end) {
                return reconstructPath(current)
            }

            for (neighbor in getNeighbors(current.pos)) {
                if (neighbor in closedSet) continue
                if (!isWalkable(neighbor)) continue
                if (!canMoveDiagonally(current.pos, neighbor)) continue

                var movementCost = distance(current.pos, neighbor)

                val heightChange = neighbor.y - current.pos.y
                if (heightChange > 0) {
                    movementCost += heightChange * JUMP_COST_MULTIPLIER
                }

                val wallProximityPenalty = checkWallProximity(neighbor)
                movementCost += wallProximityPenalty

                current.parent?.let { parent ->
                    val prevDir = current.pos.subtract(parent.pos)
                    val nextDir = neighbor.subtract(current.pos)

                    if (prevDir.x != nextDir.x || prevDir.z != nextDir.z) {
                        val prevIsDiagonal = prevDir.x != 0 && prevDir.z != 0
                        val nextIsDiagonal = nextDir.x != 0 && nextDir.z != 0

                        movementCost += if (prevIsDiagonal || nextIsDiagonal) {
                            DIAGONAL_TURN_COST
                        } else {
                            CARDINAL_TURN_COST
                        }
                    }
                }

                val tentativeG = current.gCost + movementCost
                val neighborNode = allNodes.getOrPut(neighbor) {
                    PathNode(neighbor, hCost = heuristic(neighbor, end))
                }

                if (tentativeG < neighborNode.gCost) {
                    neighborNode.gCost = tentativeG
                    neighborNode.parent = current

                    if (neighborNode !in openSet) {
                        openSet.add(neighborNode)
                    }
                }
            }
        }

        return null 
    }

    /**
     * Gets all valid neighbor positions from a given position.
     * Thread-safe version using cached world snapshot.
     * 
     * This function generates potential movement options including:
     * - Cardinal movements (N, S, E, W)
     * - Diagonal movements (NE, NW, SE, SW)
     * - Jump movements (1-3 blocks horizontal, 1-2 blocks up)
     * - Fall movements (down to MAX_FALL_DISTANCE blocks)
     * 
     * CUSTOMIZATION:
     * - Add more movement patterns in the loops
     * - Modify jump distance calculations
     * - Add special movement modes (e.g., parkour jumps)
     * - Filter out certain neighbor types
     * 
     * @param pos The current position
     * @return List of all valid neighbor positions
     */
    private fun getNeighbors(pos: BlockPos): List<BlockPos> {
        val world = mc.world ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val neighbors = mutableListOf<BlockPos>()
        
        val maxJumpHeight = getMaxJumpHeight(player)
        val maxJumpDistance = getMaxJumpDistance(player)

        fun hasHeadroom(checkPos: BlockPos): Boolean {
            val blockAhead = world.getBlockState(checkPos.up())
            return isPassableBlock(blockAhead) || !blockAhead.isSolidBlock(world, checkPos.up())
        }

        listOf(
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1)
        ).filter { hasHeadroom(it) }.forEach { neighbors.add(it) }

        listOf(
            pos.add(1, 0, 1), pos.add(1, 0, -1),
            pos.add(-1, 0, 1), pos.add(-1, 0, -1)
        ).filter { hasHeadroom(it) }.forEach { neighbors.add(it) }

        val cardinalDirs = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
        )

        for ((dx, dz) in cardinalDirs) {
            for (distance in 1..maxJumpDistance) {
                for (height in 1..maxJumpHeight) {
                    val jumpPos = pos.add(dx * distance, height, dz * distance)
                    
                    if (isWalkable(jumpPos) && isJumpPathClear(pos, jumpPos)) {
                        neighbors.add(jumpPos)
                    }
                }
            }
        }

        val horizontalOffsets = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1),

            Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1)
        )

        for ((dx, dz) in horizontalOffsets) {
            for (dropHeight in 1..MAX_FALL_DISTANCE) {
                val dropPos = pos.add(dx, -dropHeight, dz)
                
                if (isWalkable(dropPos)) {
                    neighbors.add(dropPos)
                    break
                }
            }
        }

        return neighbors
    }

    /**
     * Calculates penalty cost for positions near walls.
     * This encourages the pathfinder to prefer open spaces over tight corridors.
     * 
     * PENALTY LEVELS:
     * - 6+ walls: 2.0 (very enclosed)
     * - 4-5 walls: 1.0 (moderately enclosed)
     * - 2-3 walls: 0.3 (slightly enclosed)
     * - 0-1 walls: 0.0 (open space)
     * 
     * Also adds extra penalty for "squeeze" situations where blocks are
     * on opposite sides (hole/gap walking).
     * 
     * @param pos Position to check
     * @return Cost penalty (0.0 to 5.0)
     */
    private fun checkWallProximity(pos: BlockPos): Double {
        val world = mc.world ?: return 0.0

        var wallCount = 0
        val checkPositions = listOf(
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1),
            pos.add(1, 1, 0), pos.add(-1, 1, 0),
            pos.add(0, 1, 1), pos.add(0, 1, -1)
        )

        var avoidBlockPenalty = 0.0
        
        for (checkPos in checkPositions) {
            val blockState = world.getBlockState(checkPos)
            if (blockState.isSolidBlock(world, checkPos) && !isPassableBlock(blockState)) {
                wallCount++
            }
            // Massive penalty for glass panes, glass blocks, fences etc - completely avoid them
            if (isAvoidBlock(blockState)) {
                avoidBlockPenalty += 50.0  // Massive penalty to force avoidance
            }
        }
        
        // Check for "squeeze" situations - blocks on opposite sides
        var squeezePenalty = 0.0
        
        // Check X-axis squeeze (left and right walls)
        val leftWall = world.getBlockState(pos.add(-1, 0, 0))
        val rightWall = world.getBlockState(pos.add(1, 0, 0))
        if (leftWall.isSolidBlock(world, pos.add(-1, 0, 0)) && 
            rightWall.isSolidBlock(world, pos.add(1, 0, 0))) {
            squeezePenalty += 3.0  // Heavy penalty for X-axis squeeze
        }
        
        // Check Z-axis squeeze (front and back walls)
        val frontWall = world.getBlockState(pos.add(0, 0, 1))
        val backWall = world.getBlockState(pos.add(0, 0, -1))
        if (frontWall.isSolidBlock(world, pos.add(0, 0, 1)) && 
            backWall.isSolidBlock(world, pos.add(0, 0, -1))) {
            squeezePenalty += 3.0  // Heavy penalty for Z-axis squeeze
        }

        val basePenalty = when {
            wallCount >= 6 -> 2.0 
            wallCount >= 4 -> 1.0 
            wallCount >= 2 -> 0.3 
            else -> 0.0 
        }
        
        return basePenalty + squeezePenalty + avoidBlockPenalty
    }

    /**
     * Determines if a block position is walkable (safe to pathfind through).
     * 
     * Checks performed:
     * 1. Has solid ground below (or slab/stair/snow)
     * 2. Space at feet is passable (or snow)
     * 3. Space at head is passable
     * 4. Not dangerous (lava, fire, cactus, magma)
     * 5. Special handling for snow layers and tall plants
     * 
     * CUSTOMIZATION:
     * - Add/remove dangerous blocks
     * - Change snow depth threshold (currently 2 layers)
     * - Modify ground detection for custom blocks
     * - Add special walkability rules for modded blocks
     * 
     * @param pos Position to check
     * @return true if walkable, false otherwise
     */
    private fun isWalkable(pos: BlockPos): Boolean {
        val world = mc.world ?: return false

        val blockState = world.getBlockState(pos)
        val below = world.getBlockState(pos.down())
        val above = world.getBlockState(pos.up())

        val blockBelowName = below.block.toString().lowercase()
        val blockAtFeetName = blockState.block.toString().lowercase()
        val isSlabOrStairBelow = blockBelowName.contains("slab") || 
                                 blockBelowName.contains("stair")
        val isSnowBelow = blockBelowName.contains("snow")
        val isSnowAtFeet = blockAtFeetName.contains("snow")
        
        // If snow is below, check that there's actual solid ground under the snow
        val hasGroundUnderSnow = if (isSnowBelow) {
            // Look for solid ground under the snow layers
            var checkPos = pos.down(2)
            var foundSolid = false
            for (i in 1..8) {
                val checkState = world.getBlockState(checkPos)
                val checkName = checkState.block.toString().lowercase()
                if (checkName.contains("snow")) {
                    checkPos = checkPos.down()
                } else if (checkState.isSolidBlock(world, checkPos)) {
                    foundSolid = true
                    break
                } else {
                    // Found air or non-solid under snow - not valid ground
                    break
                }
            }
            foundSolid
        } else {
            true  // No snow below, will check normal ground later
        }
        
        if (isSnowBelow && !hasGroundUnderSnow) {
            return false  // Snow floating on air - not walkable
        }

        if (isSnowBelow && !isPassableBlock(blockState) && !isSnowAtFeet) {
            return false
        }

        val blockAtFeetIsTall = blockState.block == Blocks.TALL_GRASS ||
                                blockState.block == Blocks.LARGE_FERN ||
                                blockState.block == Blocks.SUNFLOWER ||
                                blockState.block == Blocks.LILAC ||
                                blockState.block == Blocks.ROSE_BUSH ||
                                blockState.block == Blocks.PEONY ||
                                blockState.block == Blocks.TALL_SEAGRASS

        if (isSnowAtFeet && blockAtFeetIsTall) {
            return false
        }

        if (isSnowAtFeet) {
            val blockAboveSnow = world.getBlockState(pos.up())

            if (!isPassableBlock(blockAboveSnow)) {
                return false
            }

            val blockTwoAbove = world.getBlockState(pos.up(2))
            if (!isPassableBlock(blockTwoAbove)) {
                return false
            }

            var snowDepth = 1
            var checkBelow = pos.down()
            for (i in 1..8) {
                val belowState = world.getBlockState(checkBelow)
                val belowName = belowState.block.toString().lowercase()
                if (belowName.contains("snow")) {
                    snowDepth++
                    checkBelow = checkBelow.down()
                } else {
                    break
                }
            }

            // 8 snow layers = full block, treat as solid ground
            if (snowDepth >= 8) {
                return true
            }

            if (snowDepth > 2) {
                return false
            }
        }

        val hasSolidGround = below.isSolidBlock(world, pos.down()) || isSlabOrStairBelow || isSnowBelow || isSnowAtFeet

        val canWalkThroughFeet = isPassableBlock(blockState) || isSnowAtFeet
        val canWalkThroughHead = isPassableBlock(above)

        val isDangerous = blockState.block == Blocks.LAVA || 
                         blockState.block == Blocks.FIRE ||
                         below.block == Blocks.LAVA ||
                         blockState.block == Blocks.MAGMA_BLOCK ||
                         blockState.block == Blocks.CACTUS ||
                         blockState.block == Blocks.SWEET_BERRY_BUSH

        return hasSolidGround && canWalkThroughFeet && canWalkThroughHead && !isDangerous
    }

    /**
     * Checks if player is at an edge where falling is possible.
     * Used to determine if sneaking should be enabled.
     * 
     * @param player The player entity
     * @param destination The target position
     * @return true if at an edge, false otherwise
     */
    private fun isAtEdge(player: ClientPlayerEntity, destination: BlockPos): Boolean {
        val world = mc.world ?: return false
        val playerPos = BlockPos.ofFloored(player.x, player.y, player.z)

        val distToDest = kotlin.math.sqrt(
            (destination.x - playerPos.x) * (destination.x - playerPos.x).toDouble() +
            (destination.z - playerPos.z) * (destination.z - playerPos.z).toDouble()
        )

        if (distToDest > 1.5) return false

        val directions = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
        )

        for ((dx, dz) in directions) {
            val checkPos = playerPos.add(dx, 0, dz)
            val blockAtSameLevel = world.getBlockState(checkPos)

            if (blockAtSameLevel.isAir) {
                return true
            }
        }

        return false
    }

    /**
     * Checks if diagonal movement is possible without getting stuck.
     * Diagonal moves are blocked if both adjacent cardinal positions are blocked.
     * 
     * Example: To move NE, either N or E must be passable.
     * 
     * @param from Starting position
     * @param to Destination position
     * @return true if diagonal movement is safe, false if blocked
     */
    private fun canMoveDiagonally(from: BlockPos, to: BlockPos): Boolean {
        val world = mc.world ?: return true

        val dx = to.x - from.x
        val dz = to.z - from.z

        if (dx == 0 || dz == 0) return true

        val side1 = from.add(dx, 0, 0)
        val side2 = from.add(0, 0, dz)

        val side1Blocked = !isPassableBlock(world.getBlockState(side1)) || 
                          !isPassableBlock(world.getBlockState(side1.up()))
        val side2Blocked = !isPassableBlock(world.getBlockState(side2)) || 
                          !isPassableBlock(world.getBlockState(side2.up()))

        return !(side1Blocked && side2Blocked)
    }

    /**
     * Verifies that a jump path has no obstructions.
     * Samples multiple points along the jump arc to ensure the player won't hit blocks.
     * 
     * @param from Jump start position
     * @param to Jump landing position
     * @return true if jump path is clear, false if obstructed
     */
    private fun isJumpPathClear(from: BlockPos, to: BlockPos): Boolean {
        val world = mc.world ?: return false

        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z

        if (dy <= 0) return true

        val horizontalDist = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
        val samples = kotlin.math.max(horizontalDist.toInt(), dy) * 2 

        for (i in 1 until samples) {
            val t = i.toDouble() / samples.toDouble()

            val x = from.x + dx * t
            val y = from.y + dy * t
            val z = from.z + dz * t

            val checkPos = BlockPos.ofFloored(x, y, z)
            val headPos = checkPos.up() 

            val blockState = world.getBlockState(checkPos)
            val headState = world.getBlockState(headPos)

            if ((blockState.isSolidBlock(world, checkPos) && !isPassableBlock(blockState)) ||
                (headState.isSolidBlock(world, headPos) && !isPassableBlock(headState))) {
                return false
            }
        }

        return true
    }

    /**
     * Checks if a block should be heavily avoided during pathfinding.
     * These include glass panes, fences, iron bars, walls, and glass blocks.
     * 
     * @param blockState The block state to check
     * @return true if the block should be avoided
     */
    private fun isAvoidBlock(blockState: net.minecraft.block.BlockState): Boolean {
        if (blockState.isAir) return false
        
        val blockName = blockState.block.toString().lowercase()
        
        return blockName.contains("glass_pane") ||
               blockName.contains("glass") ||  // All glass blocks
               blockName.contains("iron_bars") ||
               blockName.contains("fence") ||
               blockName.contains("wall") && !blockName.contains("wallsign") ||
               blockName.contains("chain")
    }
    
    /**
     * Determines if a block can be walked through (non-solid).
     * 
     * Passable blocks include:
     * - Air
     * - Flowers, saplings, leaves, crops (from block tags)
     * - Snow, grass, torches, wires, rails (by name)
     * - Vines, kelp, bamboo, cobwebs, etc. (specific blocks)
     * 
     * CUSTOMIZATION:
     * - Add modded plant blocks
     * - Add custom decorative blocks
     * - Remove blocks you want to treat as solid
     * 
     * @param blockState The block state to check
     * @return true if passable, false if solid
     */
    private fun isPassableBlock(blockState: net.minecraft.block.BlockState): Boolean {
        if (blockState.isAir) return true
        
        val block = blockState.block
        val blockName = block.toString().lowercase()
        
        // Glass panes are NOT passable - treat as solid
        if (blockName.contains("glass_pane")) return false
        if (blockName.contains("iron_bars")) return false
        
        if (blockState.isIn(BlockTags.FLOWERS)) return true
        if (blockState.isIn(BlockTags.SAPLINGS)) return true
        if (blockState.isIn(BlockTags.LEAVES)) return true
        if (blockState.isIn(BlockTags.CROPS)) return true
        
        
        if (blockName.contains("snow")) return true
        if (blockName.contains("grass")) return true
        if (blockName.contains("torch")) return true
        if (blockName.contains("wire")) return true
        if (blockName.contains("rail")) return true
        
        
        return when (block) {
            Blocks.VINE, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT, Blocks.SUGAR_CANE,
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.COBWEB,
            Blocks.DEAD_BUSH, Blocks.NETHER_WART,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA -> true
            else -> false
        }
    }

    /**
     * Calculates the heuristic cost (estimated distance) between two positions.
     * Uses Euclidean distance with a bias multiplier.
     * 
     * HEURISTIC_BIAS:
     * - = 1.0: Perfect admissibility (optimal paths, slower)
     * - > 1.0: Greedy search (faster, potentially suboptimal)
     * - Current value (1.2): Good balance
     * 
     * @param from Start position
     * @param to Goal position
     * @return Estimated cost
     */
    private fun heuristic(from: BlockPos, to: BlockPos): Double {
        val dx = (from.x - to.x).toDouble()
        val dy = (from.y - to.y).toDouble()
        val dz = (from.z - to.z).toDouble()
        val euclidean = sqrt(dx * dx + dy * dy + dz * dz)
        
        
        return euclidean * HEURISTIC_BIAS
    }

    /**
     * Calculates actual movement cost between two positions.
     * Applies penalties for vertical movement (climbing costs more than falling).
     * 
     * @param from Start position
     * @param to Destination position
     * @return Actual movement cost
     */
    private fun distance(from: BlockPos, to: BlockPos): Double {
        val dx = (from.x - to.x).toDouble()
        val dy = (from.y - to.y).toDouble()
        val dz = (from.z - to.z).toDouble()

        val verticalPenalty = if (dy > 0) {
            kotlin.math.abs(dy) * VERTICAL_CLIMB_COST
        } else {
            kotlin.math.abs(dy) * VERTICAL_DROP_COST
        }

        return sqrt(dx * dx + dy * dy + dz * dz) + verticalPenalty
    }

    /**
     * Reconstructs the final path by following parent pointers from end to start.
     * Then applies path smoothing to remove unnecessary waypoints.
     * 
     * @param endNode The goal node reached by A*
     * @return List of waypoints from start to end
     */
    private fun reconstructPath(endNode: PathNode): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var current: PathNode? = endNode

        while (current != null) {
            path.add(0, current.pos)
            current = current.parent
        }

        return smoothPath(path)
    }

    /**
     * Removes unnecessary waypoints from the path using line-of-sight optimization.
     * Skips intermediate waypoints on the same Y level if line of sight exists.
     * 
     * Example: Path A -> B -> C -> D becomes A -> D if direct line exists.
     * 
     * @param path The raw path from A*
     * @return Smoothed path with fewer waypoints
     */
    private fun smoothPath(path: List<BlockPos>): List<BlockPos> {
        if (path.size <= 2) return path

        val smoothed = mutableListOf(path.first())

        var i = 0
        while (i < path.size - 1) {
            val current = path[i]
            var furthest = i + 1
            
            
            for (j in i + 1 until path.size) {
                if (kotlin.math.abs(path[j].y - current.y) > 0 || !hasLineOfSight(current, path[j])) break
                furthest = j
            }

            smoothed.add(path[furthest])
            i = furthest
        }

        if (smoothed.last() != path.last()) smoothed.add(path.last())
        return smoothed
    }

    /**
     * Checks if there's a clear horizontal line of sight between two positions.
     * Samples multiple points along the line to ensure both feet and head space is clear.
     * 
     * Used for:
     * - Path smoothing
     * - Waypoint skipping
     * 
     * @param from Start position
     * @param to End position
     * @return true if clear line of sight, false if obstructed
     */
    private fun hasLineOfSight(from: BlockPos, to: BlockPos): Boolean {
        val world = mc.world ?: return false

        val dx = to.x - from.x
        val dz = to.z - from.z
        val horizontalDistance = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())

        if (horizontalDistance < 1.0) return true

        val steps = (horizontalDistance * 2).toInt()
        val checkedPositions = mutableSetOf<BlockPos>()

        for (step in 0..steps) {
            val progress = step.toDouble() / steps
            val checkX = from.x + (dx * progress)
            val checkZ = from.z + (dz * progress)

            val positions = listOf(
                BlockPos(kotlin.math.floor(checkX).toInt(), from.y, kotlin.math.floor(checkZ).toInt()),
                BlockPos(kotlin.math.ceil(checkX).toInt(), from.y, kotlin.math.floor(checkZ).toInt()),
                BlockPos(kotlin.math.floor(checkX).toInt(), from.y, kotlin.math.ceil(checkZ).toInt()),
                BlockPos(kotlin.math.ceil(checkX).toInt(), from.y, kotlin.math.ceil(checkZ).toInt())
            )

            for (checkPos in positions) {
                if (checkPos in checkedPositions) continue
                checkedPositions.add(checkPos)

                val blockAtFeet = world.getBlockState(checkPos)
                val blockAtHead = world.getBlockState(checkPos.up())

                if (!isPassableBlock(blockAtFeet) || !isPassableBlock(blockAtHead)) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Checks if the path changes direction at a given waypoint.
     * Used to identify turning points in the path.
     * 
     * @param path The path to check
     * @param index The waypoint index to check
     * @return true if direction changes, false if straight
     */
    private fun hasDirectionChange(path: List<BlockPos>, index: Int): Boolean {
        if (index <= 0 || index >= path.size - 1) return false

        val prev = path[index - 1]
        val current = path[index]
        val next = path[index + 1]

        val dx1 = current.x - prev.x
        val dz1 = current.z - prev.z
        val dx2 = next.x - current.x
        val dz2 = next.z - current.z

        return dx1 != dx2 || dz1 != dz2
    }

    /**
     * Renders path visualization in the world.
     * Called every frame to draw the path and update player rotation.
     * 
     * Visualization:
     * - Green box: Current waypoint
     * - Gray boxes: Passed waypoints
     * - Blue boxes: Future waypoints
     * - Blue lines: Path connections
     * - Red box: Final destination
     * 
     * CUSTOMIZATION:
     * - Change colors by modifying Color() values
     * - Adjust box sizes (currently 0.1 to 0.9)
     * - Modify line thickness (currently 3f)
     * - Add additional debug visualization
     * 
     * @param ctx World render context
     * @param player The client player entity
     */
    override fun onWorldRenderLast(ctx: WorldRenderContext, player: ClientPlayerEntity) {
        val path = currentPath ?: return

        if (currentIndex < path.size) {
            val playerVec = Vec3d(player.x, player.y, player.z)

            val targetNode = if (!player.isOnGround) {
                val maxLookAheadDist = getMaxJumpDistance(player).toDouble() * 1.5 
                val maxLookAheadWaypoints = minOf(12, (maxLookAheadDist / 2).toInt() + 4)

                var bestNode = path[currentIndex]
                for (i in (currentIndex + 1) until minOf(currentIndex + maxLookAheadWaypoints, path.size)) {
                    val futureNode = path[i]
                    val distToFuture = kotlin.math.sqrt(
                        (futureNode.x + 0.5 - playerVec.x) * (futureNode.x + 0.5 - playerVec.x) +
                        (futureNode.z + 0.5 - playerVec.z) * (futureNode.z + 0.5 - playerVec.z)
                    )

                    if (distToFuture < maxLookAheadDist) {
                        bestNode = futureNode
                    } else {
                        break
                    }
                }
                bestNode
            } else {
                path[currentIndex]
            }

            val targetVec = Vec3d(
                targetNode.x + 0.5,
                targetNode.y.toDouble(),
                targetNode.z + 0.5
            )
            MovementController.updateRotation(player, targetVec)
        }

        path.forEachIndexed { index, pos ->
            try {
                val box = Box(
                    pos.x + 0.1, pos.y + 0.1, pos.z + 0.1,
                    pos.x + 0.9, pos.y + 0.9, pos.z + 0.9
                )
                val color = when {
                    index == currentIndex -> Color(0, 255, 0, 200)
                    index < currentIndex -> Color(100, 100, 100, 100)
                    else -> Color(0, 150, 255, 200)
                }
                Render3D.drawBox(ctx, box, color, esp = true)
            } catch (e: Exception) {
                if (DEBUG) println("[Pathfinder] Render error at waypoint $index: ${e.message}")
            }
        }

        for (i in 0 until path.size - 1) {
            try {
                val start = Vec3d.ofCenter(path[i])
                val end = Vec3d.ofCenter(path[i + 1])
                val lineColor = if (i < currentIndex) {
                    Color(150, 150, 150, 255)
                } else {
                    Color(0, 200, 255, 255)
                }
                Render3D.drawLine(ctx, start, end, lineColor, esp = true, thickness = 3f)
            } catch (e: Exception) {
                if (DEBUG) println("[Pathfinder] Line render error: ${e.message}")
            }
        }

        targetPos?.let { target ->
            try {
                val targetBox = Box(
                    target.x + 0.05, target.y + 0.05, target.z + 0.05,
                    target.x + 0.95, target.y + 0.95, target.z + 0.95
                )
                Render3D.drawBox(ctx, targetBox, Color(255, 0, 0, 200), esp = true)
            } catch (e: Exception) {
                if (DEBUG) println("[Pathfinder] Target render error: ${e.message}")
            }
        }
    }
    
    /**
     * Thread-safe pathfinding that uses a world snapshot.
     * This version runs on a background thread and doesn't freeze the game.
     * 
     * @param start Starting position
     * @param end Target position
     * @return List of waypoints or null if no path found
     */
    private fun findPathThreaded(start: BlockPos, end: BlockPos): List<BlockPos>? {
        val snapshot = worldSnapshot ?: return null
        val player = mc.player ?: return null
        
        val openSet = PriorityQueue<PathNode>(compareBy { it.fCost })
        val closedSet = mutableSetOf<BlockPos>()
        val allNodes = mutableMapOf<BlockPos, PathNode>()

        val startNode = PathNode(start, gCost = 0.0, hCost = heuristic(start, end))
        allNodes[start] = startNode
        openSet.add(startNode)

        var iterations = 0

        while (openSet.isNotEmpty() && iterations < MAX_ITERATIONS) {
            // Check if calculation was cancelled
            if (isCancelled || Thread.currentThread().isInterrupted) {
                if (DEBUG) println("[Pathfinder-Thread] Path calculation cancelled at iteration $iterations")
                return null
            }
            
            iterations++

            val current = openSet.poll()
            closedSet.add(current.pos)

            if (current.pos == end) {
                return reconstructPath(current)
            }

            for (neighbor in getNeighborsThreaded(current.pos, snapshot, player)) {
                if (neighbor in closedSet) continue
                if (!isWalkableThreaded(neighbor, snapshot)) continue
                if (!canMoveDiagonallyThreaded(current.pos, neighbor, snapshot)) continue

                var movementCost = distance(current.pos, neighbor)

                val heightChange = neighbor.y - current.pos.y
                
                // Heavy penalty for going up - discourages air paths
                if (heightChange > 0) {
                    movementCost += heightChange * JUMP_COST_MULTIPLIER * 3.0  // 3x multiplier
                }
                
                // Penalty for being high above ground
                val groundDistance = getDistanceToGround(neighbor, snapshot)
                if (groundDistance > 1) {
                    movementCost += groundDistance * 5.0  // Strong penalty for air paths
                }

                val wallProximityPenalty = checkWallProximityThreaded(neighbor, snapshot)
                movementCost += wallProximityPenalty

                current.parent?.let { parent ->
                    val prevDir = current.pos.subtract(parent.pos)
                    val nextDir = neighbor.subtract(current.pos)

                    if (prevDir.x != nextDir.x || prevDir.z != nextDir.z) {
                        val prevIsDiagonal = prevDir.x != 0 && prevDir.z != 0
                        val nextIsDiagonal = nextDir.x != 0 && nextDir.z != 0

                        movementCost += if (prevIsDiagonal || nextIsDiagonal) {
                            DIAGONAL_TURN_COST
                        } else {
                            CARDINAL_TURN_COST
                        }
                    }
                }

                val tentativeG = current.gCost + movementCost
                val neighborNode = allNodes.getOrPut(neighbor) {
                    PathNode(neighbor, hCost = heuristic(neighbor, end))
                }

                if (tentativeG < neighborNode.gCost) {
                    neighborNode.gCost = tentativeG
                    neighborNode.parent = current

                    if (neighborNode !in openSet) {
                        openSet.add(neighborNode)
                    }
                }
            }
        }

        return null
    }
    
    /**
     * Thread-safe version of getNeighbors using world snapshot.
     * Optimized to generate fewer neighbors for faster pathfinding.
     */
    private fun getNeighborsThreaded(pos: BlockPos, snapshot: WorldSnapshot, player: ClientPlayerEntity): List<BlockPos> {
        val neighbors = mutableListOf<BlockPos>()
        
        val maxJumpHeight = minOf(getMaxJumpHeight(player), 2)  // Cap at 2 for speed
        val maxJumpDistance = minOf(getMaxJumpDistance(player), 3)  // Cap at 3 for speed

        fun hasHeadroomThreaded(checkPos: BlockPos): Boolean {
            val blockAhead = snapshot.getBlockState(checkPos.up())
            return isPassableBlock(blockAhead) || !blockAhead.isSolidBlock(snapshot.world, checkPos.up())
        }
        
        fun adjustForSnow(checkPos: BlockPos): BlockPos {
            // Check if there's snow at or below this position
            val blockAt = snapshot.getBlockState(checkPos)
            val blockBelow = snapshot.getBlockState(checkPos.down())
            
            if (blockAt.block.translationKey.contains("snow")) {
                // Standing in snow, move up to stand on top
                return checkPos.up()
            }
            if (blockBelow.block.translationKey.contains("snow")) {
                // Snow below, position is correct (standing on snow)
                return checkPos
            }
            return checkPos
        }

        // Cardinal movements - adjust for snow
        listOf(
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1)
        ).map { adjustForSnow(it) }.filter { hasHeadroomThreaded(it) }.forEach { neighbors.add(it) }

        // Diagonal movements - adjust for snow
        listOf(
            pos.add(1, 0, 1), pos.add(1, 0, -1),
            pos.add(-1, 0, 1), pos.add(-1, 0, -1)
        ).map { adjustForSnow(it) }.filter { hasHeadroomThreaded(it) }.forEach { neighbors.add(it) }

        // Only add jump/climb movements if there's a block blocking direct movement
        // This prevents unnecessary air paths
        val needsJumps = listOf(
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1)
        ).any { !isWalkableThreaded(it, snapshot) && hasHeadroomThreaded(it) }

        if (needsJumps) {
            // Simplified jump movements - only when needed
            val cardinalDirs = listOf(
                Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
            )

            // Only test short jumps for speed and prefer staying low
            for ((dx, dz) in cardinalDirs) {
                for (height in 1..1) {  // Only 1 block jumps to avoid air paths
                    for (distance in 1..2) {  // Only 1-2 block distance
                        val jumpPos = pos.add(dx * distance, height, dz * distance)
                        
                        if (isWalkableThreaded(jumpPos, snapshot) && isJumpPathClearThreaded(pos, jumpPos, snapshot)) {
                            neighbors.add(jumpPos)
                        }
                    }
                }
            }
        }

        // Simplified fall movements - only cardinal directions
        val fallOffsets = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
        )

        for ((dx, dz) in fallOffsets) {
            for (dropHeight in 1..MAX_FALL_DISTANCE step 2) {  // Check every 2 blocks for speed
                val dropPos = pos.add(dx, -dropHeight, dz)
                
                if (isWalkableThreaded(dropPos, snapshot)) {
                    neighbors.add(dropPos)
                    break
                }
            }
        }

        return neighbors
    }
    
    /**
     * Calculates how far above solid ground a position is.
     * Returns 0 if on ground (including snow layers), higher values for positions in air.
     */
    private fun getDistanceToGround(pos: BlockPos, snapshot: WorldSnapshot): Int {
        for (i in 0..10) {  // Check up to 10 blocks below
            val checkPos = pos.down(i)
            val blockState = snapshot.getBlockState(checkPos)
            val blockName = blockState.block.translationKey
            
            // Consider snow layers as ground
            if (blockName.contains("snow")) {
                return i
            }
            
            if (blockState.isSolidBlock(snapshot.world, checkPos) && !isPassableBlock(blockState)) {
                return i
            }
        }
        return 10  // Max penalty if very high in air
    }
    
    /**
     * Thread-safe version of checkWallProximity.
     * Simplified for speed.
     */
    private fun checkWallProximityThreaded(pos: BlockPos, snapshot: WorldSnapshot): Double {
        // Check horizontal directions
        var wallCount = 0
        val checkPositions = listOf(
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1)
        )

        var avoidBlockPenalty = 0.0
        
        for (checkPos in checkPositions) {
            val blockState = snapshot.getBlockState(checkPos)
            if (blockState.isSolidBlock(snapshot.world, checkPos) && !isPassableBlock(blockState)) {
                wallCount++
            }
            // Massive penalty for glass panes, glass blocks, fences etc - completely avoid them
            if (isAvoidBlock(blockState)) {
                avoidBlockPenalty += 50.0  // Massive penalty to force avoidance
            }
        }
        
        // Check for "squeeze" situations - blocks on opposite sides
        var squeezePenalty = 0.0
        
        // Check X-axis squeeze (left and right walls)
        val leftWall = snapshot.getBlockState(pos.add(-1, 0, 0))
        val rightWall = snapshot.getBlockState(pos.add(1, 0, 0))
        if (leftWall.isSolidBlock(snapshot.world, pos.add(-1, 0, 0)) && 
            rightWall.isSolidBlock(snapshot.world, pos.add(1, 0, 0))) {
            squeezePenalty += 3.0  // Heavy penalty for X-axis squeeze
        }
        
        // Check Z-axis squeeze (front and back walls)
        val frontWall = snapshot.getBlockState(pos.add(0, 0, 1))
        val backWall = snapshot.getBlockState(pos.add(0, 0, -1))
        if (frontWall.isSolidBlock(snapshot.world, pos.add(0, 0, 1)) && 
            backWall.isSolidBlock(snapshot.world, pos.add(0, 0, -1))) {
            squeezePenalty += 3.0  // Heavy penalty for Z-axis squeeze
        }

        val basePenalty = when {
            wallCount >= 6 -> 2.0 
            wallCount >= 4 -> 1.0 
            wallCount >= 2 -> 0.3 
            else -> 0.0 
        }
        
        return basePenalty + squeezePenalty + avoidBlockPenalty
    }
    
    /**
     * Thread-safe version of isWalkable.
     * Properly handles snow layers as walkable surfaces.
     */
    private fun isWalkableThreaded(pos: BlockPos, snapshot: WorldSnapshot): Boolean {
        val blockBelow = snapshot.getBlockState(pos.down())
        val blockAt = snapshot.getBlockState(pos)
        val blockAbove = snapshot.getBlockState(pos.up())
        
        // Check if below or at position is snow layer
        val blockBelowName = blockBelow.block.translationKey
        val blockAtName = blockAt.block.translationKey
        val isSnowBelow = blockBelowName.contains("snow")
        val isSnowAt = blockAtName.contains("snow")
        
        // If snow is below, verify there's actual solid ground under it
        val hasGroundUnderSnow = if (isSnowBelow) {
            var checkPos = pos.down(2)
            var foundSolid = false
            for (i in 1..8) {
                val checkState = snapshot.getBlockState(checkPos)
                val checkName = checkState.block.translationKey
                if (checkName.contains("snow")) {
                    checkPos = checkPos.down()
                } else if (checkState.isSolidBlock(snapshot.world, checkPos)) {
                    foundSolid = true
                    break
                } else {
                    break  // Found air or non-solid under snow
                }
            }
            foundSolid
        } else {
            true
        }
        
        // If standing IN snow (not on top), this is not valid
        if (isSnowAt && !isPassableBlock(blockAt)) {
            return false
        }
        
        // Snow floating on air is not walkable
        if (isSnowBelow && !hasGroundUnderSnow) {
            return false
        }

        // Snow layers count as solid ground only when there's real ground under them
        val hasGround = blockBelow.isSolidBlock(snapshot.world, pos.down()) || 
                       (isSnowBelow && hasGroundUnderSnow)

        // Position must be clear (passable snow or air)
        val isPositionClear = isPassableBlock(blockAt) || isSnowAt

        val hasHeadroom = isPassableBlock(blockAbove) || 
                         !blockAbove.isSolidBlock(snapshot.world, pos.up())

        return hasGround && isPositionClear && hasHeadroom
    }
    
    /**
     * Thread-safe version of canMoveDiagonally.
     */
    private fun canMoveDiagonallyThreaded(from: BlockPos, to: BlockPos, snapshot: WorldSnapshot): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z

        if (dx == 0 || dz == 0) return true

        val checkPos1 = from.add(dx, 0, 0)
        val checkPos2 = from.add(0, 0, dz)

        val blockState1 = snapshot.getBlockState(checkPos1)
        val blockState2 = snapshot.getBlockState(checkPos2)

        return (isPassableBlock(blockState1) || !blockState1.isSolidBlock(snapshot.world, checkPos1)) &&
               (isPassableBlock(blockState2) || !blockState2.isSolidBlock(snapshot.world, checkPos2))
    }
    
    /**
     * Thread-safe version of isJumpPathClear.
     */
    private fun isJumpPathClearThreaded(from: BlockPos, to: BlockPos, snapshot: WorldSnapshot): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dy = to.y - from.y

        val steps = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))
        if (steps == 0) return true

        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val checkX = from.x + (dx * t).toInt()
            val checkZ = from.z + (dz * t).toInt()
            val checkY = from.y + (dy * t).toInt()
            val checkPos = BlockPos(checkX, checkY, checkZ)

            val blockAt = snapshot.getBlockState(checkPos)
            val blockAbove = snapshot.getBlockState(checkPos.up())

            if (!isPassableBlock(blockAt) && blockAt.isSolidBlock(snapshot.world, checkPos)) {
                return false
            }
            if (!isPassableBlock(blockAbove) && blockAbove.isSolidBlock(snapshot.world, checkPos.up())) {
                return false
            }
        }

        return true
    }
}

/**
 * Thread-safe snapshot of world blocks for pathfinding calculations.
 * Pre-caches a reasonable area on the main thread, then allows safe background access.
 */
private class WorldSnapshot private constructor(
    val world: net.minecraft.world.World,
    private val blockCache: MutableMap<BlockPos, net.minecraft.block.BlockState>
) {
    companion object {
        private val gson = GsonBuilder().create()  // No pretty printing for faster I/O
        private val cacheDirectory = File("pathfinder_cache")
        private var currentAreaName = "unknown"
        private var isBackgroundCachingActive = false
        
        /** Set of chunk coordinates that have been cached to disk */
        private val cachedChunksToDisk = Collections.synchronizedSet(mutableSetOf<Pair<Int, Int>>())
        
        /** Set of chunks currently loaded in memory */
        private val loadedChunksInMemory = Collections.synchronizedSet(mutableSetOf<Pair<Int, Int>>())
        
        /** Shared block cache for gradual loading */
        private val sharedBlockCache = Collections.synchronizedMap(mutableMapOf<BlockPos, net.minecraft.block.BlockState>())
        
        /** Queue of chunks to load gradually */
        private val chunkLoadQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int>>()
        
        /** Flag indicating if gradual loading is ready for pathfinding */
        @Volatile
        var isReadyForPathfinding = false
            private set
        
        /** Required chunks for current pathfinding request */
        private val requiredChunks = Collections.synchronizedSet(mutableSetOf<Pair<Int, Int>>())
        
        init {
            if (!cacheDirectory.exists()) {
                cacheDirectory.mkdirs()
            }
            // Load the list of cached chunks on startup
            loadCachedChunksList()
        }
        
        /**
         * Called every game tick to load 1 chunk into memory.
         * Call this from the main tick loop.
         */
        fun tickChunkLoading(world: net.minecraft.world.World) {
            val chunk = chunkLoadQueue.poll() ?: return
            
            val (chunkX, chunkZ) = chunk
            val chunkFile = getChunkCacheFile(chunkX, chunkZ)
            
            if (chunkFile.exists()) {
                val loaded = loadChunkFromDiskIntoShared(chunkX, chunkZ)
                if (loaded > 0) {
                    loadedChunksInMemory.add(chunk)
                }
            } else {
                // Load from world
                val loaded = loadChunkFromWorldIntoShared(world, chunkX, chunkZ)
                if (loaded > 0) {
                    loadedChunksInMemory.add(chunk)
                    // Save to disk in background
                    Thread {
                        saveChunkToDisk(world, chunkX, chunkZ)
                        cachedChunksToDisk.add(chunk)
                        saveCachedChunksIndex()
                    }.apply { isDaemon = true }.start()
                }
            }
            
            // Check if all required chunks are loaded
            if (requiredChunks.isNotEmpty() && requiredChunks.all { it in loadedChunksInMemory }) {
                isReadyForPathfinding = true
                println("[WorldSnapshot] All ${requiredChunks.size} required chunks loaded! Ready for pathfinding.")
            }
        }
        
        /**
         * Gets the number of chunks remaining to load.
         */
        fun getChunksRemaining(): Int = chunkLoadQueue.size
        
        /**
         * Queues chunks for gradual loading.
         */
        fun queueChunksForLoading(startPos: BlockPos, endPos: BlockPos) {
            val startChunkX = startPos.x shr 4
            val startChunkZ = startPos.z shr 4
            val endChunkX = endPos.x shr 4
            val endChunkZ = endPos.z shr 4
            
            val minChunkX = minOf(startChunkX, endChunkX) - 3
            val maxChunkX = maxOf(startChunkX, endChunkX) + 3
            val minChunkZ = minOf(startChunkZ, endChunkZ) - 3
            val maxChunkZ = maxOf(startChunkZ, endChunkZ) + 3
            
            requiredChunks.clear()
            chunkLoadQueue.clear()
            isReadyForPathfinding = false
            
            var queued = 0
            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunkPos = Pair(chunkX, chunkZ)
                    requiredChunks.add(chunkPos)
                    
                    if (chunkPos !in loadedChunksInMemory) {
                        chunkLoadQueue.add(chunkPos)
                        queued++
                    }
                }
            }
            
            // If all chunks already loaded, we're ready immediately
            if (queued == 0) {
                isReadyForPathfinding = true
                println("[WorldSnapshot] All chunks already in memory! Ready immediately.")
            } else {
                println("[WorldSnapshot] Queued $queued chunks for gradual loading (1 per tick)")
            }
        }
        
        /**
         * Loads a chunk from disk into the shared cache.
         */
        private fun loadChunkFromDiskIntoShared(chunkX: Int, chunkZ: Int): Int {
            val chunkFile = getChunkCacheFile(chunkX, chunkZ)
            if (!chunkFile.exists()) return 0
            
            try {
                val jsonText = chunkFile.readText()
                val json = gson.fromJson(jsonText, JsonObject::class.java)
                val blocksArray = json.getAsJsonArray("blocks")
                var loaded = 0
                
                for (element in blocksArray) {
                    val obj = element.asJsonObject
                    val x = obj.get("x").asInt
                    val y = obj.get("y").asInt
                    val z = obj.get("z").asInt
                    val blockId = obj.get("block").asString
                    
                    val pos = BlockPos(x, y, z)
                    val block = net.minecraft.registry.Registries.BLOCK.get(
                        net.minecraft.util.Identifier.of(blockId)
                    )
                    sharedBlockCache[pos] = block.defaultState
                    loaded++
                }
                
                return loaded
            } catch (e: Exception) {
                return 0
            }
        }
        
        /**
         * Loads a chunk from world into the shared cache.
         */
        private fun loadChunkFromWorldIntoShared(world: net.minecraft.world.World, chunkX: Int, chunkZ: Int): Int {
            val startX = chunkX shl 4
            val startZ = chunkZ shl 4
            var loaded = 0
            
            try {
                for (x in startX until startX + 16) {
                    for (z in startZ until startZ + 16) {
                        for (y in world.bottomY until (world.bottomY + world.height)) {
                            val pos = BlockPos(x, y, z)
                            val state = world.getBlockState(pos)
                            sharedBlockCache[pos] = state
                            loaded++
                        }
                    }
                }
            } catch (e: Exception) {
                // Chunk might not be loaded
            }
            
            return loaded
        }
        
        /**
         * Creates a snapshot using the shared cache.
         */
        fun createFromSharedCache(world: net.minecraft.world.World): WorldSnapshot {
            return WorldSnapshot(world, sharedBlockCache)
        }
        
        init {
            if (!cacheDirectory.exists()) {
                cacheDirectory.mkdirs()
            }
            // Load the list of cached chunks on startup
            loadCachedChunksList()
        }
        
        /**
         * Loads the list of already cached chunks from the chunks index file.
         */
        private fun loadCachedChunksList() {
            try {
                val indexFile = File(cacheDirectory, "chunks_index.json")
                if (indexFile.exists()) {
                    val json = gson.fromJson(indexFile.readText(), JsonObject::class.java)
                    val chunksArray = json.getAsJsonArray("chunks")
                    for (element in chunksArray) {
                        val obj = element.asJsonObject
                        cachedChunksToDisk.add(Pair(obj.get("x").asInt, obj.get("z").asInt))
                    }
                    println("[WorldSnapshot] Found ${cachedChunksToDisk.size} chunks saved on disk")
                }
            } catch (e: Exception) {
                println("[WorldSnapshot] Failed to load chunks index: ${e.message}")
            }
        }
        
        /**
         * Saves the list of cached chunks to index file.
         */
        private fun saveCachedChunksIndex() {
            try {
                val indexFile = File(cacheDirectory, "chunks_index.json")
                val json = JsonObject()
                val chunksArray = JsonArray()
                for ((x, z) in cachedChunksToDisk) {
                    val obj = JsonObject()
                    obj.addProperty("x", x)
                    obj.addProperty("z", z)
                    chunksArray.add(obj)
                }
                json.add("chunks", chunksArray)
                json.addProperty("count", cachedChunksToDisk.size)
                indexFile.writeText(gson.toJson(json))
            } catch (e: Exception) {
                println("[WorldSnapshot] Failed to save chunks index: ${e.message}")
            }
        }
        
        /**
         * Gets the cache file for a specific chunk.
         */
        private fun getChunkCacheFile(chunkX: Int, chunkZ: Int): File {
            val chunkDir = File(cacheDirectory, currentAreaName)
            if (!chunkDir.exists()) chunkDir.mkdirs()
            return File(chunkDir, "chunk_${chunkX}_${chunkZ}.json")
        }
        
        /**
         * Extracts area name from tablist (e.g., "Dwarven Mines").
         */
        private fun getAreaNameFromTablist(): String {
            try {
                val mc = MinecraftClient.getInstance()
                val playerListHud = mc.inGameHud?.playerListHud
                
                if (playerListHud != null) {
                    // Try to extract from player list entries
                    val playerList = mc.networkHandler?.playerList
                    if (playerList != null) {
                        for (entry in playerList) {
                            val displayName = entry.displayName?.string ?: continue
                            
                            // Look for area name in display (e.g., "Area: Dwarven Mines")
                            if (displayName.contains("Area:", ignoreCase = true)) {
                                val parts = displayName.split("Area:", ignoreCase = true)
                                if (parts.size > 1) {
                                    val areaName = parts[1].trim()
                                        .replace(Regex("[^a-zA-Z0-9 ]"), "") // Remove special chars
                                        .replace(" ", "_")
                                        .lowercase()
                                    if (areaName.isNotEmpty()) return areaName
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[WorldSnapshot] Failed to get area from tablist: ${e.message}")
            }
            return "unknown"
        }
        
        /**
         * Gets the cache file for the current world/area.
         */
        private fun getCacheFile(world: net.minecraft.world.World): File {
            // Update area name from tablist
            val areaName = getAreaNameFromTablist()
            if (areaName != "unknown") {
                currentAreaName = areaName
            }
            
            // Use area name and dimension as filename
            val dimension = world.registryKey.value.toString().replace(':', '_')
            val fileName = if (currentAreaName != "unknown") {
                "${currentAreaName}_${dimension}.json"
            } else {
                "world_${dimension}.json"
            }
            return File(cacheDirectory, fileName)
        }
        /**
         * Starts monitoring for new chunk loads and saves them to disk automatically.
         * This builds up the persistent world cache over time.
         */
        fun startChunkCaching(world: net.minecraft.world.World) {
            if (isBackgroundCachingActive) return
            isBackgroundCachingActive = true
            
            // Update area name
            val areaName = getAreaNameFromTablist()
            if (areaName != "unknown") {
                currentAreaName = areaName
            }
            
            Thread {
                try {
                    println("[WorldSnapshot] Started automatic chunk caching for ${currentAreaName}")
                    println("[WorldSnapshot] Already have ${cachedChunksToDisk.size} chunks saved on disk")
                    
                    while (isBackgroundCachingActive) {
                        try {
                            val mc = MinecraftClient.getInstance()
                            val player = mc.player
                            if (player != null) {
                                val playerChunkX = (player.x.toInt()) shr 4
                                val playerChunkZ = (player.z.toInt()) shr 4
                                val renderDistance = mc.options.viewDistance.value
                                
                                for (chunkX in (playerChunkX - renderDistance)..(playerChunkX + renderDistance)) {
                                    for (chunkZ in (playerChunkZ - renderDistance)..(playerChunkZ + renderDistance)) {
                                        val chunkPos = Pair(chunkX, chunkZ)
                                        
                                        if (chunkPos !in cachedChunksToDisk) {
                                            // Save this chunk to disk for future use
                                            saveChunkToDisk(world, chunkX, chunkZ)
                                            cachedChunksToDisk.add(chunkPos)
                                            
                                            // Small delay to prevent lag
                                            Thread.sleep(50)
                                        }
                                    }
                                }
                            }
                            
                            // Save chunks index periodically
                            saveCachedChunksIndex()
                            
                            // Check every 5 seconds
                            Thread.sleep(5000)
                        } catch (e: Exception) {
                            println("[WorldSnapshot] Chunk caching error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    println("[WorldSnapshot] Chunk caching thread error: ${e.message}")
                }
            }.apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
        
        /**
         * Stops automatic chunk caching.
         */
        fun stopChunkCaching() {
            isBackgroundCachingActive = false
            saveCachedChunksIndex()
            println("[WorldSnapshot] Stopped automatic chunk caching")
        }
        
        /**
         * Saves all blocks in a chunk to disk (JSON file).
         */
        private fun saveChunkToDisk(world: net.minecraft.world.World, chunkX: Int, chunkZ: Int) {
            val startX = chunkX shl 4
            val startZ = chunkZ shl 4
            val blocksArray = JsonArray()
            var cached = 0
            var skippedAir = 0
            
            for (x in startX until startX + 16) {
                for (z in startZ until startZ + 16) {
                    for (y in world.bottomY until (world.bottomY + world.height)) {
                        val pos = BlockPos(x, y, z)
                        try {
                            val state = world.getBlockState(pos)
                            // Skip air blocks to save space
                            if (state.isAir) {
                                skippedAir++
                                continue
                            }
                            
                            val blockObj = JsonObject()
                            blockObj.addProperty("x", pos.x)
                            blockObj.addProperty("y", pos.y)
                            blockObj.addProperty("z", pos.z)
                            val blockId = net.minecraft.registry.Registries.BLOCK.getId(state.block)
                            blockObj.addProperty("block", blockId.toString())
                            blocksArray.add(blockObj)
                            cached++
                        } catch (e: Exception) {
                            // Skip failed blocks
                        }
                    }
                }
            }
            
            // Save chunk to its own file
            if (cached > 0) {
                try {
                    val chunkFile = getChunkCacheFile(chunkX, chunkZ)
                    val json = JsonObject()
                    json.add("blocks", blocksArray)
                    json.addProperty("chunk_x", chunkX)
                    json.addProperty("chunk_z", chunkZ)
                    json.addProperty("cached_at", System.currentTimeMillis())
                    json.addProperty("block_count", cached)
                    chunkFile.writeText(gson.toJson(json))
                    println("[WorldSnapshot] Saved chunk ($chunkX, $chunkZ): $cached blocks (skipped $skippedAir air)")
                } catch (e: Exception) {
                    println("[WorldSnapshot] Failed to save chunk ($chunkX, $chunkZ): ${e.message}")
                }
            }
        }
        
        /**
         * Creates a snapshot for pathfinding.
         * Loads blocks from disk into memory for fast access.
         * Also triggers background caching of any new chunks in the path area.
         * 
         * @param world The world to snapshot
         * @param start Start position
         * @param end End position
         * @return WorldSnapshot instance with blocks loaded into memory
         */
        fun create(world: net.minecraft.world.World?, start: BlockPos, end: BlockPos): WorldSnapshot? {
            if (world == null) return null
            
            // Update area name
            val areaName = getAreaNameFromTablist()
            if (areaName != "unknown") {
                currentAreaName = areaName
            }
            
            // Calculate chunks needed for the path
            val startChunkX = start.x shr 4
            val startChunkZ = start.z shr 4
            val endChunkX = end.x shr 4
            val endChunkZ = end.z shr 4
            
            val minChunkX = minOf(startChunkX, endChunkX) - 3
            val maxChunkX = maxOf(startChunkX, endChunkX) + 3
            val minChunkZ = minOf(startChunkZ, endChunkZ) - 3
            val maxChunkZ = maxOf(startChunkZ, endChunkZ) + 3
            
            println("[WorldSnapshot] Need chunks from ($minChunkX, $minChunkZ) to ($maxChunkX, $maxChunkZ)")
            println("[WorldSnapshot] Have ${cachedChunksToDisk.size} chunks in index, checking overlap...")
            
            // Create in-memory cache for pathfinding
            val blockCache = Collections.synchronizedMap(mutableMapOf<BlockPos, net.minecraft.block.BlockState>())
            
            // Load existing chunks from disk into memory
            var loadedFromDisk = 0
            var loadedFromWorld = 0
            var chunksNotFound = 0
            
            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunkPos = Pair(chunkX, chunkZ)
                    
                    // Check if chunk file exists on disk (even if not in index)
                    val chunkFile = getChunkCacheFile(chunkX, chunkZ)
                    
                    if (chunkFile.exists()) {
                        // Load from disk into memory
                        val loaded = loadChunkFromDisk(chunkX, chunkZ, blockCache)
                        if (loaded > 0) {
                            loadedChunksInMemory.add(chunkPos)
                            cachedChunksToDisk.add(chunkPos) // Update index
                            loadedFromDisk++
                        }
                    } else {
                        // Cache from world if chunk file doesn't exist
                        val cached = loadChunkFromWorld(world, chunkX, chunkZ, blockCache)
                        if (cached > 0) {
                            loadedFromWorld++
                            // Also save to disk in background
                            val chunkXCopy = chunkX
                            val chunkZCopy = chunkZ
                            Thread {
                                saveChunkToDisk(world, chunkXCopy, chunkZCopy)
                                cachedChunksToDisk.add(Pair(chunkXCopy, chunkZCopy))
                                saveCachedChunksIndex()
                            }.apply { isDaemon = true }.start()
                        } else {
                            chunksNotFound++
                        }
                    }
                }
            }
            
            println("[WorldSnapshot] Loaded ${blockCache.size} blocks into memory")
            println("[WorldSnapshot] - From disk: $loadedFromDisk chunks, From world: $loadedFromWorld chunks, Not found: $chunksNotFound")
            
            return WorldSnapshot(world, blockCache)
        }
        
        /**
         * Debug: Get info about cached chunks
         */
        fun getDebugInfo(): String {
            val sb = StringBuilder()
            sb.appendLine("§7=== WorldSnapshot Debug ===")
            sb.appendLine("§7Area: §f$currentAreaName")
            sb.appendLine("§7Chunks in index: §f${cachedChunksToDisk.size}")
            sb.appendLine("§7Chunks in memory: §f${loadedChunksInMemory.size}")
            sb.appendLine("§7Cache directory: §f${cacheDirectory.absolutePath}")
            
            val areaDir = File(cacheDirectory, currentAreaName)
            if (areaDir.exists()) {
                val files = areaDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
                sb.appendLine("§7Chunk files in area folder: §f${files.size}")
                if (files.isNotEmpty()) {
                    val sample = files.take(5).map { it.name }
                    sb.appendLine("§7Sample files: §f$sample")
                }
            } else {
                sb.appendLine("§cArea folder doesn't exist: ${areaDir.absolutePath}")
            }
            
            return sb.toString()
        }
        
        /**
         * Debug: Force load all chunks from the area folder into memory
         */
        fun debugLoadAllChunks(world: net.minecraft.world.World): Pair<Int, Int> {
            val areaName = getAreaNameFromTablist()
            if (areaName != "unknown") {
                currentAreaName = areaName
            }
            
            val areaDir = File(cacheDirectory, currentAreaName)
            if (!areaDir.exists()) {
                println("[WorldSnapshot] Area folder doesn't exist: ${areaDir.absolutePath}")
                return Pair(0, 0)
            }
            
            val files = areaDir.listFiles()?.filter { it.name.startsWith("chunk_") && it.name.endsWith(".json") } ?: emptyList()
            println("[WorldSnapshot] Found ${files.size} chunk files in $currentAreaName")
            
            var loadedChunks = 0
            var loadedBlocks = 0
            
            // Load directly into the shared cache so pathfinding can use it
            sharedBlockCache.clear()
            
            for (file in files) {
                try {
                    // Parse chunk coords from filename: chunk_X_Z.json
                    val parts = file.nameWithoutExtension.split("_")
                    if (parts.size >= 3) {
                        val chunkX = parts[1].toInt()
                        val chunkZ = parts[2].toInt()
                        
                        val loaded = loadChunkFromDisk(chunkX, chunkZ, sharedBlockCache)
                        if (loaded > 0) {
                            loadedChunks++
                            loadedBlocks += loaded
                            cachedChunksToDisk.add(Pair(chunkX, chunkZ))
                            loadedChunksInMemory.add(Pair(chunkX, chunkZ))
                        }
                    }
                } catch (e: Exception) {
                    println("[WorldSnapshot] Error loading ${file.name}: ${e.message}")
                }
            }
            
            // Update the shared snapshot if one exists
            saveCachedChunksIndex()
            
            println("[WorldSnapshot] Debug load complete: $loadedChunks chunks, $loadedBlocks blocks")
            return Pair(loadedChunks, loadedBlocks)
        }
        
        /**
         * Loads a chunk from disk into the given cache map.
         * @return Number of blocks loaded
         */
        private fun loadChunkFromDisk(chunkX: Int, chunkZ: Int, cache: MutableMap<BlockPos, net.minecraft.block.BlockState>): Int {
            val chunkFile = getChunkCacheFile(chunkX, chunkZ)
            if (!chunkFile.exists()) return 0
            
            try {
                val jsonText = chunkFile.readText()
                val json = gson.fromJson(jsonText, JsonObject::class.java)
                val blocksArray = json.getAsJsonArray("blocks")
                var loaded = 0
                
                for (element in blocksArray) {
                    val obj = element.asJsonObject
                    val x = obj.get("x").asInt
                    val y = obj.get("y").asInt
                    val z = obj.get("z").asInt
                    val blockId = obj.get("block").asString
                    
                    val pos = BlockPos(x, y, z)
                    val block = net.minecraft.registry.Registries.BLOCK.get(
                        net.minecraft.util.Identifier.of(blockId)
                    )
                    cache[pos] = block.defaultState
                    loaded++
                }
                
                return loaded
            } catch (e: Exception) {
                println("[WorldSnapshot] Failed to load chunk ($chunkX, $chunkZ) from disk: ${e.message}")
                return 0
            }
        }
        
        /**
         * Loads a chunk from the live world into the cache map.
         * @return Number of blocks loaded
         */
        private fun loadChunkFromWorld(world: net.minecraft.world.World, chunkX: Int, chunkZ: Int, cache: MutableMap<BlockPos, net.minecraft.block.BlockState>): Int {
            val startX = chunkX shl 4
            val startZ = chunkZ shl 4
            var loaded = 0
            
            try {
                for (x in startX until startX + 16) {
                    for (z in startZ until startZ + 16) {
                        for (y in world.bottomY until (world.bottomY + world.height)) {
                            val pos = BlockPos(x, y, z)
                            val state = world.getBlockState(pos)
                            cache[pos] = state
                            loaded++
                        }
                    }
                }
            } catch (e: Exception) {
                // Chunk might not be loaded
            }
            
            return loaded
        }
        
        /**
         * Creates a snapshot by loading saved chunks from disk.
         */
        fun loadFromDisk(world: net.minecraft.world.World): WorldSnapshot? {
            // Update area name
            val areaName = getAreaNameFromTablist()
            if (areaName != "unknown") {
                currentAreaName = areaName
            }
            
            if (cachedChunksToDisk.isEmpty()) {
                println("[WorldSnapshot] No cached chunks found for $currentAreaName")
                return null
            }
            
            // Create empty cache - chunks will be loaded on demand via create()
            val blockCache = Collections.synchronizedMap(mutableMapOf<BlockPos, net.minecraft.block.BlockState>())
            
            println("[WorldSnapshot] Ready to load from ${cachedChunksToDisk.size} cached chunks on disk")
            return WorldSnapshot(world, blockCache)
        }
        
        /**
         * Clears the in-memory cache to free memory.
         * Disk cache remains intact.
         */
        fun clearMemoryCache() {
            loadedChunksInMemory.clear()
            println("[WorldSnapshot] Cleared in-memory cache. Disk cache still has ${cachedChunksToDisk.size} chunks")
        }
    }
    
    /**
     * Gets block state from in-memory cache.
     * Returns AIR if not in cache (fast path for pathfinding).
     */
    fun getBlockState(pos: BlockPos): net.minecraft.block.BlockState {
        return blockCache[pos] ?: Blocks.AIR.defaultState
    }
}


package jant.path.pathfinder

import jant.path.api.PathfinderAPI
import jant.path.api.PathfinderAPIInternal
import jant.path.api.PathfinderSession
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.registry.tag.BlockTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.cobalt.api.event.impl.render.WorldRenderContext
import org.cobalt.api.pathfinder.IPathExec
import org.cobalt.api.util.render.Render3D
import java.awt.Color
import java.util.*
import kotlin.math.sqrt

object PathfinderCore : IPathExec {

    private const val STUCK_THRESHOLD = 60
    private const val MAX_ITERATIONS = 50000
    private const val RECALC_COOLDOWN = 1000L 
    private const val MAX_FALL_DISTANCE = 60
    
    private const val JUMP_COST_MULTIPLIER = 1.5
    private const val DIAGONAL_TURN_COST = 0.3
    private const val CARDINAL_TURN_COST = 0.2
    private const val VERTICAL_CLIMB_COST = 2.5
    private const val VERTICAL_DROP_COST = 0.8
    
    private const val HEURISTIC_BIAS = 1.2

    private const val DEBUG = false
    
    // === STATE VARIABLES ===
    
    private val mc = MinecraftClient.getInstance()
    
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
    
    /** Counter for how many ticks the player hasn't moved */
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
     * @return Number of blocks the player can jump (1 + jump boost level)
     */
    private fun getMaxJumpHeight(player: ClientPlayerEntity): Int {
        val jumpBoost = getJumpBoostLevel(player)

        return 1 + jumpBoost
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
    }

    /**
     * Stops pathfinding and clears all state.
     * Call this to cancel pathfinding.
     */
    fun clearPath() {
        currentPath = null
        targetPos = null
        currentIndex = 0

        mc.options.sneakKey.setPressed(false)
        shouldKeepSneak = false
        currentApiSession = null
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
        val currentTime = System.currentTimeMillis()
        val playerPos = BlockPos.ofFloored(player.x, player.y, player.z)

        if (playerPos == lastPlayerPos) {
            stuckTicks++
        } else {
            stuckTicks = 0
            lastPlayerPos = playerPos
        }

        val shouldRecalculate = stuckTicks > STUCK_THRESHOLD

        if (currentPath == null || shouldRecalculate) {
            if (shouldRecalculate) {
                if (DEBUG) println("[Pathfinder] Stuck detected, recalculating path")
            }
            currentPath = findPath(playerPos, target)
            currentIndex = 0
            lastRecalcTime = currentTime
            stuckTicks = 0

            if (currentPath == null) {
                if (DEBUG) println("[Pathfinder] No path found to target")
                currentApiSession?.let { session ->
                    PathfinderAPI.notifyPathFailed(session, "No path found to target")
                }
                clearPath()
                return
            }
            
            // Notify API that path was calculated
            currentApiSession?.let { session ->
                PathfinderAPI.notifyPathCalculated(session, currentPath!!)
            }
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

        val world = mc.world ?: return
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
                    val blockAbove = world.getBlockState(checkPos.withY(y))
                    isPassableBlock(blockAbove) || !blockAbove.isSolidBlock(world, checkPos.withY(y))
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
                    val blockAtHead = world.getBlockState(checkPos.up())
                    val blockAboveHead = world.getBlockState(checkPos.up(2))

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
     * @param pos Position to check
     * @return Cost penalty (0.0 to 2.0)
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

        for (checkPos in checkPositions) {
            val blockState = world.getBlockState(checkPos)
            if (blockState.isSolidBlock(world, checkPos) && !isPassableBlock(blockState)) {
                wallCount++
            }
        }

        return when {
            wallCount >= 6 -> 2.0 
            wallCount >= 4 -> 1.0 
            wallCount >= 2 -> 0.3 
            else -> 0.0 
        }
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
}


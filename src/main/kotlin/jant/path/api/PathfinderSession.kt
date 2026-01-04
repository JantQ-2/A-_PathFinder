package jant.path.api

import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * Represents a pathfinding session with state and waypoints.
 */
data class PathfinderSession(
    /** Unique identifier for this session */
    val id: UUID,
    
    /** Target destination position */
    val target: BlockPos,
    
    /** Callback for path events */
    val callback: PathfinderCallback,
    
    /** Priority level (higher = more important) */
    val priority: Int = 0,
    
    /** Current state of the pathfinding session */
    var state: PathfinderState = PathfinderState.QUEUED,
    
    /** Calculated waypoints (empty until path is calculated) */
    var waypoints: List<BlockPos> = emptyList(),
    
    /** Timestamp when this session was created */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Possible states for a pathfinding session.
 */
enum class PathfinderState {
    /** Waiting to be processed */
    QUEUED,
    
    /** Currently calculating path */
    CALCULATING,
    
    /** Path calculated, now following it */
    FOLLOWING_PATH,
    
    /** Reached the destination */
    REACHED_DESTINATION,
    
    /** Path calculation or following failed */
    FAILED,
    
    /** Session was cancelled */
    CANCELLED
}


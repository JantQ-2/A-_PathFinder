package jant.path.api

import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * Public API for accessing the pathfinding system from other mods.
 * 
 * USAGE EXAMPLE:
 * ```kotlin
 * // Request pathfinding to a location
 * val sessionId = PathfinderAPI.requestPath(
 *     target = BlockPos(100, 64, 200),
 *     callback = object : PathfinderCallback {
 *         override fun onPathCalculated(session: PathfinderSession) {
 *             println("Path found with ${session.waypoints.size} waypoints")
 *         }
 *         override fun onPathFailed(session: PathfinderSession, reason: String) {
 *             println("Path failed: $reason")
 *         }
 *         override fun onReachedDestination(session: PathfinderSession) {
 *             println("Reached destination!")
 *         }
 *     }
 * )
 * 
 * // Cancel pathfinding
 * PathfinderAPI.cancelPath(sessionId)
 * ```
 */
object PathfinderAPI {

    private val sessions = mutableMapOf<UUID, PathfinderSession>()
    private var activeSession: PathfinderSession? = null

    /**
     * Requests pathfinding to a target position.
     * 
     * @param target The destination block position
     * @param callback Callback for path events
     * @param priority Priority level (higher = more important)
     * @return Session ID for tracking this pathfinding request
     */
    fun requestPath(
        target: BlockPos,
        callback: PathfinderCallback,
        priority: Int = 0
    ): UUID {
        val session = PathfinderSession(
            id = UUID.randomUUID(),
            target = target,
            callback = callback,
            priority = priority
        )
        
        sessions[session.id] = session
        
        // If no active session or this has higher priority, activate it
        if (activeSession == null || priority > activeSession!!.priority) {
            activateSession(session)
        }
        
        return session.id
    }

    /**
     * Cancels an active pathfinding session.
     * 
     * @param sessionId The session ID to cancel
     * @return true if session was cancelled, false if not found
     */
    fun cancelPath(sessionId: UUID): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        
        if (activeSession?.id == sessionId) {
            deactivateCurrentSession()
            // Activate next highest priority session
            sessions.values.maxByOrNull { it.priority }?.let { activateSession(it) }
        }
        
        return true
    }

    /**
     * Gets the current active pathfinding session.
     * 
     * @return The active session, or null if none
     */
    fun getActiveSession(): PathfinderSession? = activeSession

    /**
     * Gets a session by ID.
     * 
     * @param sessionId The session ID
     * @return The session, or null if not found
     */
    fun getSession(sessionId: UUID): PathfinderSession? = sessions[sessionId]

    /**
     * Gets all active sessions.
     * 
     * @return List of all sessions
     */
    fun getAllSessions(): List<PathfinderSession> = sessions.values.toList()

    /**
     * Checks if the pathfinder is currently active.
     * 
     * @return true if pathfinding is in progress
     */
    fun isPathfinding(): Boolean = activeSession != null

    /**
     * Clears all pathfinding sessions.
     */
    fun clearAll() {
        deactivateCurrentSession()
        sessions.clear()
    }

    // Internal API for PathfinderCore to use

    internal fun activateSession(session: PathfinderSession) {
        deactivateCurrentSession()
        activeSession = session
        session.state = PathfinderState.CALCULATING
        PathfinderAPIInternal.startPathfinding(session)
    }

    internal fun deactivateCurrentSession() {
        activeSession?.let { session ->
            PathfinderAPIInternal.stopPathfinding()
            if (session.state != PathfinderState.REACHED_DESTINATION) {
                session.state = PathfinderState.CANCELLED
            }
        }
        activeSession = null
    }

    internal fun notifyPathCalculated(session: PathfinderSession, waypoints: List<BlockPos>) {
        if (sessions.containsKey(session.id)) {
            session.waypoints = waypoints
            session.state = PathfinderState.FOLLOWING_PATH
            session.callback.onPathCalculated(session)
        }
    }

    internal fun notifyPathFailed(session: PathfinderSession, reason: String) {
        if (sessions.containsKey(session.id)) {
            session.state = PathfinderState.FAILED
            session.callback.onPathFailed(session, reason)
            sessions.remove(session.id)
            if (activeSession?.id == session.id) {
                activeSession = null
                // Activate next session if any
                sessions.values.maxByOrNull { it.priority }?.let { activateSession(it) }
            }
        }
    }

    internal fun notifyReachedDestination(session: PathfinderSession) {
        if (sessions.containsKey(session.id)) {
            session.state = PathfinderState.REACHED_DESTINATION
            session.callback.onReachedDestination(session)
            sessions.remove(session.id)
            if (activeSession?.id == session.id) {
                activeSession = null
                // Activate next session if any
                sessions.values.maxByOrNull { it.priority }?.let { activateSession(it) }
            }
        }
    }
}

/**
 * Internal API bridge to PathfinderCore.
 * Do not use directly - use PathfinderAPI instead.
 */
object PathfinderAPIInternal {
    var startPathfinding: (PathfinderSession) -> Unit = {}
    var stopPathfinding: () -> Unit = {}
}


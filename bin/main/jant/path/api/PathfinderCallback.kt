package jant.path.api

/**
 * Callback interface for pathfinding events.
 * Implement this to receive notifications about path calculation and execution.
 */
interface PathfinderCallback {
    /**
     * Called when a path has been successfully calculated.
     * 
     * @param session The pathfinding session with calculated waypoints
     */
    fun onPathCalculated(session: PathfinderSession)

    /**
     * Called when path calculation or following fails.
     * 
     * @param session The pathfinding session
     * @param reason Human-readable reason for failure
     */
    fun onPathFailed(session: PathfinderSession, reason: String)

    /**
     * Called when the destination is reached.
     * 
     * @param session The completed pathfinding session
     */
    fun onReachedDestination(session: PathfinderSession)

    /**
     * Called periodically with progress updates (optional).
     * Override to receive progress updates during pathfinding.
     * 
     * @param session The pathfinding session
     * @param currentWaypoint Current waypoint index
     * @param totalWaypoints Total number of waypoints
     */
    fun onProgress(session: PathfinderSession, currentWaypoint: Int, totalWaypoints: Int) {
        // Optional - default implementation does nothing
    }
}

/**
 * Simple callback implementation using lambdas for convenience.
 * 
 * USAGE:
 * ```kotlin
 * PathfinderAPI.requestPath(
 *     target = targetPos,
 *     callback = SimplePathfinderCallback(
 *         onCalculated = { println("Path calculated!") },
 *         onFailed = { _, reason -> println("Failed: $reason") },
 *         onReached = { println("Reached!") }
 *     )
 * )
 * ```
 */
class SimplePathfinderCallback(
    private val onCalculated: (PathfinderSession) -> Unit = {},
    private val onFailed: (PathfinderSession, String) -> Unit = { _, _ -> },
    private val onReached: (PathfinderSession) -> Unit = {},
    private val onProgressUpdate: (PathfinderSession, Int, Int) -> Unit = { _, _, _ -> }
) : PathfinderCallback {
    override fun onPathCalculated(session: PathfinderSession) = onCalculated(session)
    override fun onPathFailed(session: PathfinderSession, reason: String) = onFailed(session, reason)
    override fun onReachedDestination(session: PathfinderSession) = onReached(session)
    override fun onProgress(session: PathfinderSession, currentWaypoint: Int, totalWaypoints: Int) = 
        onProgressUpdate(session, currentWaypoint, totalWaypoints)
}


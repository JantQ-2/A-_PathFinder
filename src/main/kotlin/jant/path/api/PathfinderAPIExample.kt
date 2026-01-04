package jant.path.api

import jant.path.api.PathfinderAPI
import jant.path.api.SimplePathfinderCallback
import net.minecraft.util.math.BlockPos

/**
 * Example usage of the Pathfinder API for other mods.
 * 
 * This file demonstrates how external mods can use the pathfinding system.
 */
object PathfinderAPIExample {

    /**
     * Example 1: Basic pathfinding with simple callbacks
     */
    fun basicExample(x: Int, y: Int, z: Int) {
        val targetPos = BlockPos(x, y, z)
        
        val sessionId = PathfinderAPI.requestPath(
            target = targetPos,
            callback = SimplePathfinderCallback(
                onCalculated = { session ->
                    println("✓ Path calculated with ${session.waypoints.size} waypoints")
                },
                onFailed = { session, reason ->
                    println("✗ Path failed: $reason")
                },
                onReached = { session ->
                    println("✓ Reached destination!")
                }
            )
        )
        
        println("Started pathfinding with session ID: $sessionId")
    }

    /**
     * Example 2: Pathfinding with progress tracking
     */
    fun withProgressTracking(x: Int, y: Int, z: Int) {
        PathfinderAPI.requestPath(
            target = BlockPos(x, y, z),
            callback = SimplePathfinderCallback(
                onCalculated = { session ->
                    println("Path calculated. Starting movement...")
                },
                onProgressUpdate = { session, current, total ->
                    val percent = (current.toDouble() / total * 100).toInt()
                    println("Progress: $current/$total ($percent%)")
                },
                onReached = { session ->
                    val duration = System.currentTimeMillis() - session.createdAt
                    println("Reached destination in ${duration}ms")
                }
            )
        )
    }

    /**
     * Example 3: Priority pathfinding (interrupts lower priority tasks)
     */
    fun highPriorityPath(x: Int, y: Int, z: Int) {
        PathfinderAPI.requestPath(
            target = BlockPos(x, y, z),
            priority = 10, // Higher priority
            callback = SimplePathfinderCallback(
                onCalculated = { println("High priority path started") },
                onReached = { println("High priority path completed") }
            )
        )
    }

    /**
     * Example 4: Managing multiple pathfinding sessions
     */
    fun multiplePathsExample() {
        val waypoints = listOf(
            BlockPos(100, 64, 100),
            BlockPos(200, 64, 200),
            BlockPos(300, 64, 300)
        )
        
        var currentWaypointIndex = 0
        
        fun pathToNextWaypoint() {
            if (currentWaypointIndex >= waypoints.size) {
                println("All waypoints completed!")
                return
            }
            
            val waypoint = waypoints[currentWaypointIndex]
            
            PathfinderAPI.requestPath(
                target = waypoint,
                callback = SimplePathfinderCallback(
                    onReached = { 
                        println("Reached waypoint ${currentWaypointIndex + 1}/${waypoints.size}")
                        currentWaypointIndex++
                        pathToNextWaypoint() // Chain to next waypoint
                    },
                    onFailed = { _, reason ->
                        println("Failed at waypoint $currentWaypointIndex: $reason")
                    }
                )
            )
        }
        
        pathToNextWaypoint()
    }

    /**
     * Example 5: Cancelling pathfinding
     */
    fun cancellablePathfinding(x: Int, y: Int, z: Int) {
        val sessionId = PathfinderAPI.requestPath(
            target = BlockPos(x, y, z),
            callback = SimplePathfinderCallback(
                onCalculated = { println("Path started") },
                onReached = { println("Path completed") }
            )
        )
        
        // Later, cancel if needed:
        // PathfinderAPI.cancelPath(sessionId)
    }

    /**
     * Example 6: Custom callback implementation
     */
    class CustomPathfinderCallback : PathfinderCallback {
        override fun onPathCalculated(session: PathfinderSession) {
            // Custom logic when path is calculated
            println("[Custom] Path ready: ${session.waypoints.size} waypoints")
            
            // You can access the waypoints for custom visualization
            session.waypoints.forEachIndexed { index, pos ->
                println("  Waypoint $index: $pos")
            }
        }

        override fun onPathFailed(session: PathfinderSession, reason: String) {
            // Custom error handling
            println("[Custom] Pathfinding failed: $reason")
            
            // Could retry with different parameters, log to file, etc.
        }

        override fun onReachedDestination(session: PathfinderSession) {
            // Custom completion logic
            val duration = System.currentTimeMillis() - session.createdAt
            println("[Custom] Reached destination in ${duration}ms")
            
            // Could trigger other actions, play sound, etc.
        }

        override fun onProgress(session: PathfinderSession, currentWaypoint: Int, totalWaypoints: Int) {
            // Optional: track progress
            if (currentWaypoint % 10 == 0) {
                println("[Custom] Progress: $currentWaypoint/$totalWaypoints")
            }
        }
    }

    fun customCallbackExample(x: Int, y: Int, z: Int) {
        PathfinderAPI.requestPath(
            target = BlockPos(x, y, z),
            callback = CustomPathfinderCallback()
        )
    }

    /**
     * Example 7: Checking active sessions
     */
    fun sessionManagement() {
        // Check if pathfinding is active
        if (PathfinderAPI.isPathfinding()) {
            val activeSession = PathfinderAPI.getActiveSession()
            println("Currently pathfinding to: ${activeSession?.target}")
            println("State: ${activeSession?.state}")
        }
        
        // Get all sessions
        val allSessions = PathfinderAPI.getAllSessions()
        println("Total sessions: ${allSessions.size}")
        
        allSessions.forEach { session ->
            println("Session ${session.id}: ${session.state} (Priority: ${session.priority})")
        }
    }

    /**
     * Example 8: Emergency stop all pathfinding
     */
    fun stopAllPathfinding() {
        PathfinderAPI.clearAll()
        println("All pathfinding sessions cleared")
    }
}


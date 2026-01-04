# Pathfinder API Documentation

A clean, easy-to-use API for integrating pathfinding into your Minecraft mod.

## Adding as a Dependency

### Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://your-repo-url/") // Replace with actual repository
}

dependencies {
    modImplementation("jant.path:ASharp_Pathfinding:1.0.0")
}
```

### Gradle (Groovy)
```groovy
repositories {
    maven { url 'https://your-repo-url/' }
}

dependencies {
    modImplementation 'jant.path:ASharp_Pathfinding:1.0.0'
}
```

## Quick Start

### Basic Usage

```kotlin
import jant.path.api.PathfinderAPI
import jant.path.api.SimplePathfinderCallback
import net.minecraft.util.math.BlockPos

// Request pathfinding to a location
val sessionId = PathfinderAPI.requestPath(
    target = BlockPos(100, 64, 200),
    callback = SimplePathfinderCallback(
        onCalculated = { session ->
            println("Path ready with ${session.waypoints.size} waypoints!")
        },
        onFailed = { session, reason ->
            println("Failed: $reason")
        },
        onReached = { session ->
            println("Destination reached!")
        }
    )
)

// Cancel if needed
PathfinderAPI.cancelPath(sessionId)
```

## API Reference

### PathfinderAPI Object

Main entry point for pathfinding operations.

#### Methods

##### `requestPath(target: BlockPos, callback: PathfinderCallback, priority: Int = 0): UUID`
Requests pathfinding to a target position.

**Parameters:**
- `target` - The destination block position
- `callback` - Callback interface for path events
- `priority` - Priority level (higher = more important, default: 0)

**Returns:** UUID session ID for tracking

**Example:**
```kotlin
val sessionId = PathfinderAPI.requestPath(
    target = BlockPos(x, y, z),
    callback = myCallback,
    priority = 5
)
```

##### `cancelPath(sessionId: UUID): Boolean`
Cancels an active pathfinding session.

**Returns:** `true` if cancelled, `false` if session not found

**Example:**
```kotlin
if (PathfinderAPI.cancelPath(sessionId)) {
    println("Session cancelled successfully")
}
```

##### `getActiveSession(): PathfinderSession?`
Gets the currently executing pathfinding session.

**Returns:** Active session or `null`

##### `getSession(sessionId: UUID): PathfinderSession?`
Gets a specific session by ID.

##### `getAllSessions(): List<PathfinderSession>`
Gets all queued and active sessions.

##### `isPathfinding(): Boolean`
Checks if pathfinder is currently active.

##### `clearAll()`
Clears all pathfinding sessions immediately.

---

### PathfinderCallback Interface

Implement this to receive pathfinding events.

#### Methods

##### `onPathCalculated(session: PathfinderSession)`
Called when path is successfully calculated.

**Example:**
```kotlin
override fun onPathCalculated(session: PathfinderSession) {
    println("Path has ${session.waypoints.size} waypoints")
    // Access waypoints: session.waypoints
}
```

##### `onPathFailed(session: PathfinderSession, reason: String)`
Called when pathfinding fails.

**Common failure reasons:**
- "No path found to target" - Destination unreachable
- "Stuck detected" - Bot couldn't make progress

##### `onReachedDestination(session: PathfinderSession)`
Called when destination is reached.

##### `onProgress(session: PathfinderSession, currentWaypoint: Int, totalWaypoints: Int)`
*(Optional)* Called periodically with progress updates.

**Example:**
```kotlin
override fun onProgress(session: PathfinderSession, current: Int, total: Int) {
    val percent = (current.toDouble() / total * 100).toInt()
    println("$percent% complete")
}
```

---

### SimplePathfinderCallback Class

Convenient lambda-based callback implementation.

**Constructor:**
```kotlin
SimplePathfinderCallback(
    onCalculated: (PathfinderSession) -> Unit = {},
    onFailed: (PathfinderSession, String) -> Unit = { _, _ -> },
    onReached: (PathfinderSession) -> Unit = {},
    onProgressUpdate: (PathfinderSession, Int, Int) -> Unit = { _, _, _ -> }
)
```

**Example:**
```kotlin
val callback = SimplePathfinderCallback(
    onCalculated = { println("Started!") },
    onReached = { println("Done!") }
)
```

---

### PathfinderSession Data Class

Represents an active pathfinding session.

**Properties:**
- `id: UUID` - Unique session identifier
- `target: BlockPos` - Destination position
- `callback: PathfinderCallback` - Event callback
- `priority: Int` - Priority level
- `state: PathfinderState` - Current state
- `waypoints: List<BlockPos>` - Calculated path (empty until calculated)
- `createdAt: Long` - Creation timestamp (milliseconds)

**Example:**
```kotlin
val session = PathfinderAPI.getActiveSession()
println("Going to: ${session?.target}")
println("Progress: ${session?.waypoints?.size} waypoints")
```

---

### PathfinderState Enum

**States:**
- `QUEUED` - Waiting to be processed
- `CALCULATING` - Computing path
- `FOLLOWING_PATH` - Executing movement
- `REACHED_DESTINATION` - Completed successfully
- `FAILED` - Failed to complete
- `CANCELLED` - User cancelled

## Usage Examples

### Example 1: Sequential Waypoints

```kotlin
fun pathToMultipleLocations(waypoints: List<BlockPos>) {
    var index = 0
    
    fun goToNext() {
        if (index >= waypoints.size) return
        
        PathfinderAPI.requestPath(
            target = waypoints[index],
            callback = SimplePathfinderCallback(
                onReached = {
                    index++
                    goToNext() // Chain to next
                }
            )
        )
    }
    
    goToNext()
}
```

### Example 2: Priority System

```kotlin
// Low priority background task
PathfinderAPI.requestPath(
    target = farmLocation,
    priority = 1,
    callback = farmingCallback
)

// High priority urgent task (interrupts farming)
PathfinderAPI.requestPath(
    target = escapeLocation,
    priority = 10,
    callback = escapeCallback
)
```

### Example 3: Custom Callback Class

```kotlin
class MyPathfinderCallback(private val onComplete: () -> Unit) : PathfinderCallback {
    override fun onPathCalculated(session: PathfinderSession) {
        // Log waypoints
        session.waypoints.forEach { println("Waypoint: $it") }
    }
    
    override fun onPathFailed(session: PathfinderSession, reason: String) {
        // Retry logic
        println("Failed: $reason, retrying...")
    }
    
    override fun onReachedDestination(session: PathfinderSession) {
        onComplete()
    }
}

// Usage
PathfinderAPI.requestPath(
    target = destination,
    callback = MyPathfinderCallback {
        println("Custom completion logic")
    }
)
```

### Example 4: Session Management

```kotlin
// Track your session
val sessionId = PathfinderAPI.requestPath(target, callback)

// Check status later
val session = PathfinderAPI.getSession(sessionId)
when (session?.state) {
    PathfinderState.CALCULATING -> println("Still calculating...")
    PathfinderState.FOLLOWING_PATH -> println("On the way!")
    PathfinderState.REACHED_DESTINATION -> println("Arrived!")
    else -> println("Unknown state")
}
```

### Example 5: Emergency Stop

```kotlin
// User pressed panic button
fun emergencyStop() {
    PathfinderAPI.clearAll()
    println("All pathfinding stopped!")
}
```

## Advanced Usage

### Thread Safety

The API is designed for use from the Minecraft client thread. Do not call from other threads.

### Priority System

- Higher priority sessions preempt lower priority ones
- Equal priority: first-come, first-served
- When high priority completes, next highest priority resumes

### Session Lifecycle

```
QUEUED → CALCULATING → FOLLOWING_PATH → REACHED_DESTINATION
                ↓
              FAILED
```

Sessions can be cancelled at any time, transitioning to `CANCELLED` state.

### Error Handling

```kotlin
PathfinderAPI.requestPath(
    target = destination,
    callback = SimplePathfinderCallback(
        onFailed = { session, reason ->
            when (reason) {
                "No path found to target" -> {
                    // Try alternate destination
                }
                else -> {
                    // Log and abort
                }
            }
        }
    )
)
```

## Integration Tips

### With Commands

```kotlin
@Command("goto")
fun gotoCommand(x: Int, y: Int, z: Int) {
    PathfinderAPI.requestPath(
        target = BlockPos(x, y, z),
        callback = SimplePathfinderCallback(
            onReached = { println("Arrived at $x, $y, $z") }
        )
    )
}
```

### With Events

```kotlin
@EventHandler
fun onPlayerDamage(event: PlayerDamageEvent) {
    // Emergency escape
    val safeLocation = findSafeLocation()
    PathfinderAPI.requestPath(
        target = safeLocation,
        priority = 100, // Max priority
        callback = escapeCallback
    )
}
```

### With GUI

```kotlin
button.onClick {
    val targetPos = getClickedBlock()
    PathfinderAPI.requestPath(
        target = targetPos,
        callback = SimplePathfinderCallback(
            onCalculated = { updateGUI("Pathing...") },
            onReached = { updateGUI("Arrived!") }
        )
    )
}
```

## Performance Notes

- Path calculation happens asynchronously (doesn't block)
- Maximum 50,000 A* iterations per calculation
- Automatic stuck detection and recalculation
- Optimized for paths up to ~200 blocks

## Troubleshooting

### "No path found to target"
- Target is too far away
- Target is unreachable (blocked by walls, etc.)
- Target is in unloaded chunks

### Bot gets stuck
- Automatic recalculation triggers after 60 ticks
- Check terrain for unusual blocks

### Multiple sessions not working
- Only one session executes at a time
- Others queue based on priority
- Use `getAllSessions()` to debug queue

## License

Part of ExampleAddon - see LICENSE file.

## Support

For issues and questions:
- Check the examples in `PathfinderAPIExample.kt`
- Read the inline documentation
- Review the main pathfinder README

## Changelog

### Version 1.0.0
- Initial API release
- Session-based pathfinding
- Priority queue system
- Progress callbacks
- Full documentation

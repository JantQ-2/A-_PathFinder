# Using Pathfinder API in Your Mod

Quick guide for integrating the Pathfinder API into your own Fabric mod.

## Step 1: Add Dependency

Add to your `build.gradle.kts`:

```kotlin
repositories {
    // Add the repository where A# Pathfinding is published
    mavenLocal() // For local testing
    // OR
    // maven("https://your-maven-repo.com/releases")
}

dependencies {
    modImplementation("jant.path:ASharp_Pathfinding:1.0.0")
}
```

## Step 2: Basic Usage

In your mod code:

```kotlin
import com.example.api.PathfinderAPI
import com.example.api.SimplePathfinderCallback
import net.minecraft.util.math.BlockPos

class YourMod {
    fun startPathfinding(x: Int, y: Int, z: Int) {
        PathfinderAPI.requestPath(
            target = BlockPos(x, y, z),
            callback = SimplePathfinderCallback(
                onCalculated = { session ->
                    println("Path ready!")
                },
                onReached = { session ->
                    println("Arrived!")
                },
                onFailed = { session, reason ->
                    println("Failed: $reason")
                }
            )
        )
    }
}
```

## Step 3: Test Locally

1. Build A# Pathfinding:
   ```bash
   cd ASharp_Pathfinding
   ./gradlew publishToMavenLocal
   ```

2. In your mod, refresh Gradle dependencies

3. Use the API as shown above

## Available API Classes

All in package `com.example.api`:
- `PathfinderAPI` - Main entry point
- `PathfinderCallback` - Interface for events
- `SimplePathfinderCallback` - Lambda-based convenience class
- `PathfinderSession` - Session data
- `PathfinderState` - Enum of states

## Complete Example

```kotlin
package com.yourmod

import jant.path.api.PathfinderAPI
import jant.path.api.PathfinderCallback
import jant.path.api.PathfinderSession
import net.minecraft.util.math.BlockPos

class AutoMiner {
    private var currentSessionId: UUID? = null
    
    fun mineAt(pos: BlockPos) {
        currentSessionId = PathfinderAPI.requestPath(
            target = pos,
            priority = 5,
            callback = object : PathfinderCallback {
                override fun onPathCalculated(session: PathfinderSession) {
                    println("Moving to ore at ${session.target}")
                }
                
                override fun onPathFailed(session: PathfinderSession, reason: String) {
                    println("Can't reach ore: $reason")
                    findNextOre()
                }
                
                override fun onReachedDestination(session: PathfinderSession) {
                    println("Reached ore, starting mining")
                    startMining(session.target)
                }
            }
        )
    }
    
    fun cancelMining() {
        currentSessionId?.let { PathfinderAPI.cancelPath(it) }
    }
    
    private fun startMining(pos: BlockPos) {
        // Your mining logic
    }
    
    private fun findNextOre() {
        // Your ore finding logic
    }
}
```

## Need Help?

- Read the full API docs: `src/main/kotlin/com/example/api/README.md`
- Check examples: `src/main/kotlin/com/example/api/PathfinderAPIExample.kt`
- Test with command: `/apipath goto <x> <y> <z>`

## Publishing Your Mod

Remember to list A# Pathfinding as a dependency in your `fabric.mod.json`:

```json
{
  "depends": {
    "asharp_pathfinding": ">=1.0.0"
  }
}
```

## Performance Notes

- Only one pathfinding session runs at a time
- Others queue based on priority
- Automatic stuck detection and recovery
- Thread-safe from client thread only

## Common Patterns

### Sequential Navigation
```kotlin
val waypoints = listOf(pos1, pos2, pos3)
var index = 0

fun goToNext() {
    if (index >= waypoints.size) return
    PathfinderAPI.requestPath(waypoints[index++], callback)
}
```

### Priority Override
```kotlin
// Low priority task
PathfinderAPI.requestPath(farmPos, callback, priority = 1)

// Emergency task (interrupts farming)
PathfinderAPI.requestPath(safePos, callback, priority = 100)
```

### Session Tracking
```kotlin
val sessions = mutableMapOf<String, UUID>()

sessions["mining"] = PathfinderAPI.requestPath(orePos, callback)
sessions["base"] = PathfinderAPI.requestPath(basePos, callback, priority = -1)

// Cancel specific task
PathfinderAPI.cancelPath(sessions["mining"]!!)
```

## License

A# Pathfinding is licensed under CC0-1.0. You can use the API freely in your mod.

# Pathfinder API - Quick Reference

## For External Mod Developers

### 1-Minute Integration

```kotlin
// In build.gradle.kts
dependencies {
    modImplementation("jant.path:ASharp_Pathfinding:1.0.0")
}

// In your code
import jant.path.api.PathfinderAPI
import jant.path.api.SimplePathfinderCallback
import net.minecraft.util.math.BlockPos

fun pathTo(x: Int, y: Int, z: Int) {
    PathfinderAPI.requestPath(
        target = BlockPos(x, y, z),
        callback = SimplePathfinderCallback(
            onReached = { println("Arrived!") }
        )
    )
}
```

### Core API

```kotlin
// Start pathfinding
val sessionId = PathfinderAPI.requestPath(target, callback, priority = 0)

// Cancel
PathfinderAPI.cancelPath(sessionId)

// Check status
if (PathfinderAPI.isPathfinding()) { /* active */ }

// Get info
val session = PathfinderAPI.getActiveSession()
```

### Callback Options

**Simple (Lambda):**
```kotlin
SimplePathfinderCallback(
    onCalculated = { session -> /* path ready */ },
    onFailed = { session, reason -> /* error */ },
    onReached = { session -> /* complete */ },
    onProgressUpdate = { session, current, total -> /* % */ }
)
```

**Custom (Interface):**
```kotlin
object MyCallback : PathfinderCallback {
    override fun onPathCalculated(session: PathfinderSession) { }
    override fun onPathFailed(session: PathfinderSession, reason: String) { }
    override fun onReachedDestination(session: PathfinderSession) { }
}
```

### Common Patterns

**Sequential Waypoints:**
```kotlin
fun pathToAll(waypoints: List<BlockPos>) {
    var i = 0
    fun next() {
        if (i < waypoints.size) {
            PathfinderAPI.requestPath(waypoints[i++], SimplePathfinderCallback(onReached = { next() }))
        }
    }
    next()
}
```

**Emergency Override:**
```kotlin
PathfinderAPI.requestPath(safeLocation, callback, priority = 100)  // Interrupts everything
```

**Session Tracking:**
```kotlin
val mySession = PathfinderAPI.requestPath(target, callback)
// Later...
PathfinderAPI.getSession(mySession)?.state  // Check state
```

### Session States

- `QUEUED` → `CALCULATING` → `FOLLOWING_PATH` → `REACHED_DESTINATION`
- Can transition to `FAILED` or `CANCELLED` at any time

### In-Game Testing

```
/apipath goto <x> <y> <z>  - Test API pathfinding
/apipath cancel             - Stop pathfinding
/apipath status             - Check current session
```

### Full Documentation

- **API Reference**: `src/main/kotlin/com/example/api/README.md`
- **Examples**: `src/main/kotlin/com/example/api/PathfinderAPIExample.kt`
- **Integration Guide**: `USING_API.md`
- **Implementation Docs**: `src/main/kotlin/com/example/pathfinder/README.md`

### Support

Check the comprehensive examples in `PathfinderAPIExample.kt` for:
- Multiple waypoints
- Priority system
- Progress tracking
- Custom callbacks
- Error handling
- Session management

---

**That's it! You now have a fully functional pathfinding API.** 🚀

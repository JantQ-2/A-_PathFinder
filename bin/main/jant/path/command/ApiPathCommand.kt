package jant.path.command

import jant.path.api.PathfinderAPI
import jant.path.api.SimplePathfinderCallback
import net.minecraft.util.math.BlockPos
import org.cobalt.api.command.Command
import org.cobalt.api.command.annotation.DefaultHandler
import org.cobalt.api.command.annotation.SubCommand
import org.cobalt.api.util.ChatUtils

object ApiPathCommand : Command(
    name = "apipath",
    aliases = arrayOf("apath", "apf")
) {

    @DefaultHandler
    fun main() {
        ChatUtils.sendMessage("§7API Path commands: §fgoto <x> <y> <z>§7, §fcancel§7, §fstatus")
    }

    @SubCommand
    fun goto(x: Int, y: Int, z: Int) {
        val target = BlockPos(x, y, z)
        
        ChatUtils.sendMessage("§7Pathfinding to §f$x $y $z§7 using API...")
        
        PathfinderAPI.requestPath(
            target = target,
            callback = SimplePathfinderCallback(
                onCalculated = { session ->
                    ChatUtils.sendMessage("§aPath calculated: §f${session.waypoints.size}§a waypoints")
                },
                onFailed = { _, reason ->
                    ChatUtils.sendMessage("§cPath failed: §f$reason")
                },
                onReached = { session ->
                    val duration = System.currentTimeMillis() - session.createdAt
                    ChatUtils.sendMessage("§aReached destination in §f${duration}ms§a!")
                },
                onProgressUpdate = { _, current, total ->
                    if (current % 20 == 0) {
                        val percent = (current.toDouble() / total * 100).toInt()
                        ChatUtils.sendMessage("§7Progress: §f$percent%")
                    }
                }
            )
        )
    }

    @SubCommand
    fun cancel() {
        if (PathfinderAPI.isPathfinding()) {
            PathfinderAPI.clearAll()
            ChatUtils.sendMessage("§cAll pathfinding cancelled")
        } else {
            ChatUtils.sendMessage("§7No active pathfinding")
        }
    }

    @SubCommand
    fun status() {
        val session = PathfinderAPI.getActiveSession()
        if (session != null) {
            ChatUtils.sendMessage(
                "§7Active: §f${session.target} §7| State: §f${session.state} §7| " +
                "Waypoints: §f${session.waypoints.size}"
            )
        } else {
            ChatUtils.sendMessage("§7No active pathfinding")
        }
        
        val allSessions = PathfinderAPI.getAllSessions()
        if (allSessions.size > 1) {
            ChatUtils.sendMessage("§7Queued sessions: §f${allSessions.size - 1}")
        }
    }
}



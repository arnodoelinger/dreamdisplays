package com.dreamdisplays.platform.server.commands.subcommands

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender

/**
 * Sub-command that can be executed by a command sender. Each sub-command has a name, an optional permission requirement,
 * and its own execution logic.
 */
@PaperOnly
interface SubCommand {
    /** The name of the sub-command. */
    val name: String

    /** The permission required to execute the sub-command, or null if no permission is required. */
    val permission: String?

    /** Whether the sub-command can only be executed by players. */
    val playerOnly: Boolean get() = false

    /** Runs the subcommand for [sender] with the parsed [args] array. */
    fun execute(sender: CommandSender, args: Array<String?>)

    /** Returns tab-completion suggestions for [sender] given the current [args]. */
    fun complete(sender: CommandSender, args: Array<String?>): List<String> = emptyList()
}

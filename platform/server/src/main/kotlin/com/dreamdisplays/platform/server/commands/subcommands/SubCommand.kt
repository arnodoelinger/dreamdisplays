package com.dreamdisplays.platform.server.commands.subcommands

import io.github.arsmotorin.ofrat.PaperOnly
import org.bukkit.command.CommandSender

/**
 * Sub-command that can be executed by a command sender.
 * Each sub-command has a name, an optional permission requirement,
 * and an execute method that defines the command's behavior.
 *
 * @property name the name of the sub-command.
 * @property permission the permission required to execute the sub-command, or null if no permission is
 * required.
 * @function execute executes the sub-command with the given command sender and arguments.
 * @param sender the command sender executing the sub-command.
 * @param args the arguments passed to the sub-command.
 * @see CommandSender
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

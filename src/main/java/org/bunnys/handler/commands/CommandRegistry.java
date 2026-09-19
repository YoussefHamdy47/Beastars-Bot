package org.bunnys.handler.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live command table.
 *
 * <p>The parallel {@code BunnyMessageCommand} registry - a name map, an alias map, a
 * loader pass and a getter - has been removed. It had <b>zero</b> implementations in
 * this project, and with the Message Content intent gone it could never gain one:
 * prefix commands cannot be read at all. Mention invocations resolve against the same
 * {@link BunnyCommand} table slash commands use, so there is one source of truth for
 * what a command is.
 */
public class CommandRegistry {

    private final BunnyHub client;
    private final Map<String, BunnyCommand> commands = new ConcurrentHashMap<>();

    /**
     * Alias to command. Kept apart from {@link #commands} so the canonical table stays
     * one entry per command: the help menu, the ready banner and the command count all
     * walk it, and folding aliases in would have every one of them counting twice.
     */
    private final Map<String, BunnyCommand> aliases = new ConcurrentHashMap<>();

    /**
     * Read-only views, built once.
     *
     * <p>{@code Collections.unmodifiable*} allocates a fresh wrapper on every call, and
     * both of these are read on the hot path - {@link #getCommands} once per slash
     * command, per mention and per autocomplete keystroke, {@link #getDeveloperIds} once
     * per gate check. The wrapper is a view rather than a copy, so a single instance
     * still reflects later registrations.
     */
    private final Map<String, BunnyCommand> commandsView = Collections.unmodifiableMap(commands);

    private final List<String> developerIds;
    private final List<String> developerIdsView;
    private final List<String> testServerIds;

    public CommandRegistry(BunnyHub client, List<String> developerIds, List<String> testServerIds) {
        this.client = client;
        this.developerIds = developerIds;
        this.developerIdsView = Collections.unmodifiableList(developerIds);
        this.testServerIds = testServerIds;
    }

    public void registerCommand(BunnyCommand command) {
        if (command.getName() == null || command.getName().isEmpty()) {
            BunnyLog.warning("[CommandRegistry] Ignored a command with no name: " + command.getClass().getSimpleName());
            return;
        }

        String name = command.getName().toLowerCase();
        registerAliases(command, name);

        BunnyCommand existing = commands.put(name, command);

        // Commands are discovered by reflection, so two classes claiming one name is an
        // easy mistake and used to resolve silently in whatever order the scanner
        // happened to return them - one command simply never ran, with nothing said. The
        // registration still wins, because refusing it would be just as arbitrary; what
        // matters is that it stops being invisible.
        if (existing != null && existing != command)
            BunnyLog.warning("[CommandRegistry] Duplicate command name '" + name + "': "
                    + command.getClass().getSimpleName() + " replaced "
                    + existing.getClass().getSimpleName() + ". One of them will never run.");
    }

    /**
     * Records a command's aliases, refusing any that would be ambiguous.
     *
     * <p>An alias that collides with a real command name, or with an alias already
     * claimed by a different command, is dropped and logged rather than silently
     * shadowing whichever registered last. Reflective loading gives no ordering
     * guarantee, so "last one wins" would mean a convenience name that works or does not
     * depending on classpath order.
     */
    private void registerAliases(BunnyCommand command, String canonical) {
        for (String alias : command.getAliases()) {
            if (commands.containsKey(alias)) {
                BunnyLog.warning("[CommandRegistry] Alias '" + alias + "' on '" + canonical
                        + "' is already a command name. Ignored.");
                continue;
            }

            BunnyCommand claimed = aliases.putIfAbsent(alias, command);
            if (claimed != null && claimed != command)
                BunnyLog.warning("[CommandRegistry] Alias '" + alias + "' is claimed by '"
                        + claimed.getName() + "'; '" + canonical + "' cannot also use it.");
        }
    }

    /**
     * A command by its name or one of its aliases, or null.
     *
     * <p>The one lookup both listeners use. Slash interactions always arrive under the
     * canonical name and hit the first map; only the mention path reaches the second.
     */
    public BunnyCommand resolveCommand(String name) {
        if (name == null || name.isEmpty())
            return null;

        String key = name.toLowerCase();
        BunnyCommand direct = commands.get(key);

        return direct != null ? direct : aliases.get(key);
    }

    public void deployCommands() {
        List<CommandData> global = new ArrayList<>();
        Map<String, List<CommandData>> perGuild = new HashMap<>();

        for (BunnyCommand command : commands.values()) {
            if (command.isTestOnly())
                for (String guildId : testServerIds)
                    perGuild.computeIfAbsent(guildId, k -> new ArrayList<>()).add(command.buildCommandData());
            else
                global.add(command.buildCommandData());
        }

        client.getJDA().updateCommands().addCommands(global).queue(
                ok -> BunnyLog.success("[CommandRegistry] Registered " + global.size() + " global commands."),
                err -> BunnyLog.error("[CommandRegistry] Global deploy failed: " + err.getMessage()));

        perGuild.forEach((guildId, list) -> {
            Guild guild = client.getJDA().getGuildById(guildId);
            if (guild == null) {
                BunnyLog.warning("[CommandRegistry] Test guild " + guildId + " is not reachable; skipped.");
                return;
            }
            guild.updateCommands().addCommands(list).queue(
                    ok -> BunnyLog.success("[CommandRegistry] Registered " + list.size()
                            + " test commands to guild " + guildId + "."),
                    err -> BunnyLog.error("[CommandRegistry] Guild deploy failed for "
                            + guildId + ": " + err.getMessage()));
        });
    }

    public int clearCommands() {
        int count = commands.size();
        commands.clear();
        aliases.clear();
        return count;
    }

    public int getCommandCount() {
        return commands.size();
    }

    /**
     * The command table, read-only.
     *
     * <p>Was the live map. Every caller only reads it - the help menu, both listeners,
     * the autocomplete router - but handing out a mutable handle to the thing that
     * defines what the bot can do invites exactly one bug, and it would be a strange one
     * to track down.
     */
    public Map<String, BunnyCommand> getCommands() {
        return commandsView;
    }

    /** Read-only, for the same reason. */
    public List<String> getDeveloperIds() {
        return developerIdsView;
    }
}

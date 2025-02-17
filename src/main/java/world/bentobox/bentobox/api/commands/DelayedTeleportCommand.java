package world.bentobox.bentobox.api.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import space.arim.morepaperlib.scheduling.ScheduledTask;
import world.bentobox.bentobox.api.addons.Addon;
import world.bentobox.bentobox.api.user.User;

/**
 * BentoBox Delayed Teleport Command
 * Adds ability to require the player stays still for a period of time before a command is executed
 * @author tastybento
 */
public abstract class DelayedTeleportCommand extends CompositeCommand implements Listener {

    /**
     * User monitor map
     */
    private static final Map<UUID, DelayedCommand> toBeMonitored = new HashMap<>();

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        // Only check x,y,z
        if (toBeMonitored.containsKey(uuid) && !e.getTo().toVector().equals(toBeMonitored.get(uuid).location().toVector())) {
            moved(uuid);
        }
    }

    private void moved(UUID uuid) {
        // Player moved
        toBeMonitored.get(uuid).task().cancel();
        toBeMonitored.remove(uuid);
        // Player has another outstanding confirmation request that will now be cancelled
        User.getInstance(uuid).notify("commands.delay.moved-so-command-cancelled");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (toBeMonitored.containsKey(uuid)) {
            moved(uuid);
        }
    }


    /**
     * Top level command
     * @param addon - addon creating the command
     * @param label - string for this command
     * @param aliases - aliases
     */
    protected DelayedTeleportCommand(Addon addon, String label, String... aliases) {
        super(addon, label, aliases);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
    }

    /**
     * Command to register a command from an addon under a parent command (that could be from another addon)
     * @param addon - this command's addon
     * @param parent - parent command
     * @param aliases - aliases for this command
     */
    protected DelayedTeleportCommand(Addon addon, CompositeCommand parent, String label, String... aliases ) {
        super(addon, parent, label, aliases);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
    }

    /**
     *
     * @param parent - parent command
     * @param label - command label
     * @param aliases - command aliases
     */
    protected DelayedTeleportCommand(CompositeCommand parent, String label, String... aliases) {
        super(parent, label, aliases);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
    }

    /**
     * Tells user to stand still for a period of time before teleporting
     * @param user User to tell
     * @param message Optional message to send to the user to give them a bit more context. It must already be translated.
     * @param confirmed Runnable to be executed if successfully delayed.
     */
    public void delayCommand(User user, String message, Runnable confirmed) {
        if (getSettings().getDelayTime() < 1 || user.isOp() || user.hasPermission(getPermissionPrefix() + "mod.bypasscooldowns")
                || user.hasPermission(getPermissionPrefix() + "mod.bypassdelays")) {
            getPlugin().getMorePaperLib().scheduling().globalRegionalScheduler().run(confirmed);
            return;
        }
        // Check for pending delays
        UUID uuid = user.getUniqueId();
        if (toBeMonitored.containsKey(uuid)) {
            // A double request - clear out the old one
            toBeMonitored.get(uuid).task().cancel();
            toBeMonitored.remove(uuid);
            // Player has another outstanding confirmation request that will now be cancelled
            user.sendMessage("commands.delay.previous-command-cancelled");
        }
        // Send user the context message if it is not empty
        if (!message.trim().isEmpty()) {
            user.sendRawMessage(message);
        }
        // Tell user that they need to stand still
        user.sendMessage("commands.delay.stand-still", "[seconds]", String.valueOf(getSettings().getDelayTime()));
        // Set up the run task
        ScheduledTask task = getPlugin().getMorePaperLib().scheduling().globalRegionalScheduler().runDelayed( () -> {
            getPlugin().getMorePaperLib().scheduling().globalRegionalScheduler().run(toBeMonitored.get(uuid).runnable());
            toBeMonitored.remove(uuid);
        }, getPlugin().getSettings().getDelayTime() * 20L);

        // Add to the monitor
        toBeMonitored.put(uuid, new DelayedCommand(confirmed, task, user.getLocation()));
    }

    /**
     * Tells user to stand still for a period of time before teleporting
     * @param user User to monitor.
     * @param command Runnable to be executed if player does not move.
     */
    public void delayCommand(User user, Runnable command) {
        delayCommand(user, "", command);
    }

    /**
     * Holds the data to run once the confirmation is given
     *
     */
    private record DelayedCommand(Runnable runnable, ScheduledTask task, Location location) {}

}

package world.bentobox.bentobox.managers.island;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.util.Util;

/**
 * The default strategy for generating locations for island
 * @author tastybento, leonardochaia
 * @since 1.8.0
 *
 */
public class DefaultNewIslandLocationStrategy implements NewIslandLocationStrategy {

    /**
     * The amount times to tolerate island check returning blocks without known
     * island.
     */
    protected static final Integer MAX_UNOWNED_ISLANDS = 20;

    protected enum Result {
        ISLAND_FOUND, BLOCKS_IN_AREA, FREE
    }

    protected final BentoBox plugin = BentoBox.getInstance();

    @Override
    public CompletableFuture<Location> getNextLocation(World world) {
        Location last = plugin.getIslands().getLast(world);
        if (last == null) {
            last = new Location(world,
                    (double) plugin.getIWM().getIslandXOffset(world) + plugin.getIWM().getIslandStartX(world),
                    plugin.getIWM().getIslandHeight(world),
                    (double) plugin.getIWM().getIslandZOffset(world) + plugin.getIWM().getIslandStartZ(world));
        }

        CompletableFuture<Location> lastFuture = new CompletableFuture<>();

        Location finalLast = last;
        plugin.getMorePaperLib().scheduling().asyncScheduler().run(() -> {
            // Find a free spot
            Map<Result, Integer> result = new EnumMap<>(Result.class);
            // Check center
            CompletableFuture<Result> resultFuture = isIsland(finalLast);
            Result r = resultFuture.join();

            Location newLast = finalLast;
            while (!r.equals(Result.FREE) && result.getOrDefault(Result.BLOCKS_IN_AREA, 0) < MAX_UNOWNED_ISLANDS) {
                newLast = nextGridLocation(newLast);
                result.put(r, result.getOrDefault(r, 0) + 1);
                r = isIsland(newLast).join();
            }

            if (!r.equals(Result.FREE)) {
                // We could not find a free spot within the limit required. It's likely this
                // world is not empty
                plugin.logError("Could not find a free spot for islands! Is this world empty?");
                plugin.logError("Blocks around center locations: " + result.getOrDefault(Result.BLOCKS_IN_AREA, 0) + " max "
                                + MAX_UNOWNED_ISLANDS);
                plugin.logError("Known islands: " + result.getOrDefault(Result.ISLAND_FOUND, 0) + " max unlimited.");
                lastFuture.complete(null);
                return;
            }
            plugin.getIslands().setLast(newLast);
            lastFuture.complete(newLast);
        });

        return lastFuture;
    }

    /**
     * Checks if there is an island or blocks at this location
     *
     * @param location - the location
     * @return Result enum indicated what was found or not found
     */
    protected CompletableFuture<Result> isIsland(Location location) {

        CompletableFuture<Result> islandResult = new CompletableFuture<>();

        // Quick check
        if (plugin.getIslands().getIslandAt(location).isPresent()) {
            islandResult.complete(Result.ISLAND_FOUND);
            return islandResult;
        }

        World world = location.getWorld();

        // Check 4 corners
        int dist = plugin.getIWM().getIslandDistance(location.getWorld());
        Set<Location> locs = new HashSet<>();
        locs.add(location);

        locs.add(new Location(world, location.getX() - dist, 0, location.getZ() - dist));
        locs.add(new Location(world, location.getX() - dist, 0, location.getZ() + dist - 1));
        locs.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() - dist));
        locs.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() + dist - 1));

        CompletableFuture<Boolean> generatedFuture = new CompletableFuture<>();
        plugin.getMorePaperLib().scheduling().asyncScheduler().run(() -> {
            boolean generated = false;
            for (Location l : locs) {
                if (plugin.getIslands().getIslandAt(l).isPresent() || plugin.getIslandDeletionManager().inDeletion(l)) {
                    islandResult.complete(Result.ISLAND_FOUND);
                    return;
                }
                CompletableFuture<Boolean> result = new CompletableFuture<>();
                plugin.getMorePaperLib().scheduling().regionSpecificScheduler(l).run(() -> {
                    result.complete(Util.isChunkGenerated(l));
                });
                if (result.join()) generated = true;
            }
            generatedFuture.complete(generated);
        });

        generatedFuture.whenComplete((generated,throwable) -> {
            // If chunk has not been generated yet, then it's not occupied
            if (!generated) {
                islandResult.complete(Result.FREE);
                return;
            }
            plugin.getMorePaperLib().scheduling().regionSpecificScheduler(location).run(() -> {
                // Block check
                if (plugin.getIWM().isCheckForBlocks(world)
                    && !plugin.getIWM().isUseOwnGenerator(world)
                    && Arrays.stream(BlockFace.values()).anyMatch(bf ->
                        !location.getBlock().getRelative(bf).isEmpty()
                        && !location.getBlock().getRelative(bf).getType().equals(Material.WATER))) {
                    // Block found
                    plugin.getIslands().createIsland(location);
                    islandResult.complete(Result.BLOCKS_IN_AREA);
                    return;
                }
                islandResult.complete(Result.FREE);
            });
        });

        return islandResult;
    }

    /**
     * Finds the next free island spot based off the last known island Uses
     * island_distance setting from the config file Builds up in a grid fashion
     *
     * @param finalLastIsland - last island location
     * @return Location of next free island
     */
    private Location nextGridLocation(Location finalLastIsland) {
        Location lastIsland = finalLastIsland.clone();
        int x = lastIsland.getBlockX();
        int z = lastIsland.getBlockZ();
        int d = plugin.getIWM().getIslandDistance(lastIsland.getWorld()) * 2;
        if (x < z) {
            if (-1 * x < z) {
                lastIsland.setX(lastIsland.getX() + d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        if (x > z) {
            if (-1 * x >= z) {
                lastIsland.setX(lastIsland.getX() - d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() - d);
            return lastIsland;
        }
        if (x <= 0) {
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        lastIsland.setZ(lastIsland.getZ() - d);
        return lastIsland;
    }
}

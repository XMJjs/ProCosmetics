/*
 * This file is part of ProCosmetics - https://github.com/FilleDev/ProCosmetics
 * Copyright (C) 2025-2026 FilleDev and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package se.filledev.procosmetics.util.block;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.TechnicalPiston;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import se.filledev.procosmetics.ProCosmeticsPlugin;
import se.filledev.procosmetics.util.MetadataUtil;

import java.util.*;

public class FakeBlockManager {

    private static final double VIEW_RANGE_SQUARED = 64.0d * 64.0d;
    public static final long PERMANENT = -1L;

    /**
     * Materials that are never allowed to be replaced.
     */
    private static final Set<Material> BLOCKED_MATERIALS = EnumSet.of(
            Material.BARRIER,
            Material.CACTUS,
            Material.SLIME_BLOCK,
            Material.HONEY_BLOCK,
            Material.TNT,
            Material.GLOWSTONE,
            Material.NOTE_BLOCK,
            Material.CRAFTING_TABLE,
            Material.OBSERVER,
            Material.DIRT_PATH,
            Material.FARMLAND,
            Material.MAGMA_BLOCK,
            Material.REDSTONE_BLOCK,
            Material.REDSTONE_LAMP,
            Material.TARGET
    );

    /**
     * Material tags that are never allowed to be replaced.
     */
    private static final List<Tag<Material>> BLOCKED_TAGS = List.of(
            Tag.SLABS,
            Tag.STAIRS,
            Tag.DOORS,
            Tag.TRAPDOORS,
            Tag.BUTTONS,
            Tag.PRESSURE_PLATES,
            Tag.BEDS,
            Tag.ALL_SIGNS,
            Tag.BANNERS,
            Tag.WALLS,
            Tag.FENCES,
            Tag.FENCE_GATES,
            Tag.SHULKER_BOXES,
            Tag.ICE,
            Tag.WOOL,
            Tag.WOOL_CARPETS,
            Tag.LANTERNS,
            Tag.CANDLE_CAKES,
            Tag.CAULDRONS,
            Tag.ANVIL,
            Tag.IMPERMEABLE // all glass blocks, including stained variants
    );

    protected final ProCosmeticsPlugin plugin;

    private final Map<Block, TrackedBlock> trackedBlocks = new LinkedHashMap<>();
    private BukkitTask task;
    private long currentTick;

    public FakeBlockManager(ProCosmeticsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a fake block to nearby players and tracks it.
     *
     * @param block         the block to change client-side
     * @param blockData     the fake block data to display
     * @param overwrite     whether an already tracked fake block may be replaced
     * @param durationTicks how long the fake block lasts in ticks, or {@link #PERMANENT}
     * @return true if the fake block was set
     */
    public boolean setFakeBlock(Block block, BlockData blockData, boolean overwrite, long durationTicks) {
        if (!overwrite && isFakeBlock(block)) {
            return false;
        }
        if (!canSetFakeBlock(block)) {
            return false;
        }
        MetadataUtil.setCustomBlock(block);
        trackedBlocks.put(block, new TrackedBlock(blockData, durationTicks < 0
                ? PERMANENT
                : currentTick + durationTicks));
        broadcastBlockChange(block, blockData);
        startTaskIfNeeded();
        return true;
    }

    public boolean setFakeBlock(Block block, Material material, boolean overwrite, long durationTicks) {
        if (!material.isBlock()) {
            return false;
        }
        return setFakeBlock(block, material.createBlockData(), overwrite, durationTicks);
    }

    public boolean setFakeBlock(Block block, ItemStack itemStack, boolean overwrite, long durationTicks) {
        return setFakeBlock(block, itemStack.getType(), overwrite, durationTicks);
    }

    public boolean setFakeBlock(Block block, ItemStack itemStack, boolean overwrite) {
        return setFakeBlock(block, itemStack, overwrite, PERMANENT);
    }

    /**
     * Restores the original (server-side) block data for all players and
     * stops tracking the block.
     */
    public void resetFakeBlock(Block block) {
        if (trackedBlocks.remove(block) != null) {
            restore(block);
        }
        stopTaskIfIdle();
    }

    public void shutdown() {
        for (Block block : trackedBlocks.keySet()) {
            restore(block);
        }
        trackedBlocks.clear();
        stopTaskIfIdle();
    }

    /**
     * Re-sends all tracked fake blocks in range to the given player.
     */
    public void refresh(Player player) {
        Location playerLocation = player.getLocation();

        for (Map.Entry<Block, TrackedBlock> entry : trackedBlocks.entrySet()) {
            Block block = entry.getKey();

            if (block.getWorld().equals(player.getWorld())
                    && block.getLocation().distanceSquared(playerLocation) < VIEW_RANGE_SQUARED) {
                player.sendBlockChange(block.getLocation(), entry.getValue().blockData());
            }
        }
    }

    public boolean isFakeBlock(Block block) {
        return trackedBlocks.containsKey(block) || MetadataUtil.isCustomBlock(block);
    }

    public int getTrackedBlockCount() {
        return trackedBlocks.size();
    }

    /**
     * Checks whether a block is suitable for a client-side replacement.
     */
    public boolean canSetFakeBlock(Block block) {
        Material material = block.getType();

        if (!material.isBlock() || !material.isSolid() || !material.isOccluding()) {
            return false;
        }
        if (BLOCKED_MATERIALS.contains(material)) {
            return false;
        }
        for (Tag<Material> tag : BLOCKED_TAGS) {
            if (tag.isTagged(material)) {
                return false;
            }
        }
        BlockData blockData = block.getBlockData();

        if (blockData instanceof Openable // doors, trapdoors, fence gates
                || blockData instanceof Bed
                || blockData instanceof Powerable // buttons, levers, plates
                || blockData instanceof AnaloguePowerable
                || blockData instanceof Piston
                || blockData instanceof TechnicalPiston) {
            return false;
        }
        // Rejects every block entity: chests, furnaces, hoppers, dispensers,
        // droppers, beacons, brewing stands, jukeboxes, enchanting tables,
        // daylight detectors, etc.
        return !(block.getState() instanceof TileState);
    }

    private void tick() {
        currentTick++;
        Iterator<Map.Entry<Block, TrackedBlock>> iterator = trackedBlocks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Block, TrackedBlock> entry = iterator.next();

            if (entry.getValue().isExpired(currentTick)) {
                iterator.remove();
                restore(entry.getKey());
            }
        }
        stopTaskIfIdle();
    }

    private void startTaskIfNeeded() {
        if (task == null && !trackedBlocks.isEmpty()) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    private void stopTaskIfIdle() {
        if (task != null && trackedBlocks.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    private void restore(Block block) {
        broadcastBlockChange(block, block.getBlockData());
        MetadataUtil.removeCustomBlock(block);
    }

    private void broadcastBlockChange(Block block, BlockData blockData) {
        Location location = block.getLocation();
        Location tmp = location.clone();

        for (Player player : block.getWorld().getPlayers()) {
            if (player.getLocation(tmp).distanceSquared(location) < VIEW_RANGE_SQUARED) {
                player.sendBlockChange(location, blockData);
            }
        }
    }

    /**
     * State for a single tracked fake block.
     *
     * @param blockData  the fake data currently shown to players
     * @param expiryTick internal tick at which the block expires, or {@link #PERMANENT}
     */
    private record TrackedBlock(BlockData blockData, long expiryTick) {

        boolean isExpired(long currentTick) {
            return expiryTick != PERMANENT && currentTick >= expiryTick;
        }
    }
}

package com.oneshotonekill.arena;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.shared.ArenaShape;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The mod's available arena dimensions and their measured combat boundaries.
 */
@SuppressWarnings("unused")
public enum Arena {
    STANDARD("standard", "Standard", "Standard.zip", new Vec3(223.5, 48.0, 55.5),
            List.of(ArenaShape.polygon(58.0, 64.0, ArenaShape.Outlines.STANDARD)), 69.0, true, false),
    DUSTPVP("dustpvp", "DustPvP", "DustPvP.zip", new Vec3(0.5, 90.0, 0.5),
            List.of(ArenaShape.polygon(70.0, 70.0, ArenaShape.Outlines.DUSTPVP)), null, true, false),
    BO2("bo2", "BO2", "BO2.zip", new Vec3(-1045.5, 63.0, 352.5),
            List.of(ArenaShape.polygon(63.0, 81.0, ArenaShape.Outlines.BO2)), null, false, true),
    TILTED_TOWERS("tilted_towers", "Tilted Towers", "TiltedTowers.zip", new Vec3(-39.5, 37.0, 258.5),
            List.of(
                    ArenaShape.polygon(7.0, 35.0, ArenaShape.Outlines.TILTED_TOWERS),
                    ArenaShape.polygon(1.0, 35.0, ArenaShape.Outlines.TILTED_TOWERS_LOWER)
            ), 35.0, false, true);

    private static final Arena DEFAULT = STANDARD;
    private static final Map<String, Arena> BY_ID = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Arena::getId, arena -> arena));
    private static final Map<ResourceKey<Level>, Arena> BY_DIMENSION = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Arena::getDimension, arena -> arena));

    private final String id, displayName, archive;
    private final Vec3 lobby;
    private final List<ArenaShape> regions;
    private final Double ceilingY;
    private final boolean scoredRespawn, spawnOnAllLevels;
    private final Identifier dimensionId;
    private final ResourceKey<Level> dimension;

    Arena(String id, String displayName, String archive, Vec3 lobby, List<ArenaShape> regions,
          Double ceilingY, boolean scoredRespawn, boolean spawnOnAllLevels) {
        this.id = id;
        this.displayName = displayName;
        this.archive = archive;
        this.lobby = lobby;
        this.regions = regions;
        this.ceilingY = ceilingY;
        this.scoredRespawn = scoredRespawn;
        this.spawnOnAllLevels = spawnOnAllLevels;
        this.dimensionId = OneShotOneKill.INSTANCE.id(id);
        this.dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
    }

    public static Arena getDefault() {
        return DEFAULT;
    }

    public static Arena byId(String id) {
        return BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    public static Arena byDimension(ResourceKey<Level> dimension) {
        return dimension != null ? BY_DIMENSION.get(dimension) : null;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getArchive() {
        return archive;
    }

    public Vec3 getLobby() {
        return lobby;
    }

    public List<ArenaShape> getRegions() {
        return regions;
    }

    public Double getCeilingY() {
        return ceilingY;
    }

    public boolean getScoredRespawn() {
        return scoredRespawn;
    }

    public boolean getSpawnOnAllLevels() {
        return spawnOnAllLevels;
    }

    public Identifier getDimensionId() {
        return dimensionId;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public Component getTitle() {
        return Component.literal(displayName);
    }

    public boolean getHasCeiling() {
        return ceilingY != null;
    }

    public double getVoidRescueY() {
        return Math.min(regions.stream().mapToDouble(ArenaShape::getMinY).min().orElseThrow(), lobby.y) - 20.0;
    }

    public boolean isInArenaColumn(double x, double z) {
        return regions.stream().anyMatch(region -> region.containsColumn(x, z));
    }

    public boolean isInArena(double x, double y, double z) {
        return regions.stream().anyMatch(region ->
                region.containsColumn(x, z)
                        && y >= region.getMinY() - ArenaShape.ARENA_FLOOR_TOLERANCE
                        && (ceilingY == null || y <= ceilingY)
        );
    }

    public ArenaShape shapeAt(double x, double z) {
        for (ArenaShape shape : regions) {
            if (shape.containsColumn(x, z)) {
                return shape;
            }
        }
        return null;
    }

    public ArenaShape regionAt(double x, double y, double z) {
        ArenaShape best = null;
        double bestDist = Double.MAX_VALUE;
        for (ArenaShape shape : regions) {
            if (shape.containsColumn(x, z)) {
                double floor = shape.getMinY() - ArenaShape.ARENA_FLOOR_TOLERANCE;
                double ceil = ceilingY != null ? ceilingY : shape.getMaxY() + ArenaShape.ARENA_HEADROOM;
                if (y >= floor && y <= ceil) {
                    return shape;
                }
                double d = y < floor ? (floor - y) : (y - ceil);
                if (d < bestDist) {
                    bestDist = d;
                    best = shape;
                }
            }
        }
        if (best != null) {
            return best;
        }
        for (ArenaShape shape : regions) {
            if (shape.containsColumn(x, z)) {
                return shape;
            }
        }
        return regions.isEmpty() ? null : regions.getFirst();
    }

    public double getFloorY(double x, double y, double z) {
        double minFloor = Double.MAX_VALUE;
        for (ArenaShape shape : regions) {
            if (shape.containsColumn(x, z)) {
                double floor = shape.getMinY() - ArenaShape.ARENA_FLOOR_TOLERANCE;
                if (floor < minFloor) {
                    minFloor = floor;
                }
            }
        }
        if (minFloor != Double.MAX_VALUE) {
            return minFloor;
        }
        return regions.stream().mapToDouble(ArenaShape::getMinY).min().orElse(0.0) - ArenaShape.ARENA_FLOOR_TOLERANCE;
    }

    public double getFloorY(double x, double z) {
        return getFloorY(x, 0.0, z);
    }

    public double[] getInwardNormal(double x, double y, double z) {
        ArenaShape shape = regionAt(x, y, z);
        if (shape != null) {
            return shape.getInwardNormal(x, z);
        }
        return getInwardNormal(x, z);
    }

    public double[] getInwardNormal(double x, double z) {
        ArenaShape shape = shapeAt(x, z);
        if (shape != null) {
            return shape.getInwardNormal(x, z);
        }
        double bestDistSq = Double.MAX_VALUE;
        ArenaShape closest = null;
        for (ArenaShape s : regions) {
            double cx = (s.getMinX() + s.getMaxX()) * 0.5 - x;
            double cz = (s.getMinZ() + s.getMaxZ()) * 0.5 - z;
            double d = cx * cx + cz * cz;
            if (d < bestDistSq) {
                bestDistSq = d;
                closest = s;
            }
        }
        return closest != null ? closest.getInwardNormal(x, z) : new double[]{0.0, 0.0};
    }
}

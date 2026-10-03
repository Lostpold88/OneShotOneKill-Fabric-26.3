package com.oneshotonekill.arena;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.equipment.EquipmentManager;
import com.oneshotonekill.match.GunGameManager;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.ArenaShape;
import com.oneshotonekill.shared.OsokEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"RedundantCast", "RedundantTypeArguments", "SuspiciousIndentAfterControlStatement", "unused"})
public final class RandomTpSystem {
   public static final RandomTpSystem INSTANCE = new RandomTpSystem();
   private static final int GROUND_ITEM_SPAWN_ATTEMPTS = 200;
   /** Mindestabstand zweier Item-Boxen zueinander, in Blöcken. */
   private static final double MIN_ITEM_SPACING = 10.0;

   private RandomTpSystem() {
   }

   public Vec3 getRandomArenaLocation(Arena arena, MinecraftServer server) {
      List<Vec3> candidates = collectArenaSpots(arena, server, 8);
      return candidates.isEmpty()
         ? arena.getLobby()
         : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
   }

   public List<Vec3> collectArenaSpots(Arena arena, MinecraftServer server, int count) {
      ServerLevel level = server.getLevel(arena.getDimension());
      if (level == null || arena.getRegions().isEmpty()) {
         return List.of(arena.getLobby());
      }

      List<Vec3> spots = new ArrayList<>(count);
      int maximumAttempts = count * 15;
      for (int attempts = 0; spots.size() < count && attempts < maximumAttempts; attempts++) {
         ColumnSample sample = sampleRegionColumn(arena);
         if (sample == null) {
            continue;
         }
         ArenaShape region = sample.region();
         int x = sample.x(), z = sample.z();
         if (ArenaDemolition.INSTANCE.isRespawnBlocked(level, x + 0.5, z + 0.5)) {
            continue;
         }

         int minY = (int) region.getMinY();
         int maxY = (int) region.getMaxY();
         int startY = arena.getSpawnOnAllLevels()
            ? ThreadLocalRandom.current().nextInt(minY, maxY + 1)
            : maxY;
         for (int y = startY; y >= minY; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            BlockState ground = level.getBlockState(feet.below());
            if (!ground.isAir() && !ground.canBeReplaced() && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) {
               spots.add(new Vec3(x + 0.5, y, z + 0.5));
               break;
            }
         }
      }
      return spots.isEmpty() ? List.of(arena.getLobby()) : spots;
   }

   /**
    * Ein freier Standplatz für eine Item-Box – irgendwo auf der Karte, in jeder Höhe.
    * <p>
    * <p>Die Spalte wird über die volle Höhe abgesucht, die die Arena überhaupt als „drinnen"
    * gelten lässt: von {@code minY} abzüglich der Bodentoleranz bis {@code maxY} zuzüglich des
    * Kopfraums, also genau die Spanne aus {@link ArenaShape#contains}. Früher lag darüber
    * zusätzlich eine feste Obergrenze je Arena, und die schnitt bei zwei der drei Karten alles
    * oberhalb des Erdgeschosses ab – Dächer, Brücken und obere Stockwerke blieben leer, obwohl
    * dort gekämpft wird. Wo die Karte ein Dach hat – auf Standard –,
    * bleibt die Suche darunter.</p>
    * <p>
    * <p>Aus allen tragfähigen Höhen einer Spalte wird eine ausgewürfelt und nicht die unterste
    * genommen: sonst gewönne auf einer mehrstöckigen Karte immer der Boden.</p>
    * <p>
    * @param occupied Stellen, an denen bereits eine Box steht. Die neue hält Abstand dazu –
    *                 zwei Boxen nebeneinander sind ein Fund, nicht zwei.
    */
   public Vec3 findGroundItemLocation(Arena arena, ServerLevel level, List<Vec3> occupied) {
      for (int attempt = 0; attempt < GROUND_ITEM_SPAWN_ATTEMPTS; attempt++) {
         // Findet sich in der Mehrzahl der Versuche kein Platz mit vollem Abstand, wird er
         // gegen Ende gelockert. Lieber eine Box etwas zu nah als gar keine.
         double spacing = attempt < GROUND_ITEM_SPAWN_ATTEMPTS * 3 / 4
            ? MIN_ITEM_SPACING
            : MIN_ITEM_SPACING / 2.0;

         ColumnSample sample = sampleRegionColumn(arena);
         if (sample == null) {
            continue;
         }
         ArenaShape region = sample.region();
         int x = sample.x(), z = sample.z();

         int minY = Math.max((int) (region.getMinY() - ArenaShape.ARENA_FLOOR_TOLERANCE), level.getMinY());
         int maxY = Math.min((int) (region.getMaxY() + ArenaShape.ARENA_HEADROOM), level.getMaxY());
         if (arena.getHasCeiling()) {
            // Die Standard-Karte ist überdacht: Zwei Blöcke unter der Decke ist die höchste
            // Stelle, an der eine Box noch samt ihrem Schwebeabstand hineinpasst.
            maxY = Math.min(maxY, (int) (double) arena.getCeilingY() - 2);
         }
         List<Integer> levels = new ArrayList<>();
         for (int y = minY; y <= maxY; y++) {
            BlockPos groundPos = new BlockPos(x, y, z);
            if (isValidGroundItemPosition(level, groundPos, level.getBlockState(groundPos), level.getBlockState(groundPos.above()))) {
               levels.add(y);
            }
         }
         if (levels.isEmpty()) {
            continue;
         }

         // Erst hier die Höhe wählen: Der Abstand zählt räumlich, ein Stockwerk höher ist
         // eine andere Stelle, auch wenn die Spalte dieselbe ist.
         java.util.Collections.shuffle(levels, ThreadLocalRandom.current());
         for (int chosen : levels) {
            Vec3 candidate = new Vec3(x + 0.5, chosen + 1.0, z + 0.5);
            if (keepsDistance(candidate, occupied, spacing)) {
               return candidate;
            }
         }
      }
      return null;
   }

   private static boolean keepsDistance(Vec3 candidate, List<Vec3> occupied, double spacing) {
      double squared = spacing * spacing;
      for (Vec3 taken : occupied) {
         if (taken.distanceToSqr(candidate) < squared) {
            return false;
         }
      }
      return true;
   }

   private ArenaShape randomRegion(Arena arena) {
      double totalFootprint = arena.getRegions().stream().mapToDouble(ArenaShape::getFootprint).sum();
      double selector = ThreadLocalRandom.current().nextDouble(totalFootprint);
      for (ArenaShape region : arena.getRegions()) {
         selector -= region.getFootprint();
         if (selector <= 0.0) {
            return region;
         }
      }
      return arena.getRegions().getLast();
   }

   public record ColumnSample(ArenaShape region, int x, int z) {}

   private @Nullable ColumnSample sampleRegionColumn(Arena arena) {
      ArenaShape region = randomRegion(arena);
      int x = ThreadLocalRandom.current().nextInt((int) region.getMinX(), (int) region.getMaxX() + 1);
      int z = ThreadLocalRandom.current().nextInt((int) region.getMinZ(), (int) region.getMaxZ() + 1);
      return region.containsColumn(x + 0.5, z + 0.5) ? new ColumnSample(region, x, z) : null;
   }

   private boolean isValidGroundItemPosition(ServerLevel level, BlockPos groundPos, BlockState ground, BlockState itemSpace) {
      if (!ground.isCollisionShapeFullBlock((BlockGetter) level, groundPos)
         || !ground.getFluidState().isEmpty()
         || isBlockedSpawnGround(ground)
         || itemSpace.is(Blocks.BARRIER)
         || (!itemSpace.isAir() && !itemSpace.canBeReplaced())
         || !itemSpace.getFluidState().isEmpty()) {
         return false;
      }
      // Ausreichend Freiraum nach oben (mindestens 2 Blöcke Luft, keine Barrieren)
      BlockPos aboveHead = groundPos.above(2);
      BlockState headSpace = level.getBlockState(aboveHead);
      if (headSpace.is(Blocks.BARRIER) || (!headSpace.isAir() && !headSpace.canBeReplaced())) {
         return false;
      }
      // Horizontale Nachbarblöcke prüfen: Box nicht direkt an Ecken/Wandnischen quetschen
      BlockPos itemPos = groundPos.above();
      int wallCount = 0;
      for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
         BlockPos neighbor = itemPos.relative(dir);
         if (level.getBlockState(neighbor).isCollisionShapeFullBlock(level, neighbor)) {
            wallCount++;
         }
      }
      return wallCount <= 1;
   }

   private boolean isBlockedSpawnGround(BlockState state) {
      // 26.2 fasst farbige Bloecke in ColorCollection zusammen; BLACK_WOOL gibt es nicht mehr.
      return state.is(Blocks.BARRIER)
         || state.is(Blocks.WOOL.black())
         || state.is(Blocks.BRICKS)
         || state.is(Blocks.BRICK_SLAB)
         || state.is(Blocks.BRICK_STAIRS)
         || state.is(Blocks.BRICK_WALL)
         || state.is(Blocks.STONE_BRICKS)
         || state.is(Blocks.STONE_BRICK_SLAB)
         || state.is(Blocks.STONE_BRICK_STAIRS)
         || state.is(Blocks.STONE_BRICK_WALL)
         || state.is(Blocks.NETHER_BRICKS)
         || state.is(Blocks.NETHER_BRICK_SLAB)
         || state.is(Blocks.NETHER_BRICK_STAIRS)
         || state.is(Blocks.NETHER_BRICK_WALL);
   }

    public void checkVoidRescue(ServerPlayer player, Arena arena) {
        if (player.getY() >= arena.getVoidRescueY()) {
            return;
        }

        MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
        if (server == null) {
            return;
        }

        ServerLevel level = server.getLevel(arena.getDimension());
        if (level == null) {
            level = player.level();
        }
        Vec3 location = getRandomArenaLocation(arena, server);
        player.teleportTo(level, location.x, location.y, location.z, Set.of(), player.getYRot(), player.getXRot(), false);
        player.fallDistance = 0.0;
        if (MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING) {
            // Der Modus entscheidet über die Ausrüstung: im Waffenspiel bleibt es die Stufenwaffe.
            MatchManager.INSTANCE.equipPlayerForCurrentMode(player);
        } else {
            EquipmentManager.INSTANCE.clearBaseEquipment(player);
        }
    }


   
   
   /** Chooses fair spawn points and performs the instant arena respawn. */
    public static final class RespawnSystem {
        public static final RespawnSystem INSTANCE = new RespawnSystem();
        private static final int RESPAWN_CANDIDATES = 10;
       private static final double ENEMY_DISTANCE_CAP = 32.0;
       private static final double DEATH_DISTANCE_CAP = 24.0;
   
       private RespawnSystem() { }
   
       public Vec3 getSafestArenaLocation(Arena arena, MinecraftServer server, ServerPlayer respawningPlayer, Vec3 deathPos) {
           if (!arena.getScoredRespawn()) return RandomTpSystem.INSTANCE.getRandomArenaLocation(arena, server);
           List<Vec3> candidates = RandomTpSystem.INSTANCE.collectArenaSpots(arena, server, RESPAWN_CANDIDATES);
           if (candidates.isEmpty()) return arena.getLobby();
           List<Vec3> enemies = collectEnemyPositions(arena, server, respawningPlayer);
           return candidates.stream().max(java.util.Comparator.comparingDouble(candidate -> rateSpawn(candidate, deathPos, enemies)))
               .orElseGet(() -> candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size())));
       }
   
       /**
        * Setzt den Spieler sofort neu – die eine Stelle, durch die jeder Tod läuft.
        * <p>
        * <p>{@code afterDeath} trennt einen echten Tod vom blossen Versetzen beim Beitritt oder
        * Match-Start: nur ein Tod hinterlässt Partikel am Sterbeort und einen Ton beim Gestorbenen.
        * Der Ton kommt bewusst <em>nach</em> dem Teleport und hängt am Spieler, denn die Spawnwahl
        * sucht absichtlich einen Punkt weit weg vom Sterbeort – am alten Ort abgespielt wäre er
        * unhörbar.
        */
       public void respawnInstant(ServerPlayer player, Arena arena, Vec3 deathPos, boolean afterDeath) {
           MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
           if (server == null) return;
           ServerLevel level = server.getLevel(arena.getDimension());
           if (level == null) level = player.level();
           com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.clearFor(player);
           if (afterDeath) OsokEffects.INSTANCE.playEliminationEffect(level, deathPos);
            Vec3 target = getSafestArenaLocation(arena, server, player, deathPos);
            restorePlayer(player);
            player.teleportTo(level, target.x, target.y, target.z, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
            if (MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING) {
                if (MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME) {
                    GunGameManager.INSTANCE.giveTierEquipment(player);
                    GunGameManager.INSTANCE.syncStatus(player, false);
                } else {
                    EquipmentManager.INSTANCE.giveOneShotEquipment(player);
                }
            } else {
                EquipmentManager.INSTANCE.clearBaseEquipment(player);
            }
            if (afterDeath) OsokEffects.INSTANCE.playDeathSound(player);
            server.execute(() -> restorePlayer(player));
       }
   
       public void respawnInstant(ServerPlayer player, Arena arena, Vec3 deathPos) { respawnInstant(player, arena, deathPos, false); }
   
       private double rateSpawn(Vec3 candidate, Vec3 deathPos, List<Vec3> enemies) {
           double nearestEnemy = ENEMY_DISTANCE_CAP;
           for (Vec3 enemy : enemies) nearestEnemy = Math.min(nearestEnemy, candidate.distanceTo(enemy));
           double enemyScore = nearestEnemy / ENEMY_DISTANCE_CAP;
           double deathScore = deathPos == null ? 1.0 : Math.min(candidate.distanceTo(deathPos), DEATH_DISTANCE_CAP) / DEATH_DISTANCE_CAP;
           return enemyScore * 0.7 + deathScore * 0.3;
       }
   
       private List<Vec3> collectEnemyPositions(Arena arena, MinecraftServer server, ServerPlayer respawningPlayer) {
           ServerLevel level = server.getLevel(arena.getDimension());
           if (level == null) return List.of();
           return level.players().stream()
               .filter(player -> respawningPlayer == null || !player.getUUID().equals(respawningPlayer.getUUID()))
               .map(ServerPlayer::position)
               .filter(position -> arena.isInArena(position.x, position.y, position.z))
               .toList();
       }
   
       private static void restorePlayer(ServerPlayer player) {
           player.setHealth(player.getMaxHealth());
           player.setDeltaMovement(Vec3.ZERO);
           player.hurtTime = 0;
           player.setInvulnerableTime(0);
           player.fallDistance = 0.0f;
       }
   }
}

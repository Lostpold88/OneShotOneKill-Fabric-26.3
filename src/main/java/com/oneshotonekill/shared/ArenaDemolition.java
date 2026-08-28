package com.oneshotonekill.shared;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaSnapshot;

import com.oneshotonekill.OneShotOneKill;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Krater schlagen und wieder zuwachsen lassen.
 *
 * Die Mod rührt die Karte sonst nirgends an – alles Abgestellte ist eine Display-Entity, und
 * Explosionen laufen über die eigene Eliminierungs-Buchführung statt über {@code Level#explode}.
 * Diese Klasse ist die eine bewusste Ausnahme: Luftangriff und Bomber dürfen Löcher reißen, weil
 * jedes Loch hier gleich mitsamt seinem Weg zurück verwaltet wird. Der ursprüngliche Zustand
 * jedes Blocks wird gemerkt und nach einer Wartezeit tickweise wieder eingesetzt.
 *
 * Vorher stand das nur im Luftangriff. Für den Bomber ein zweites Mal zu schreiben hieße, zwei
 * Buchhaltungen darüber zu führen, was der Karte gerade fehlt – und die erste vergessene
 * Wiederherstellung beschädigt die Arena dauerhaft.
 */
public final class ArenaDemolition {
   public static final ArenaDemolition INSTANCE = new ArenaDemolition();

   private static final int RESTORE_BLOCKS_PER_TICK = 24;
   /** So lange nach einem Einschlag bleiben Gegenstände im Krater verschwunden. */
   private static final int DROP_SUPPRESSION_TICKS = 20;

   /**
    * Die unversehrte Karte je Arena, abgeschrieben beim Serverstart.
    *
    * Sie ist der einzige Weg, der im laufenden Betrieb zuverlaessig zurueckfuehrt – siehe
    * {@link ArenaSnapshot}, warum weder das Kartenarchiv noch die Buchfuehrung unten dafuer
    * reichen.
    */
   private final Map<Arena, ArenaSnapshot> snapshots = new EnumMap<>(Arena.class);

   private final List<Restoration> restorations = new ArrayList<>();
   private final List<DropSuppression> dropSuppressions = new ArrayList<>();

   /** Zählt jede Veränderung der Karte, damit zwischengespeicherte Geländescans sich erneuern. */
   private int terrainRevision;

   private ArenaDemolition() {
   }

   public int getTerrainRevision() {
      return terrainRevision;
   }

   /**
    * Sprengt eine Kugel aus der Arena und meldet sie zur Wiederherstellung an.
    *
    * @param impact       Einschlagpunkt an der Oberfläche
    * @param radius       Kraterradius in Blöcken
    * @param depthOffset  wie weit die Kugelmitte unter dem Einschlag liegt – erst dadurch wird
    *                     aus der Kugel eine Schüssel statt eines Lochs mit Überhang
    * @param restoreDelay Ticks bis zum Zuwachsen
    * @return true, wenn wirklich etwas zerstört wurde
    */
   public boolean detonate(ServerLevel level, Arena arena, Vec3 impact, int radius, double depthOffset,
                           int restoreDelay, int currentTick) {
      clearGroundItems(level, impact, radius);
      dropSuppressions.add(new DropSuppression(level, impact, radius, currentTick + DROP_SUPPRESSION_TICKS));

      Map<BlockPos, BlockState> destroyed = carve(level, arena, impact, radius, depthOffset);
      clearGroundItems(level, impact, radius);
      if (destroyed.isEmpty()) {
         return false;
      }

      restorations.add(new Restoration(level, currentTick + restoreDelay, destroyed, impact, radius));
      terrainRevision++;
      return true;
   }

   /**
    * Radiert einen Ring um den Einschlag aus – schlägt eine gewaltige, unregelmäßige
    * Kraterschüssel mit geschmolzenem Kern (Lava, Magma, Obsidian) und Brandherden.
    * Pulverisiert Bedrock, Dächer, Gebäude und Außengelände organisch.
    *
    * @return wie viele Blöcke verschwunden sind
    */
   public int obliterate(ServerLevel level, Arena arena, Vec3 centre, double innerRadius, double outerRadius,
                         int restoreDelay, int currentTick) {
      int from = (int) Math.floor(centre.x - outerRadius);
      int to = (int) Math.ceil(centre.x + outerRadius);
      int fromZ = (int) Math.floor(centre.z - outerRadius);
      int toZ = (int) Math.ceil(centre.z + outerRadius);
      double innerSquared = innerRadius * innerRadius;
      double outerSquared = outerRadius * outerRadius;
      Map<BlockPos, BlockState> destroyed = new LinkedHashMap<>();
      Map<BlockPos, BlockState> moltenPlacements = new LinkedHashMap<>();

      // Krater-Schüsselprofil: Bis ~48 Blöcke Radius eine gewaltige, tiefe Schüssel bis in den tiefsten Bedrock
      double craterRadius = 48.0;
      double maxDepth = Math.max(26.0, centre.y - level.getMinY() - 1);

      for (int x = from; x <= to; x++) {
         for (int z = fromZ; z <= toZ; z++) {
            double distanceX = x + 0.5 - centre.x;
            double distanceZ = z + 0.5 - centre.z;
            double distance = distanceX * distanceX + distanceZ * distanceZ;
            if (distance < innerSquared || distance > outerSquared) {
               continue;
            }

            double dist = Math.sqrt(distance);
            // Organische, unregelmäßige Kraterränder und Schuttwellen (Multi-Octave Noise)
            double angle = Math.atan2(distanceZ, distanceX);
            double noise = Math.sin(angle * 3.0 + 0.4) * 4.0 + Math.cos(angle * 7.0 + 1.3) * 2.8 + Math.sin(angle * 13.0) * 1.5;
            double noiseOuter = Math.sin(x * 0.25 + z * 0.17) * 3.2 + Math.cos(x * 0.13 - z * 0.29) * 2.4;
            double effectiveDist = dist + noise;

            int lower;
            if (effectiveDist <= craterRadius) {
               // Parabolische Kraterschüssel bis tief in den Untergrund / Bedrock
               double depthShare = 1.0 - (effectiveDist / craterRadius) * (effectiveDist / craterRadius);
               lower = (int) Math.max(level.getMinY(), Math.floor(centre.y - maxDepth * depthShare));
            } else {
               // Außerhalb des Zentralkraters: Zerklüftetes, ungleichmäßiges Zerstörungsmuster
               double surfaceDip = Math.max(-1.0, 3.5 + noiseOuter);
               lower = (int) Math.max(level.getMinY(), Math.floor(centre.y - surfaceDip));
            }

            int upper = (int) Math.min(level.getMaxY(), Math.ceil(centre.y + 48.0 + noiseOuter));

            for (int y = lower; y <= upper; y++) {
               BlockPos pos = new BlockPos(x, y, z);
               BlockState state = level.getBlockState(pos);
               // Zerstört ALLES – inklusive Bedrock, Barrieren, Dächern und Gebäuden
               if (!state.isAir()) {
                  destroyed.put(pos, state);
               }
            }

            // --- Apokalyptische Glutherde, Lava, Magma und Feuer am Kraterboden & Wänden ---
            if (dist <= 26.0) {
               // Epizentrum: Magma- & Lavaseen im geschmolzenen Kraterkern
               BlockPos floorPos = new BlockPos(x, lower, z);
               BlockPos aboveFloor = floorPos.above();
               double floorHash = Math.sin(x * 12.9898 + z * 78.233) * 43758.5453;
               double rand = floorHash - Math.floor(floorHash);

               if (rand < 0.28) {
                  // Lavaseen im tiefsten Kessel
                  moltenPlacements.put(floorPos, Blocks.LAVA.defaultBlockState());
               } else if (rand < 0.65) {
                  // Glühender Magmaboden
                  moltenPlacements.put(floorPos, Blocks.MAGMA_BLOCK.defaultBlockState());
               } else if (rand < 0.88) {
                  // Erstarrtes Obsidian & Basalt
                  moltenPlacements.put(floorPos, Blocks.OBSIDIAN.defaultBlockState());
               } else {
                  moltenPlacements.put(floorPos, Blocks.BASALT.defaultBlockState());
               }

               // Feuer auf Magma/Obsidian
               if (rand > 0.38 && rand < 0.72 && !moltenPlacements.containsKey(aboveFloor)) {
                  moltenPlacements.put(aboveFloor, Blocks.FIRE.defaultBlockState());
               }
            } else if (dist <= craterRadius + 16.0) {
               // Kraterwände & Schuttbereich: Vereinzelte Glutnester und Feuer
               BlockPos floorPos = new BlockPos(x, lower, z);
               BlockPos aboveFloor = floorPos.above();
               double floorHash = Math.sin(x * 15.123 + z * 93.456) * 43758.5453;
               double rand = floorHash - Math.floor(floorHash);

               if (rand < 0.14) {
                  moltenPlacements.put(floorPos, Blocks.MAGMA_BLOCK.defaultBlockState());
                  if (rand < 0.08) {
                     moltenPlacements.put(aboveFloor, Blocks.FIRE.defaultBlockState());
                  }
               } else if (rand < 0.22) {
                  moltenPlacements.put(floorPos, Blocks.CRYING_OBSIDIAN.defaultBlockState());
               } else if (rand < 0.38) {
                  moltenPlacements.put(aboveFloor, Blocks.FIRE.defaultBlockState());
               }
            }
         }
      }

      for (BlockPos pos : destroyed.keySet()) {
         level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
      }

      // Setze geschmolzene Kraterblöcke (Lava, Magma, Obsidian, Feuer)
      for (Map.Entry<BlockPos, BlockState> molten : moltenPlacements.entrySet()) {
         BlockPos pos = molten.getKey();
         if (!destroyed.containsKey(pos)) {
            BlockState oldState = level.getBlockState(pos);
            if (!oldState.isAir()) {
               destroyed.put(pos, oldState);
            }
         }
         level.setBlock(pos, molten.getValue(), Block.UPDATE_CLIENTS);
      }

      if (!destroyed.isEmpty()) {
         restorations.add(new Restoration(level, currentTick + restoreDelay, destroyed, centre, (int) outerRadius));
         terrainRevision++;
      }
      return destroyed.size();
   }

   /**
    * Nimmt die Abschrift einer Arena ab – gedacht fuer den Moment nach dem Auspacken.
    *
    * Ein zweiter Aufruf ueberschreibt die vorhandene. Das ist Absicht: Wer sie erneuert,
    * erklaert damit den aktuellen Zustand zum Sollzustand, und genau das will man nach einem
    * frischen Auspacken.
    */
   public void takeSnapshot(ServerLevel level, Arena arena) {
      ArenaSnapshot snapshot = ArenaSnapshot.capture(level, arena);
      if (snapshot == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Von {} liess sich keine Abschrift nehmen; "
            + "ein Reset kann dort nur zurueckbauen, was die Mod selbst zerstoert hat.", arena.getId());
         return;
      }
      snapshots.put(arena, snapshot);
      OneShotOneKill.INSTANCE.getLOGGER().info("Abschrift von {}: {} Zellen, {} verschiedene Bloecke.",
         arena.getId(), snapshot.cellCount(), snapshot.paletteSize());
   }

   /**
    * Setzt eine Arena auf ihren Sollzustand zurueck – ganz gleich, was ihr zugestossen ist.
    *
    * <p>Das ist der Weg, den der Reset-Knopf geht. Er fragt nicht nach der Ursache: TNT,
    * Nuke, Luftangriff oder ein Spieler mit Spitzhacke enden alle hier, weil verglichen wird
    * und nicht Buch gefuehrt.</p>
    *
    * <p>Fehlt die Abschrift, bleibt der alte Weg ueber die Buchfuehrung. Der deckt weniger ab,
    * ist aber besser als nichts.</p>
    *
    * @return wie viele Bloecke geaendert wurden
    */
   public int restoreArena(ServerLevel level, Arena arena) {
      restorations.clear();
      dropSuppressions.clear();
      terrainRevision++;

      ArenaSnapshot snapshot = snapshots.get(arena);
      if (snapshot == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Keine Abschrift von {} vorhanden.", arena.getId());
         return 0;
      }
      int changed = snapshot.restore(level);
      OneShotOneKill.INSTANCE.getLOGGER().info("Arena {} zurueckgesetzt: {} Bloecke geaendert.",
         arena.getId(), changed);
      return changed;
   }

   public void tick(int currentTick) {
      restoreDestroyedBlocks(currentTick);
      dropSuppressions.removeIf(suppression -> currentTick >= suppression.expiresAt);
   }

   /** Niemand soll in ein Loch spawnen, das gleich wieder zuwächst. */
   public boolean isRespawnBlocked(ServerLevel level, double x, double z) {
      for (Restoration restoration : restorations) {
         if (restoration.level != level) {
            continue;
         }
         double deltaX = x - restoration.impact.x;
         double deltaZ = z - restoration.impact.z;
         double exclusion = restoration.radius + 1.0;
         if (deltaX * deltaX + deltaZ * deltaZ <= exclusion * exclusion) {
            return true;
         }
      }
      return false;
   }

   /**
    * Ein Krater darf keine Blöcke als Gegenstände zurücklassen – sonst läge nach jedem
    * Einschlag die halbe Karte als Beute herum.
    */
   public boolean suppressesItemDrop(ServerLevel level, ItemEntity item) {
      for (DropSuppression suppression : dropSuppressions) {
         if (suppression.level != level) {
            continue;
         }
         double reach = suppression.radius + 1.0;
         double x = item.getX() - suppression.impact.x;
         double y = item.getY() - suppression.impact.y;
         double z = item.getZ() - suppression.impact.z;
         if (x * x + z * z <= reach * reach && Math.abs(y) <= reach) {
            return true;
         }
      }
      return false;
   }

   /**
    * Füllt alles Offene sofort auf, statt auf die Wartezeit zu warten.
    *
    * Beim Serverstart gerufen: ein halb gesprengter Krater darf nicht stehen bleiben, nur weil
    * niemand mehr auf seine Wiederherstellung wartet.
    */
   public int restoreEverythingNow() {
      int restored = 0;
      for (Restoration restoration : restorations) {
         while (restoration.blocks.hasNext()) {
            Map.Entry<BlockPos, BlockState> block = restoration.blocks.next();
            restoration.level.setBlock(block.getKey(), block.getValue(), 2 | 16);
            restored++;
         }
      }
      OneShotOneKill.INSTANCE.getLOGGER().info("Karte zurueckgebaut: {} Bloecke aus {} Eintraegen.",
         restored, restorations.size());
      restorations.clear();
      dropSuppressions.clear();
      terrainRevision++;
      return restored;
   }

   private Map<BlockPos, BlockState> carve(ServerLevel level, Arena arena, Vec3 impact, int radius, double depthOffset) {
      Map<BlockPos, BlockState> destroyed = new LinkedHashMap<>();
      Vec3 craterCentre = impact.add(0.0, -depthOffset, 0.0);

      for (int x = (int) Math.floor(impact.x) - radius; x <= (int) Math.floor(impact.x) + radius; x++) {
         for (int z = (int) Math.floor(impact.z) - radius; z <= (int) Math.floor(impact.z) + radius; z++) {
            ArenaShape shape = shapeAt(arena, x + 0.5, z + 0.5);
            if (shape == null) {
               continue;
            }
            // Der Krater liegt um den tatsächlichen Einschlag herum – trifft die Bombe ein Dach,
            // wird das Dach gesprengt und nicht der Boden darunter.
            int lower = Math.max(level.getMinY(), (int) Math.floor(craterCentre.y - radius));
            int upper = Math.min((int) Math.ceil(craterCentre.y + radius), (int) Math.floor(shape.getMaxY()) + 6);
            if (arena.getHasCeiling()) {
               upper = Math.min(upper, (int) Math.floor(arena.getCeilingY()) - 2);
            }
            for (int y = lower; y <= upper; y++) {
               double distanceX = x + 0.5 - craterCentre.x;
               double distanceY = y + 0.5 - craterCentre.y;
               double distanceZ = z + 0.5 - craterCentre.z;
               if (distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ > radius * radius) {
                  continue;
               }
               BlockPos pos = new BlockPos(x, y, z);
               BlockState state = level.getBlockState(pos);
               if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                  destroyed.put(pos, state);
               }
            }
         }
      }

      for (BlockPos pos : destroyed.keySet()) {
         level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
      }
      return destroyed;
   }

   private void clearGroundItems(ServerLevel level, Vec3 impact, int radius) {
      double reach = radius + 1.0;
      AABB blastArea = new AABB(impact.x - reach, impact.y - reach, impact.z - reach,
         impact.x + reach, impact.y + reach, impact.z + reach);
      for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, blastArea)) {
         item.discard();
      }
   }

   private void restoreDestroyedBlocks(int currentTick) {
      Iterator<Restoration> iterator = restorations.iterator();
      while (iterator.hasNext()) {
         Restoration restoration = iterator.next();
         if (currentTick < restoration.restoreAt) {
            continue;
         }

         // Das Gelände wächst tickweise zurück – zwischengespeicherte Scans müssen mitlaufen.
         terrainRevision++;
         for (int restored = 0; restored < RESTORE_BLOCKS_PER_TICK && restoration.blocks.hasNext(); restored++) {
            Map.Entry<BlockPos, BlockState> block = restoration.blocks.next();
            restoration.level.setBlock(block.getKey(), block.getValue(), 2 | 16);
            BlockPos pos = block.getKey();
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            restoration.level.sendParticles(ParticleTypes.END_ROD, x, y, z, 3, 0.0, 0.0, 0.0, 0.02);
            restoration.level.sendParticles(ParticleTypes.ENCHANT, x, y + 0.15, z, 4, 0.0, 0.0, 0.0, 0.06);
            restoration.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 2, 0.0, 0.0, 0.0, 0.03);
         }
         if (!restoration.blocks.hasNext()) {
            iterator.remove();
         }
      }
   }

   /** Wird pro Kraterspalte aufgerufen – daher bewusst ohne Stream. */
   private static ArenaShape shapeAt(Arena arena, double x, double z) {
      List<ArenaShape> regions = arena.getRegions();
      for (int index = 0; index < regions.size(); index++) {
         ArenaShape shape = regions.get(index);
         if (shape.containsColumn(x, z)) {
            return shape;
         }
      }
      return null;
   }

   private record DropSuppression(ServerLevel level, Vec3 impact, int radius, int expiresAt) {
   }

   private static final class Restoration {
      private final ServerLevel level;
      private final int restoreAt;
      private final Vec3 impact;
      private final int radius;
      private final Iterator<Map.Entry<BlockPos, BlockState>> blocks;

      private Restoration(ServerLevel level, int restoreAt, Map<BlockPos, BlockState> destroyed, Vec3 impact, int radius) {
         this.level = level;
         this.restoreAt = restoreAt;
         this.impact = impact;
         this.radius = radius;
         // Von innen nach außen und von unten nach oben – so wächst der Krater sichtbar zu,
         // statt an zufälligen Stellen zu flackern.
         this.blocks = destroyed.entrySet().stream()
            .sorted(Comparator.comparingDouble((Map.Entry<BlockPos, BlockState> entry) -> horizontalDistance(entry.getKey(), impact))
               .thenComparingInt(entry -> entry.getKey().getY()))
            .iterator();
      }

      private static double horizontalDistance(BlockPos pos, Vec3 impact) {
         double x = pos.getX() + 0.5 - impact.x;
         double z = pos.getZ() + 0.5 - impact.z;
         return x * x + z * z;
      }
   }
}

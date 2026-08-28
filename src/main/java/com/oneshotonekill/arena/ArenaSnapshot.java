package com.oneshotonekill.arena;
import com.oneshotonekill.shared.ArenaShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Eine vollständige Abschrift einer Arena – die Karte, wie sie sein soll.
 *
 * <h2>Warum es das gibt</h2>
 *
 * <p>Der Rückweg über das Kartenarchiv sieht richtig aus und ist es nicht. Ein Reset packt die
 * Regionsdateien sauber neu aus – nachgemessen, sie sind danach byteweise mit dem Archiv
 * identisch –, aber der Server hält die Chunks derselben Dimension im Arbeitsspeicher und liest
 * sie nie wieder von der Platte. Der Spieler steht anschließend in genau der Karte, die er
 * kaputtgemacht hat, während auf der Platte die heile liegt. Eine Möglichkeit, geladene Chunks
 * von außen zu verwerfen, gibt es nicht.</p>
 *
 * <p>Die zweite Buchführung – aufschreiben, was zerstört wird – deckt nur ab, was die Mod selbst
 * zerstört. Wer mit TNT arbeitet, geht durch {@code Level#explode} und kommt dort nie vorbei.</p>
 *
 * <p>Diese Abschrift kennt beides nicht. Sie hält den Sollzustand und schreibt ihn zurück, ohne
 * zu fragen, wie der Istzustand zustande kam.</p>
 *
 * <h2>Wie sie gespeichert wird</h2>
 *
 * <p>Als Palette und Indexfeld, nicht als Karte aus Positionen. Eine Arena hat je nach Karte
 * dreißig- bis vierhunderttausend Zellen; als {@code HashMap<BlockPos, BlockState>} wären das
 * zweistellige Megabyte, als {@code short[]} über die Palette sind es unter einem. Die größte
 * der drei Karten braucht damit 0,9 MB – wenig genug, um sie dauerhaft zu halten.</p>
 *
 * <p>Gespeichert wird der ganze Quader, nicht nur das Polygon der Kampfzone. Die paar Prozent
 * Luft an den Rändern kosten nichts und ersparen die Frage, was mit Schaden knapp außerhalb der
 * Zone geschieht.</p>
 */
public final class ArenaSnapshot {
   /** Darüber passt kein Index mehr in ein {@code short}; dann wird nicht abgeschrieben. */
   private static final int MAX_PALETTE = Short.MAX_VALUE;
   /** Großzügiger Puffer um Regionen und Lobby, damit die gesamte Karte und Nuke-Krater erfasst werden. */
   private static final int MARGIN_HORIZONTAL = 64;
   private static final int MARGIN_BELOW = 32;
   private static final int MARGIN_ABOVE = 32;

   private final int minX;
   private final int minY;
   private final int minZ;
   private final int sizeX;
   private final int sizeY;
   private final int sizeZ;
   private final BlockState[] palette;
   private final short[] cells;

   private ArenaSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                         BlockState[] palette, short[] cells) {
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.sizeX = sizeX;
      this.sizeY = sizeY;
      this.sizeZ = sizeZ;
      this.palette = palette;
      this.cells = cells;
   }

   /**
    * Liest die gesamte Karte (Kampfbereich, Lobby, Dächer, Bedrock und weite Umgebung) ein.
    *
    * <p>Aufgerufen wird das unmittelbar nach dem Auspacken beim Serverstart – der eine
    * Zeitpunkt, an dem die Karte nachweislich unversehrt ist. Später wäre jede Abschrift nur
    * so gut wie der Zustand, den sie vorfindet.</p>
    *
    * @return {@code null}, wenn keine Regionen existieren oder die Palette überläuft
    */
   public static ArenaSnapshot capture(ServerLevel level, Arena arena) {
      double regMinX = arena.getRegions().stream().mapToDouble(ArenaShape::getMinX).min().orElse(arena.getLobby().x);
      double regMaxX = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxX).max().orElse(arena.getLobby().x);
      double regMinZ = arena.getRegions().stream().mapToDouble(ArenaShape::getMinZ).min().orElse(arena.getLobby().z);
      double regMaxZ = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxZ).max().orElse(arena.getLobby().z);
      double regMinY = arena.getRegions().stream().mapToDouble(ArenaShape::getMinY).min().orElse(arena.getLobby().y);
      double regMaxY = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxY).max().orElse(arena.getLobby().y);

      Vec3 lobby = arena.getLobby();
      int minX = (int) Math.floor(Math.min(regMinX, lobby.x)) - MARGIN_HORIZONTAL;
      int maxX = (int) Math.ceil(Math.max(regMaxX, lobby.x)) + MARGIN_HORIZONTAL;
      int minZ = (int) Math.floor(Math.min(regMinZ, lobby.z)) - MARGIN_HORIZONTAL;
      int maxZ = (int) Math.ceil(Math.max(regMaxZ, lobby.z)) + MARGIN_HORIZONTAL;
      int minY = level.getMinY(); // Erfasst die gesamte Welt bis zum tiefsten Bedrock-Boden
      int maxY = (int) Math.ceil(Math.max(regMaxY, lobby.y)) + MARGIN_ABOVE;

      if (arena.getHasCeiling()) {
         maxY = Math.max(maxY, (int) Math.ceil(arena.getCeilingY()) + 6);
      }
      maxY = Math.min(maxY, level.getMaxY());

      int sizeX = maxX - minX + 1;
      int sizeY = maxY - minY + 1;
      int sizeZ = maxZ - minZ + 1;

      Map<BlockState, Short> lookup = new HashMap<>();
      List<BlockState> palette = new ArrayList<>();
      short[] cells = new short[sizeX * sizeY * sizeZ];
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

      int index = 0;
      for (int x = 0; x < sizeX; x++) {
         for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
               cursor.set(minX + x, minY + y, minZ + z);
               BlockState state = level.getBlockState(cursor);
               Short known = lookup.get(state);
               if (known == null) {
                  if (palette.size() >= MAX_PALETTE) {
                     return null;
                  }
                  known = (short) palette.size();
                  palette.add(state);
                  lookup.put(state, known);
               }
               cells[index++] = known;
            }
         }
      }
      return new ArenaSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ,
         palette.toArray(new BlockState[0]), cells);
   }

   /**
    * Schreibt die Abschrift zurück – ohne Rücksicht darauf, was sie vorfindet.
    *
    * <p>Gesetzt wird nur, was abweicht. Das ist nicht bloß schneller: Ein Reset auf einer
    * unbeschädigten Karte schickt damit kein einziges Paket, und einer nach einem einzelnen
    * TNT-Block genau so viele, wie der Krater groß war. Ein stumpfes Überschreiben aller
    * vierhunderttausend Zellen wäre für den Server und die Leitung dasselbe wie eine neue
    * Karte.</p>
    *
    * <p>Ohne Nachbarschaftsprüfung ({@code Block.UPDATE_CLIENTS}): Beim Zurückbauen einer
    * ganzen Karte wäre die Kettenreaktion aus fallendem Sand und brechenden Fackeln teurer als
    * das Setzen selbst – und das Ergebnis stünde ohnehin fest.</p>
    *
    * @return wie viele Blöcke geändert wurden
    */
   public int restore(ServerLevel level) {
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      int changed = 0;
      int index = 0;

      for (int x = 0; x < sizeX; x++) {
         for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
               BlockState wanted = palette[cells[index++]];
               cursor.set(minX + x, minY + y, minZ + z);
               if (level.getBlockState(cursor) != wanted) {
                  level.setBlock(cursor, wanted, Block.UPDATE_CLIENTS);
                  changed++;
               }
            }
         }
      }
      return changed;
   }

   /** Wie viele Zellen die Abschrift umfasst – für die Meldung im Serverlog. */
   public int cellCount() {
      return cells.length;
   }

   public int paletteSize() {
      return palette.length;
   }
}

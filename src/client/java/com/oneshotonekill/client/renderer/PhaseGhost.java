package com.oneshotonekill.client.renderer;

import com.oneshotonekill.shared.PhaseFields;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Merkt sich, welchen Block der Abschnittsbauer gerade zeichnet und ob er in einer Phasen-Kugel liegt.
 * <p>
 * Abschnitte entstehen auf Arbeitsfäden, und der Block, der Vertexpuffer und die Seitenprüfung
 * liegen in verschiedenen Klassen ohne gemeinsamen Parameter. Der Zustand hängt deshalb am Faden.
 * Er wird in {@code PhaseSectionCompilerMixin} gesetzt und gelöscht.
 */
public final class PhaseGhost {
   /** Deckkraft der Blöcke in der Kugel, 0..255. */
   public static final int ALPHA = 70;

   private static final int OUTSIDE = 0;
   private static final int SOLID = 1;
   private static final int GHOST = 2;

   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private PhaseGhost() {
   }

   /** Zugriff des Puffers auf sein Merkmal; implementiert von {@code PhaseBufferBuilderMixin}. */
   public interface Builder {
      void osok$setGhost(boolean ghost);
   }

   public static void enter(BlockPos pos) {
      State state = STATE.get();
      state.pos.set(pos);
      state.mode = PhaseFields.CLIENT.covers(pos) ? GHOST : SOLID;
   }

   public static void leave() {
      STATE.get().mode = OUTSIDE;
   }

   public static boolean isGhost() {
      return STATE.get().mode == GHOST;
   }

   /**
    * Grenzfläche zwischen Kugel und Umgebung: sie muss gezeichnet werden, auch wo Vanilla sie
    * als verdeckt weglässt. Zwischen zwei Blöcken derselben Seite bleibt alles beim Alten – sonst
    * läge in der Kugel Fläche über Fläche und sie würde wieder undurchsichtig.
    */
   public static boolean forcesFace(Direction direction) {
      State state = STATE.get();
      if (state.mode == OUTSIDE) {
         return false;
      }
      state.neighbour.setWithOffset(state.pos, direction);
      return (state.mode == GHOST) != PhaseFields.CLIENT.covers(state.neighbour);
   }

   public static int fade(int argb) {
      return ((argb >>> 24) * ALPHA / 255) << 24 | (argb & 0x00FFFFFF);
   }

   private static final class State {
      private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
      private int mode = OUTSIDE;
   }
}

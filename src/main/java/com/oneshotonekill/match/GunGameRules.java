package com.oneshotonekill.match;

import java.util.Collection;
import java.util.List;

/**
 * Die reine Rechenlogik des Waffenspiels, ohne jede Minecraft-Abhängigkeit.
 * <p>
 * Aufstieg, Rangfolge, Gleichstand, Wiederholungsschutz und Blickwinkel stehen hier, damit sie
 * sich ohne Spielstart prüfen lassen. {@link GunGameTier} und {@link GunGameManager} rufen nur auf.
 */
public final class GunGameRules {
   /** Derselbe Gegner zählt nur einmal je 3 Sekunden (verhindert Doppelzählung desselben Treffers). */
   public static final int REPEAT_GUARD_TICKS = 60;
   /** Ab diesem Winkel zwischen Blickrichtung und Richtung zum Angreifer gilt ein Opfer als abgewandt. */
   public static final double BACK_TURN_DEGREES = 100.0;
   private static final double BACK_TURN_COS = Math.cos(Math.toRadians(BACK_TURN_DEGREES));

   private GunGameRules() {
   }

   public enum Step {
      PROGRESS,
      ADVANCE,
      WIN
   }

   /** Ergebnis eines gezählten Kills: wie es weitergeht und welchen Stand der Spieler danach hat. */
   public record Outcome(Step step, int tier, int kills) {
   }

   /** Der Stand eines Spielers für die Rangfolge. */
   public record Standing(int tier, int progress, int kills) {
   }

   public static Outcome advance(int tier, int kills, int required, int totalTiers) {
      int next = kills + 1;
      if (next < required) {
         return new Outcome(Step.PROGRESS, tier, next);
      }
      if (tier >= totalTiers) {
         return new Outcome(Step.WIN, tier, required);
      }
      return new Outcome(Step.ADVANCE, tier + 1, 0);
   }

   /** Wer später beitritt, steigt auf der niedrigsten Stufe ein, auf der gerade jemand spielt. */
   public static int lowestActiveTier(Collection<Integer> tiers) {
      int lowest = Integer.MAX_VALUE;
      for (int tier : tiers) {
         lowest = Math.min(lowest, tier);
      }
      return lowest == Integer.MAX_VALUE ? 1 : Math.max(1, lowest);
   }

   /** Positiv, wenn {@code a} vor {@code b} liegt: erst Stufe, dann Fortschritt, dann Kills. */
   public static int compare(Standing a, Standing b) {
      if (a.tier() != b.tier()) {
         return Integer.compare(a.tier(), b.tier());
      }
      if (a.progress() != b.progress()) {
         return Integer.compare(a.progress(), b.progress());
      }
      return Integer.compare(a.kills(), b.kills());
   }

   /** Rang 1 ist der Führende. Wer genau gleichauf liegt, teilt sich den Rang. */
   public static int rankOf(Standing mine, Collection<Standing> all) {
      int rank = 1;
      for (Standing other : all) {
         if (compare(other, mine) > 0) {
            rank++;
         }
      }
      return rank;
   }

   public static int leaderTier(Collection<Standing> all) {
      int best = 1;
      for (Standing standing : all) {
         best = Math.max(best, standing.tier());
      }
      return best;
   }

   /**
    * Kein Sieger, wenn niemand etwas erreicht hat oder die beiden Besten völlig gleichauf liegen.
    *
    * @param sortedBestFirst die Stände, bester zuerst
    */
   public static boolean isDraw(List<Standing> sortedBestFirst) {
      if (sortedBestFirst.isEmpty()) {
         return true;
      }
      Standing top = sortedBestFirst.get(0);
      if (top.tier() <= 1 && top.progress() == 0 && top.kills() == 0) {
         return true;
      }
      return sortedBestFirst.size() > 1 && compare(top, sortedBestFirst.get(1)) == 0;
   }

   public static boolean isRepeat(Integer lastCountedTick, int nowTick) {
      return lastCountedTick != null && nowTick - lastCountedTick < REPEAT_GUARD_TICKS;
   }

   /**
    * Schaut das Opfer mehr als 100 Grad an der Richtung zum Angreifer vorbei?
    * Die ersten drei Werte sind die Blickrichtung des Opfers, die letzten drei die Richtung
    * vom Opfer zum Angreifer.
    */
   public static boolean isBackTurned(double lookX, double lookY, double lookZ,
                                      double toKillerX, double toKillerY, double toKillerZ) {
      double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
      double directionLength = Math.sqrt(toKillerX * toKillerX + toKillerY * toKillerY + toKillerZ * toKillerZ);
      if (lookLength < 1.0E-6 || directionLength < 1.0E-6) {
         return false;
      }
      double cosine = (lookX * toKillerX + lookY * toKillerY + lookZ * toKillerZ) / (lookLength * directionLength);
      return cosine < BACK_TURN_COS;
   }
}

package com.oneshotonekill.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oneshotonekill.match.GunGameRules.Outcome;
import com.oneshotonekill.match.GunGameRules.Standing;
import com.oneshotonekill.match.GunGameRules.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Prüft die reine Rechenlogik des Waffenspiels, ohne Minecraft zu starten. */
class GunGameRulesTest {
   private static final int TOTAL = 23;

   @Test
   void killBelowRequirementIsProgress() {
      Outcome outcome = GunGameRules.advance(1, 0, 2, TOTAL);
      assertEquals(Step.PROGRESS, outcome.step());
      assertEquals(1, outcome.tier());
      assertEquals(1, outcome.kills());
   }

   @Test
   void lastKillOfATierAdvancesAndResetsProgress() {
      Outcome outcome = GunGameRules.advance(4, 1, 2, TOTAL);
      assertEquals(Step.ADVANCE, outcome.step());
      assertEquals(5, outcome.tier());
      assertEquals(0, outcome.kills());
   }

   @Test
   void singleKillTierAdvancesImmediately() {
      Outcome outcome = GunGameRules.advance(20, 0, 1, TOTAL);
      assertEquals(Step.ADVANCE, outcome.step());
      assertEquals(21, outcome.tier());
   }

   @Test
   void finishingTheLastTierWins() {
      Outcome outcome = GunGameRules.advance(TOTAL, 0, 1, TOTAL);
      assertEquals(Step.WIN, outcome.step());
      assertEquals(TOTAL, outcome.tier());
      assertEquals(1, outcome.kills());
   }

   @Test
   void lateJoinerStartsOnTheLowestActiveTier() {
      assertEquals(1, GunGameRules.lowestActiveTier(List.of()));
      assertEquals(3, GunGameRules.lowestActiveTier(List.of(5, 3, 9)));
      assertEquals(1, GunGameRules.lowestActiveTier(List.of(1, 12)));
   }

   @Test
   void tierBeatsProgressBeatsKills() {
      Standing higherTier = new Standing(5, 0, 0);
      Standing moreProgress = new Standing(4, 2, 0);
      Standing moreKills = new Standing(4, 1, 9);
      assertTrue(GunGameRules.compare(higherTier, moreProgress) > 0);
      assertTrue(GunGameRules.compare(moreProgress, moreKills) > 0);
      assertTrue(GunGameRules.compare(moreKills, higherTier) < 0);
      assertEquals(0, GunGameRules.compare(moreKills, new Standing(4, 1, 9)));
   }

   @Test
   void tiedPlayersShareTheirRank() {
      Standing leader = new Standing(8, 1, 10);
      Standing tiedA = new Standing(5, 0, 4);
      Standing tiedB = new Standing(5, 0, 4);
      Standing last = new Standing(2, 1, 1);
      List<Standing> all = List.of(leader, tiedA, tiedB, last);
      assertEquals(1, GunGameRules.rankOf(leader, all));
      assertEquals(2, GunGameRules.rankOf(tiedA, all));
      assertEquals(2, GunGameRules.rankOf(tiedB, all));
      assertEquals(4, GunGameRules.rankOf(last, all));
      assertEquals(8, GunGameRules.leaderTier(all));
   }

   @Test
   void emptyMatchOrNoProgressIsADraw() {
      assertTrue(GunGameRules.isDraw(List.of()));
      assertTrue(GunGameRules.isDraw(List.of(new Standing(1, 0, 0), new Standing(1, 0, 0))));
   }

   @Test
   void tieAtTheTopIsADraw() {
      assertTrue(GunGameRules.isDraw(List.of(new Standing(6, 1, 5), new Standing(6, 1, 5), new Standing(2, 0, 0))));
   }

   @Test
   void clearLeaderIsNotADraw() {
      assertFalse(GunGameRules.isDraw(List.of(new Standing(6, 1, 5), new Standing(6, 0, 5))));
      assertFalse(GunGameRules.isDraw(List.of(new Standing(1, 1, 1))));
   }

   @Test
   void repeatGuardCoversTwentySeconds() {
      Integer neverKilled = null;
      assertFalse(GunGameRules.isRepeat(neverKilled, 1000));
      assertTrue(GunGameRules.isRepeat(1000, 1000 + GunGameRules.REPEAT_GUARD_TICKS - 1));
      assertFalse(GunGameRules.isRepeat(1000, 1000 + GunGameRules.REPEAT_GUARD_TICKS));
   }

   @Test
   void victimLookingAtTheKillerIsNotBackTurned() {
      // Blick entlang +X, der Angreifer steht vor dem Opfer.
      assertFalse(GunGameRules.isBackTurned(1, 0, 0, 5, 0, 0));
   }

   @Test
   void victimLookingAwayIsBackTurned() {
      assertTrue(GunGameRules.isBackTurned(1, 0, 0, -5, 0, 0));
   }

   @Test
   void backTurnThresholdIsAHundredDegrees() {
      // 99 Grad zwischen Blick und Angreifer: noch nicht abgewandt. 101 Grad: abgewandt.
      assertFalse(GunGameRules.isBackTurned(1, 0, 0, Math.cos(Math.toRadians(99)), 0, Math.sin(Math.toRadians(99))));
      assertTrue(GunGameRules.isBackTurned(1, 0, 0, Math.cos(Math.toRadians(101)), 0, Math.sin(Math.toRadians(101))));
      // Genau seitlich (90 Grad) ist nicht abgewandt.
      assertFalse(GunGameRules.isBackTurned(1, 0, 0, 0, 0, 5));
   }

   @Test
   void zeroVectorsAreNeverBackTurned() {
      assertFalse(GunGameRules.isBackTurned(0, 0, 0, 1, 0, 0));
      assertFalse(GunGameRules.isBackTurned(1, 0, 0, 0, 0, 0));
   }
}

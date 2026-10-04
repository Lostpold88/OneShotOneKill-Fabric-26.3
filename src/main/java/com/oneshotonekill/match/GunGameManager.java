package com.oneshotonekill.match;

import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.item.runtime.BoogieBombSystem;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.ThrownDevices;
import com.oneshotonekill.match.GunGameRules.Outcome;
import com.oneshotonekill.match.GunGameRules.Standing;
import com.oneshotonekill.network.OsokPayloads.GunGameStatusPayload;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.OsokEffects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Verwaltet Ablauf, Fortschritt, Ausrüstung und Nachschub des Waffenspiels (Gun Game).
 * <p>
 * Die Stufen selbst stehen in {@link GunGameTier}, die reine Rechenlogik in {@link GunGameRules}.
 * Ein Kill wird in zwei Schritten behandelt: {@link #evaluate} misst ihn gegen die Stufe, solange
 * Opfer und Spezial-Systeme noch nicht aufgeräumt sind, {@link #applyKill} schreibt das Ergebnis fort.
 */
@SuppressWarnings({"ConstantValue", "resource", "unused"})
public final class GunGameManager {
   public static final GunGameManager INSTANCE = new GunGameManager();

   /** So hoch darf ein Spieler über dem Boden liegen, damit wir die Höhe noch messen. */
   private static final double HEIGHT_PROBE = 8.0;
   /** Der HUD-Zustand wird alle paar Ticks gegen die Fenster der Stufe geprüft. */
   private static final int HUD_CHECK_TICKS = 5;

   private final Map<UUID, Integer> playerTiers = new HashMap<>();
   private final Map<UUID, Integer> playerTierKills = new HashMap<>();
   private final Map<UUID, Integer> resupplyCounters = new HashMap<>();
   private final Map<UUID, Map<UUID, Integer>> lastCountedKill = new HashMap<>();
   private final Map<UUID, Boolean> lastWindowState = new HashMap<>();
   /** Stufe des Täters beim ersten Kill eines Ticks: Mehrfachopfer eines Schlags messen alle dagegen. */
   private final Map<UUID, TierSnapshot> tickTiers = new HashMap<>();
   private final Set<UUID> announcedFinalTier = new HashSet<>();
   private UUID leader;
   /** Gesetzt, sobald jemand die letzte Stufe geschafft hat: ab dann kommt kein HUD mehr zurück. */
   private boolean finished;

   private GunGameManager() {
   }

   /** Das Urteil über einen Abschuss. */
   public record Verdict(Kind kind, int tier) {
      public static final Verdict IGNORE = new Verdict(Kind.IGNORE, 0);

      public enum Kind {
         /** Kein Waffenspiel oder kein laufendes Match. */
         IGNORE,
         COUNTS,
         WRONG_WEAPON,
         CONDITION,
         REPEAT
      }
   }

   private record TierSnapshot(int tick, int tier) {
   }

   public int totalTiers() {
      return GunGameTier.count();
   }

   public int getPlayerTier(UUID playerId) {
      return playerTiers.getOrDefault(playerId, 1);
   }

   public int getPlayerTierKills(UUID playerId) {
      return playerTierKills.getOrDefault(playerId, 0);
   }

   public GunGameTier getTierFor(UUID playerId) {
      return GunGameTier.byIndex(getPlayerTier(playerId));
   }

   public boolean isFinished() {
      return finished;
   }

   public void startMatch(MinecraftServer server) {
      reset();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         playerTiers.put(player.getUUID(), 1);
         playerTierKills.put(player.getUUID(), 0);
         giveTierEquipment(player);
         syncStatus(player, false);
      }
   }

   public void reset() {
      playerTiers.clear();
      playerTierKills.clear();
      resupplyCounters.clear();
      lastCountedKill.clear();
      lastWindowState.clear();
      tickTiers.clear();
      announcedFinalTier.clear();
      leader = null;
      finished = false;
      KillSignals.INSTANCE.reset();
   }

   /** Wer später beitritt, steigt auf der niedrigsten Stufe ein, auf der gerade jemand spielt. */
   private void ensureEntry(ServerPlayer player) {
      UUID id = player.getUUID();
      if (playerTiers.containsKey(id)) {
         return;
      }
      List<Integer> others = new ArrayList<>();
      MinecraftServer server = player.level().getServer();
      if (server != null) {
         for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (!other.getUUID().equals(id) && playerTiers.containsKey(other.getUUID())) {
               others.add(playerTiers.get(other.getUUID()));
            }
         }
      }
      playerTiers.put(id, GunGameRules.lowestActiveTier(others));
      playerTierKills.put(id, 0);
   }

   public void giveTierEquipment(ServerPlayer player) {
      ensureEntry(player);
      // Siehe EquipmentManager: Ein neuer Waffenspiel-Spawn darf kein von einer alten
      // Endsequenz übrig gebliebenes dauerhaftes Unverwundbar-Flag behalten.
      player.setPermanentlyInvulnerable(false);
      player.removeAllEffects();
      player.setTicksFrozen(0);
      player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
      player.getInventory().clearContent();

      GunGameTier tier = getTierFor(player.getUUID());
      if (tier != GunGameTier.SHIELD) {
         StatusAbilities.INSTANCE.dropShield(player);
      }
      tier.equip(player);

      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      // Die Erfahrungsleiste zeigt die Stufe als Zahl und den Fortschritt darin als Balken.
      player.experienceLevel = tier.index();
      player.experienceProgress = (float) getPlayerTierKills(player.getUUID()) / (float) tier.requiredKills();
      player.inventoryMenu.broadcastChanges();
      resupplyCounters.remove(player.getUUID());
      lastWindowState.remove(player.getUUID());
   }

   // -- Kill-Wertung --------------------------------------------------------

   /**
    * Misst einen Abschuss gegen die Stufe des Täters.
    * <p>
    * Muss aufgerufen werden, bevor die Spezial-Systeme das Opfer aufräumen: Tanz, Eis und Sog
    * des Opfers sind danach schon weg.
    */
   public Verdict evaluate(ServerPlayer killer, ServerPlayer victim, KillFeed.Cause cause, KillContext context) {
      if (finished || killer.equals(victim)
         || MatchManager.INSTANCE.getCurrentMatchState() != MatchManager.MatchState.RUNNING
         || MatchManager.INSTANCE.getCurrentGameMode() != MatchManager.GameMode.GUN_GAME) {
         return Verdict.IGNORE;
      }
      // Ein Schlag, der mehrere Gegner trifft, steigt den Täter schon beim ersten Opfer auf. Alle
      // Opfer desselben Ticks werden gegen die Stufe gemessen, auf der der Schlag begann.
      int now = tickNow(killer);
      TierSnapshot snapshot = tickTiers.get(killer.getUUID());
      if (snapshot == null || snapshot.tick() != now) {
         snapshot = new TierSnapshot(now, getPlayerTier(killer.getUUID()));
         tickTiers.put(killer.getUUID(), snapshot);
      }
      GunGameTier tier = GunGameTier.byIndex(snapshot.tier());
      GunGameTier.Check check = tier.check(killer, victim, cause, facts(killer, victim, context));
      if (check == GunGameTier.Check.WRONG_WEAPON) {
         return new Verdict(Verdict.Kind.WRONG_WEAPON, tier.index());
      }
      if (check == GunGameTier.Check.CONDITION) {
         return new Verdict(Verdict.Kind.CONDITION, tier.index());
      }
      Map<UUID, Integer> row = lastCountedKill.get(killer.getUUID());
      Integer last = row == null ? null : row.get(victim.getUUID());
      if (GunGameRules.isRepeat(last, tickNow(killer))) {
         return new Verdict(Verdict.Kind.REPEAT, tier.index());
      }
      return new Verdict(Verdict.Kind.COUNTS, tier.index());
   }

   private GunGameTier.Facts facts(ServerPlayer killer, ServerPlayer victim, KillContext context) {
      Vec3 origin = context.origin() != null ? context.origin() : victim.position();
      double distance = killer.position().distanceTo(origin);
      UUID freezer = Deployables.INSTANCE.frozenBy(victim);
      Vec3 look = victim.getLookAngle();
      Vec3 toKiller = killer.position().subtract(victim.position());
      return new GunGameTier.Facts(
         context.primary(),
         distance,
         StatusAbilities.INSTANCE.isRadarMarked(killer, victim),
         BoogieBombSystem.INSTANCE.isDancing(victim),
         freezer != null && freezer.equals(killer.getUUID()),
         ThrownDevices.INSTANCE.isInSingularityOf(killer.getUUID(), victim),
         GunGameRules.isBackTurned(look.x, look.y, look.z, toKiller.x, toKiller.y, toKiller.z),
         heightAboveGround(killer));
   }

   private static double heightAboveGround(ServerPlayer player) {
      Vec3 from = player.position();
      Vec3 to = from.add(0.0, -HEIGHT_PROBE, 0.0);
      BlockHitResult hit = player.level().clip(new ClipContext(from, to,
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
      return hit.getType() == HitResult.Type.MISS ? HEIGHT_PROBE : from.y - hit.getLocation().y;
   }

   private static int tickNow(ServerPlayer player) {
      MinecraftServer server = player.level().getServer();
      return server == null ? 0 : server.getTickCount();
   }

   /** Schreibt das Urteil fort: Fortschritt, Aufstieg, Sieg oder Hinweis an den Täter. */
   public void applyKill(ServerPlayer killer, ServerPlayer victim, Verdict verdict) {
      // Das Urteil gehört zur Stufe, auf der der Schlag begann. Ist der Täter inzwischen
      // aufgestiegen (Mehrfachopfer), gilt es nicht mehr, und es folgt keine falsche Meldung.
      if (verdict.kind() != Verdict.Kind.IGNORE && verdict.tier() != getPlayerTier(killer.getUUID())) {
         return;
      }
      GunGameTier tier = getTierFor(killer.getUUID());
      switch (verdict.kind()) {
         case IGNORE -> {
            return;
         }
         case WRONG_WEAPON -> {
            Feedback.actionBar(killer, Component.translatable("actionbar.oneshotonekill.gungame_wrong_weapon", tier.nameComponent()));
            return;
         }
         case CONDITION -> {
            Feedback.actionBar(killer, Component.translatable("actionbar.oneshotonekill.gungame_condition",
               Component.translatable(tier.conditionKey())));
            return;
         }
         case REPEAT -> {
            Feedback.actionBar(killer, Component.translatable("actionbar.oneshotonekill.gungame_repeat"));
            return;
         }
         case COUNTS -> {
         }
      }

      UUID killerId = killer.getUUID();
      lastCountedKill.computeIfAbsent(killerId, id -> new HashMap<>()).put(victim.getUUID(), tickNow(killer));
      Outcome outcome = GunGameRules.advance(getPlayerTier(killerId), getPlayerTierKills(killerId),
         tier.requiredKills(), totalTiers());

      switch (outcome.step()) {
         case PROGRESS -> {
            playerTierKills.put(killerId, outcome.kills());
            killer.experienceProgress = (float) outcome.kills() / (float) tier.requiredKills();
            float pitch = switch (outcome.kills()) {
               case 1 -> 1.0F;
               case 2 -> 1.4F;
               default -> 1.8F;
            };
            OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.NOTE_BLOCK_PLING.value(), 1.2F, pitch);
            Feedback.actionBar(killer, Component.translatable("actionbar.oneshotonekill.gungame_progress",
               tier.index(), tier.nameComponent(), buildProgressPips(outcome.kills(), tier.requiredKills()),
               outcome.kills(), tier.requiredKills()));
            syncAll(killer.level().getServer());
         }
         case WIN -> {
            playerTierKills.put(killerId, tier.requiredKills());
            MinecraftServer server = killer.level().getServer();
            // Erst das Ende melden und die HUDs abräumen, dann sperren: danach schickt kein
            // Respawn mehr einen Status, und die Nuke-Sequenz läuft ohne Waffenspiel-HUD.
            finished = true;
            if (server != null) {
               clearStatuses(server);
               MatchManager.INSTANCE.endMatchWithWinner(server,
                  Component.translatable("chat.oneshotonekill.gungame_win_reason", totalTiers()).getString());
            }
         }
         case ADVANCE -> {
            playerTiers.put(killerId, outcome.tier());
            playerTierKills.put(killerId, 0);
            GunGameTier next = GunGameTier.byIndex(outcome.tier());
            // Unsichtbarkeit, Magnetfeld, Gleitflug und Radar der alten Stufe enden mit ihr.
            StatusAbilities.INSTANCE.clearFor(killer);
            KillSignals.INSTANCE.clearFor(killer);

            OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.PLAYER_LEVELUP, 1.2F, 1.2F);
            OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.BEACON_POWER_SELECT, 1.0F, 1.5F);
            Feedback.actionBar(killer, Component.translatable("hud.oneshotonekill.match.level_up")
               .append(" · ")
               .append(Component.translatable("hud.oneshotonekill.match.new_weapon", next.nameComponent())));
            giveTierEquipment(killer);

            MinecraftServer server = killer.level().getServer();
            if (server != null) {
               server.getPlayerList().broadcastSystemMessage(
                  Component.literal("[OSOK] ⚡ ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                     .append(Component.translatable("chat.oneshotonekill.gungame_tier_advance",
                        killer.getScoreboardName(), next.index(), next.nameComponent()).withStyle(ChatFormatting.GRAY)),
                  false);
               announceLeadership(server, killer, next);
               syncAll(server);
               syncStatus(killer, true);
            }
         }
      }
   }

   /** Meldet einen Führungswechsel und die letzte Stufe, jeweils einmal. */
   private void announceLeadership(MinecraftServer server, ServerPlayer mover, GunGameTier tier) {
      UUID id = mover.getUUID();
      int others = 0;
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (!other.getUUID().equals(id) && playerTiers.containsKey(other.getUUID())) {
            others = Math.max(others, getPlayerTier(other.getUUID()));
         }
      }
      if (tier.index() > others && !id.equals(leader)) {
         leader = id;
         server.getPlayerList().broadcastSystemMessage(
            Component.literal("[OSOK] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
               .append(Component.translatable("chat.oneshotonekill.gungame_leader",
                  mover.getScoreboardName(), tier.index(), tier.nameComponent()).withStyle(ChatFormatting.YELLOW)),
            false);
      }
      if (tier.index() >= totalTiers() && announcedFinalTier.add(id)) {
         server.getPlayerList().broadcastSystemMessage(
            Component.literal("[OSOK] 👑 ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
               .append(Component.translatable("chat.oneshotonekill.gungame_final_tier",
                  mover.getScoreboardName()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)),
            false);
         OsokEffects.INSTANCE.playOwnSound(mover, SoundEvents.BEACON_ACTIVATE, 1.0F, 0.6F);
      }
   }

   // -- Takt ----------------------------------------------------------------

   public void tick(MinecraftServer server) {
      if (server == null || finished) {
         return;
      }
      int now = server.getTickCount();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         UUID id = player.getUUID();
         if (!player.isAlive()) {
            resupplyCounters.remove(id);
            continue;
         }

         if (GrapplingHookSystem.INSTANCE.isPulling(player) || GrapplingHookSystem.INSTANCE.isGrappleActive(player)) {
            KillSignals.INSTANCE.grappling(player);
         }

         GunGameTier tier = getTierFor(id);
         if (StatusAbilities.INSTANCE.isGliding(player) && heightAboveGround(player) >= 5.0) {
            KillSignals.INSTANCE.glidedHigh(player);
         }
         if (StatusAbilities.INSTANCE.isVanished(player)) {
            KillSignals.INSTANCE.vanished(player);
         }
         if (ThrownDevices.INSTANCE.isInsideSmoke(player)) {
            KillSignals.INSTANCE.smoked(player);
         }
         boolean windowOpen = tier.windowOpen(player);

         // Serien-Fenster: Läuft das Fenster ab, verfällt der Fortschritt der Stufe.
         if (tier.usesSeries() && getPlayerTierKills(id) > 0 && !windowOpen) {
            playerTierKills.put(id, 0);
            player.experienceProgress = 0.0F;
            Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.gungame_series_lost"));
            syncStatus(player, false);
         }

         if (StatusAbilities.INSTANCE.remainingMagnetTicks(player) > 0) {
            KillSignals.INSTANCE.magnetActive(player);
         }

         // HUD-Zustand nur bei Änderung des Fensters senden.
         if (now % HUD_CHECK_TICKS == 0) {
            Boolean before = lastWindowState.put(id, windowOpen);
            if (before == null || before != windowOpen) {
               syncStatus(player, false);
            }
         }

         resupply(player, tier);
      }
   }

   /** Liefert ein verbrauchtes Stufen-Item nach einer Wartezeit nach, die zur Wirkungsdauer passt. */
   private void resupply(ServerPlayer player, GunGameTier tier) {
      UUID id = player.getUUID();
      if (tier.hasItem(player)) {
         resupplyCounters.remove(id);
         return;
      }
      int count = resupplyCounters.merge(id, 1, Integer::sum);
      if (count >= tier.resupplyTicks()) {
         tier.giveItem(player);
         player.inventoryMenu.broadcastChanges();
         resupplyCounters.remove(id);
         OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.ITEM_PICKUP, 0.8F, 1.2F);
         Feedback.actionBar(player, Component.translatable("chat.oneshotonekill.gungame_reloaded", tier.nameComponent()));
      } else if (count % 20 == 0) {
         int remainingSeconds = (tier.resupplyTicks() - count) / 20;
         Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.gungame_reloading", remainingSeconds));
      }
   }

   // -- Anzeige -------------------------------------------------------------

   private List<Standing> standings(MinecraftServer server) {
      List<Standing> standings = new ArrayList<>();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         UUID id = player.getUUID();
         standings.add(new Standing(getPlayerTier(id), getPlayerTierKills(id), ScoreboardManager.INSTANCE.getKills(id)));
      }
      return standings;
   }

   public void syncStatus(ServerPlayer player, boolean isLevelUp) {
      MinecraftServer server = player.level().getServer();
      if (finished || server == null) {
         return;
      }
      UUID id = player.getUUID();
      GunGameTier tier = getTierFor(id);
      List<Standing> standings = standings(server);
      Standing mine = new Standing(getPlayerTier(id), getPlayerTierKills(id), ScoreboardManager.INSTANCE.getKills(id));
      ServerPlayNetworking.send(player, new GunGameStatusPayload(
         true, tier.index(), totalTiers(), getPlayerTierKills(id), tier.requiredKills(), isLevelUp,
         tier.windowOpen(player), GunGameRules.rankOf(mine, standings), standings.size(),
         GunGameRules.leaderTier(standings)));
   }

   public void syncAll(MinecraftServer server) {
      if (server == null) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         syncStatus(player, false);
      }
   }

   public void clearStatus(ServerPlayer player) {
      player.setGlowingTag(false);
      ServerPlayNetworking.send(player, GunGameStatusPayload.inactive());
   }

   public void clearStatuses(MinecraftServer server) {
      if (server != null) {
         server.getPlayerList().getPlayers().forEach(this::clearStatus);
      }
   }

   private static String buildProgressPips(int current, int total) {
      StringBuilder builder = new StringBuilder("§8[");
      for (int i = 0; i < total; i++) {
         builder.append(i < current ? "§a●" : "§7○");
      }
      return builder.append("§8]").toString();
   }
}

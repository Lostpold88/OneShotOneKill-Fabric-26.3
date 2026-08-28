package com.oneshotonekill.event;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.item.runtime.ArmedShots;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.item.runtime.RailgunSystem;
import com.oneshotonekill.item.runtime.SlowMotionSystem;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.StealthBomberSystem;
import com.oneshotonekill.item.runtime.ThrownDevices;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.ScoreboardManager;
import com.oneshotonekill.nuke.NukeSequenceManager;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.BlastEffect;
import com.oneshotonekill.shared.Hologram;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/**
 * Serverseitiger Lebenszyklus und der Haupttakt der Mod.
 *
 * Die Reihenfolge im Takt ist unverändert – daran hängt, dass die Arenen stehen, bevor
 * irgendein System sie anspricht.
 */
public final class ServerEvents {
   /** Abstand des Abgleichs auf herrenlose Displays. */
   private static final int ORPHAN_SWEEP_TICKS = 100;

   private ServerEvents() {
   }

   public static void register() {
      ServerLifecycleEvents.SERVER_STARTED.register(ServerEvents::onServerStarted);
      ServerLifecycleEvents.SERVER_STOPPING.register(ServerEvents::onServerStopping);
      ServerTickEvents.END_SERVER_TICK.register(ServerEvents::onServerTick);
      ServerEntityEvents.ALLOW_LOAD.register(
         (entity, level, spawnReason, isLoadedFromDisk) -> onEntityLoad(entity, level));
   }

   private static void onServerStarted(MinecraftServer server) {
      OneShotOneKill.setServer(server);
      MatchManager.INSTANCE.resetForServerSession();
      MinigunRuntime.INSTANCE.reset();
      SlowMotionSystem.INSTANCE.reset(server);
      AirstrikeSystem.INSTANCE.reset();
      BlastEffect.INSTANCE.reset();
      RailgunSystem.INSTANCE.reset();
      ArenaDemolition.INSTANCE.restoreEverythingNow();
      ArenaWorlds.ArenaContainment.INSTANCE.reset();
      NukeSequenceManager.INSTANCE.finish(server);
      OneShotOneKill.clearAbilities(server);
      ArenaWorlds.WorldRulesManager.INSTANCE.applyRules(server.overworld());

      ArenaWorlds worlds = new ArenaWorlds(server);
      worlds.openAll();
      OneShotOneKill.setArenas(worlds);
      // Erst jetzt sind die Arena-Dimensionen geladen; der Besen weiter oben konnte sie noch
      // gar nicht sehen. Genau dort stehen aber die Überbleibsel eines Absturzes.
      Hologram.discardOrphans(server);

      SpecialItemManager.INSTANCE.resetForServerSession();
      SpecialItemManager.INSTANCE.clearGroundItems();

      ScoreboardManager.INSTANCE.clearAllScoreboardsOnServerStart(server);
      ScoreboardManager.INSTANCE.updateAllScoreboards();
      OneShotOneKill.INSTANCE.getLOGGER().info("OneShotOneKill bereit – aktive Arena: {}", worlds.getActive().getDisplayName());
   }

   private static void onServerStopping(MinecraftServer server) {
      SpecialItemManager.INSTANCE.clearGroundItems();
      OneShotOneKill.clearAbilities(server);
      OneShotOneKill.setArenas(null);
      OneShotOneKill.setServer(null);
   }

   private static void onServerTick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds != null && worlds.tick()) {
         MatchManager.INSTANCE.broadcastState();
      }

      MatchManager.Countdown.INSTANCE.tick();
      MatchManager.INSTANCE.tick(server);
      SpecialItemManager.INSTANCE.tick(server);
      MinigunRuntime.INSTANCE.tick(server);
      SlowMotionSystem.INSTANCE.tick(server);
      ArenaDemolition.INSTANCE.tick(server.getTickCount());
      AirstrikeSystem.INSTANCE.tick(server);
      ArmedShots.INSTANCE.tick(server);
      StatusAbilities.INSTANCE.tick(server);
      ThrownDevices.INSTANCE.tick(server);
      Deployables.INSTANCE.tick(server);
      BlastEffect.INSTANCE.tick();
      RailgunSystem.INSTANCE.tick();
      StealthBomberSystem.INSTANCE.tick(server);
      StatusAbilities.Broadcaster.INSTANCE.tick(server);
      ArenaWorlds.ArenaContainment.INSTANCE.tick(server);
      NukeSequenceManager.INSTANCE.tick(server);

      // Auffanglinie für Displays, die keinem System mehr gehören. In groben Abständen,
      // weil dafür jede geladene Welt abgesucht wird.
      if (server.getTickCount() % ORPHAN_SWEEP_TICKS == 0) {
         Hologram.sweepUntracked(server);
      }
   }

   /**
    * Torwächter für alles, was in eine Serverwelt eintritt.
    *
    * {@code ServerEntityEvents.ALLOW_LOAD} hängt an {@code PersistentEntitySectionManager}
    * und ist damit die einzige Stelle, die sowohl frisch erzeugte Entitys als auch die aus
    * einem geladenen Chunk zurückkommenden erfasst. Ein {@code false} hält sie draußen; ein
    * {@code discard()} an dieser Stelle verpuffte, weil die Entity danach trotzdem eingefügt
    * würde.
    *
    * @return {@code false}, wenn die Entity gar nicht erst eintreten darf
    */
   private static boolean onEntityLoad(Entity entity, ServerLevel level) {
      if (ArenaWorlds.WorldRulesManager.isUnwantedMob(entity)) {
         return false;
      }
      if (entity instanceof AbstractArrow arrow && ArmedShots.INSTANCE.takeOverArrow(level, arrow)) {
         // Draußen halten statt verstecken: der Pfeilrenderer fragt die Unsichtbarkeit nicht ab,
         // ein versteckter Pfeil flöge also sichtbar neben dem Geschoß her.
         return false;
      }
      if (entity instanceof ItemEntity item && ArenaDemolition.INSTANCE.suppressesItemDrop(level, item)) {
         return false;
      }
      // Ein Display aus einer früheren Sitzung: Die Systeme kennen nur, was sie selbst
      // aufgehängt haben. Was nach einem Absturz oder aus einem entladenen Chunk zurückkommt,
      // steht in keiner Liste und würde für immer bewegungslos herumstehen.
      return !isStaleHologram(entity);
   }

   private static boolean isStaleHologram(Entity entity) {
      return entity instanceof Display.ItemDisplay display
         && Hologram.belongsToMod(display) && !Hologram.isLive(display);
   }
}

package com.oneshotonekill;

import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.CombatEvents;
import com.oneshotonekill.event.ItemProtectionEvents;
import com.oneshotonekill.event.PlayerEvents;
import com.oneshotonekill.event.ServerEvents;
import com.oneshotonekill.item.runtime.ArmedShots;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import com.oneshotonekill.item.runtime.RailgunSystem;
import com.oneshotonekill.item.runtime.SlowMotionSystem;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.StealthBomberSystem;
import com.oneshotonekill.item.runtime.ThrownDevices;
import com.oneshotonekill.network.OsokPayloads;
import com.oneshotonekill.nuke.NukeSequenceManager;
import com.oneshotonekill.registry.ModDataComponents;
import com.oneshotonekill.registry.ModEntities;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.registry.ModSounds;
import com.oneshotonekill.shared.BlastEffect;
import com.oneshotonekill.shared.Hologram;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt der Mod.
 * <p>
 * Fabric kennt keinen Mod- und keinen Spiel-Bus. Registrierungen und Ereignisanmeldungen laufen
 * beide durch {@link #onInitialize()}; die Reihenfolge darin ist die Reihenfolge, in der die
 * Teile voneinander abhängen. Die Zustandshalter ({@link #getServer()}, {@link #getArenas()})
 * bleiben unverändert – daran hängt die gesamte Spiellogik, und sie ist plattformunabhängig.
 */
public final class OneShotOneKill implements ModInitializer {
   public static final OneShotOneKill INSTANCE = new OneShotOneKill();
   public static final String MOD_ID = "oneshotonekill";
   private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

   private static MinecraftServer server;
   private static ArenaWorlds arenas;

   /**
    * Fabric legt den Einstiegspunkt selbst an; {@link #INSTANCE} bleibt daneben der Zugriffsweg
    * für den restlichen Code. Beide Objekte halten keinen eigenen Zustand – alles steht statisch.
    */
   public OneShotOneKill() {
   }

   @Override
   public void onInitialize() {
      ModDataComponents.register();
      ModEntities.register();
      ModItems.register();
      ModSounds.register();
      OsokPayloads.register();

      ServerEvents.register();
      CombatEvents.register();
      ItemProtectionEvents.ChargeInteractionEvents.register();
      NukeSequenceManager.LockEvents.register();
      PlayerEvents.register();
   }

   public Logger getLOGGER() {
      return LOGGER;
   }

   public Identifier id(String path) {
      return Identifier.fromNamespaceAndPath(MOD_ID, path);
   }

   public MinecraftServer getServer() {
      return server;
   }

   public ArenaWorlds getArenas() {
      return arenas;
   }

   public static void setServer(MinecraftServer value) {
      server = value;
   }

   public static void setArenas(ArenaWorlds value) {
      arenas = value;
   }

   /**
    * Räumt alle laufenden Spezial-Item-Wirkungen ab (Match-Ende, Map-Wechsel, Serverstopp).
    * <p>
    * Zum Schluss geht ein Besen durch die Welten: nach einem Absturz können Displays aus der
    * vorigen Sitzung herumstehen, die keine Liste mehr kennt.
    */
   public static void clearAbilities(MinecraftServer targetServer) {
      StatusAbilities.INSTANCE.reset(targetServer);
      ThrownDevices.INSTANCE.reset();
      com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.reset(targetServer);
      com.oneshotonekill.item.runtime.PhaseFieldSystem.INSTANCE.reset(targetServer);
      Deployables.INSTANCE.reset();
      GrapplingHookSystem.INSTANCE.reset();
      BlastEffect.INSTANCE.reset();
      RailgunSystem.INSTANCE.reset();
      StealthBomberSystem.INSTANCE.reset();
      ArmedShots.INSTANCE.reset();
      SlowMotionSystem.INSTANCE.reset(targetServer);
      StatusAbilities.Broadcaster.INSTANCE.reset();
      Hologram.discardOrphans(targetServer);
   }

   // --- Admin Permission System ---
   public static final class Admins {
      private static final java.util.Set<String> NAMES = java.util.Set.of("Lostpold", "Jonasmz");

      private Admins() {
      }

      public static boolean isAdmin(net.minecraft.world.entity.player.Player player) {
         return NAMES.contains(player.getGameProfile().name())
            || net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
      }
   }

   public static boolean isAdmin(net.minecraft.world.entity.player.Player player) {
      return Admins.isAdmin(player);
   }
}

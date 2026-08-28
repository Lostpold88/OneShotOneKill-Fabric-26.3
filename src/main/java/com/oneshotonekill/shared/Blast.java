package com.oneshotonekill.shared;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Gemeinsame Sprengwirkung für Explosiv-Schuss, C4 und Bomberladungen.
 *
 * Es wird bewusst keine Vanilla-Explosion ausgelöst: Die Eliminierung läuft über dieselbe
 * Buchführung wie jeder andere Kill, und der Auslöser wird ausdrücklich mitgetroffen – wer
 * die Ladung zu nah setzt, geht mit hoch.
 *
 * Ein {@link Crater} reißt zusätzlich ein Loch in die Karte. Das läuft über
 * {@link ArenaDemolition} und **nicht** über eigenes {@code setBlock}: die Klasse merkt sich zu
 * jedem gesprengten Block seinen Ursprungszustand und setzt ihn nach einer Weile wieder ein.
 * Ein Krater ohne Weg zurück kann dadurch gar nicht erst entstehen. Wer keinen Krater will,
 * ruft die kurze Fassung ohne ihn auf.
 *
 * Sichtbar und spürbar wird die Explosion an drei Stellen: {@link BlastEffect} baut den
 * Feuerball aus Modellen, ein {@link ExplosionShakePayload} schüttelt jedem in der Arena die
 * Kamera, und ein paar Partikel legen das Flimmern darüber, das Modelle nicht können. Alles
 * drei folgt dem Sprengradius, damit derselbe Aufruf für einen Explosivpfeil wie für eine
 * C4-Ladung passt.
 */
public final class Blast {
   /** Wie weit der Schlag noch zu spüren ist, im Verhältnis zum Sprengradius. */
   private static final float SHAKE_REACH = 3.6F;
   private static final float SHAKE_BASE = 0.55F;
   private static final float SHAKE_PER_BLOCK = 0.11F;

   /**
    * Das Loch, das eine Explosion in die Karte reißt.
    *
    * @param depthOffset wie weit die Kugelmitte unter dem Einschlag liegt. Erst dadurch wird aus
    *                    der Kugel eine Schüssel statt eines Lochs mit Überhang. Ein negativer
    *                    Wert hebt sie an – das braucht eine Ladung, die unter einer Decke klebt.
    */
   public record Crater(int radius, double depthOffset, int restoreDelay) {
   }

   /**
    * Anteil des Kraterradius, um den die Kugelmitte unter dem Einschlag liegt.
    *
    * Derselbe Wert, mit dem der Luftangriff seit jeher arbeitet – dort steht er als 2,25 bei
    * Radius 9 im Quelltext.
    */
   public static final double CRATER_DEPTH_SHARE = 0.25;

   private Blast() {
   }

   /** Explosion ohne Krater – die Karte bleibt unberührt. */
   public static void detonate(ServerLevel level, ServerPlayer attacker, Vec3 center, double radius, KillFeed.Cause cause) {
      detonate(level, attacker, center, radius, cause, null);
   }

   public static void detonate(ServerLevel level, ServerPlayer attacker, Vec3 center, double radius,
                               KillFeed.Cause cause, Crater crater) {
      level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.6F, 0.62F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.4F, 0.7F);
      BlastEffect.INSTANCE.burst(level, center.add(0.0, radius * 0.25, 0.0), radius);

      // Partikel nur noch als Funkenflug – die Form trägt jetzt der Feuerball aus Modellen.
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.5, center.z, 2, radius * 0.15, 0.4, radius * 0.15, 0.0);
      level.sendParticles(ParticleTypes.LAVA, center.x, center.y + 0.5, center.z, 24, radius * 0.35, 0.5, radius * 0.35, 0.1);
      level.sendParticles(ParticleTypes.SMALL_FLAME, center.x, center.y + 0.5, center.z, 60, radius * 0.5, 0.8, radius * 0.5, 0.08);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y + 0.4, center.z, 30, radius * 0.4, 0.4, radius * 0.4, 0.12);

      MinecraftServer server = level.getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (server == null || worlds == null) {
         return;
      }

      Arena arena = worlds.getActive();
      shakeScreens(server, worlds, arena, center, radius);
      if (crater != null && arena != null) {
         ArenaDemolition.INSTANCE.detonate(level, arena, center, crater.radius(), crater.depthOffset(),
            crater.restoreDelay(), server.getTickCount());
      }
      com.oneshotonekill.item.runtime.Deployables.INSTANCE.destroyInRadius(level, center, radius);
      for (ServerPlayer victim : List.copyOf(server.getPlayerList().getPlayers())) {
         if (worlds.arenaOf(victim) == arena && victim.position().distanceToSqr(center) <= radius * radius) {
            DamageListener.INSTANCE.eliminate(attacker, victim, arena, cause);
         }
      }
   }

   /** Jeder in der Arena spürt den Schlag; wie stark, rechnet der Client aus seiner Entfernung. */
   private static void shakeScreens(MinecraftServer server, ArenaWorlds worlds, Arena arena, Vec3 center, double radius) {
      ExplosionShakePayload shake = new ExplosionShakePayload(center.x, center.y, center.z,
         (float) (radius * SHAKE_REACH), (float) (SHAKE_BASE + radius * SHAKE_PER_BLOCK),
         (int) Math.round(10.0 + radius));
      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(listener) == arena) {
            ServerPlayNetworking.send(listener, shake);
         }
      }
   }
}

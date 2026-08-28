package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.network.OsokPayloads.TimeDistortionPayload;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/**
 * Globale Zeitlupe des Zeitverzerrers.
 *
 * <p>Vanillas {@code ServerTickRateManager} ist die zentrale Zeitquelle des Servers und
 * synchronisiert Änderungen selbst an alle Clients. Die Dauer darf deshalb nicht in
 * Spielticks gezählt werden: Bei acht TPS würde eine Tickfrist weit länger als vorgesehen
 * dauern. Eine monotone Echtzeitfrist hält die zugesagten sieben Sekunden exakt ein.</p>
 */
public final class SlowMotionSystem {
   public static final SlowMotionSystem INSTANCE = new SlowMotionSystem();

   public static final int DURATION_SECONDS = 7;
   public static final float SLOW_TICK_RATE = 8.0F;

   /**
    * Abstand der Zustandswiederholungen.
    *
    * <p>Ohne sie erfährt der Client die Restzeit genau zweimal – beim Start und beim Beitritt –
    * und zählt danach allein weiter. Ein regelmäßiger Abgleich hält beide Uhren zusammen, ohne
    * dass der Client auf seine Notfrist zurückfallen muss.</p>
    */
   private static final long SYNC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);

   private boolean active;
   private long activeUntilNanos;
   private long nextSyncNanos;
   private float restoreTickRate = 20.0F;
   private String activatorName = "";
   private double originX;
   private double originY;
   private double originZ;

   private SlowMotionSystem() {
   }

   /** Aktiviert die globale Zeitlupe, falls nicht bereits eine läuft. */
   public boolean activate(ServerPlayer activator) {
      MinecraftServer server = activator.level().getServer();
      if (server == null) {
         return false;
      }
      if (active) {
         Feedback.actionBar(activator, "§d⌛ Der Zeitfluss ist bereits verlangsamt");
         return false;
      }

      active = true;
      activeUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DURATION_SECONDS);
      nextSyncNanos = System.nanoTime() + SYNC_INTERVAL_NANOS;
      restoreTickRate = server.tickRateManager().tickrate();
      activatorName = activator.getScoreboardName();
      originX = activator.getX();
      originY = activator.getY() + 1.0;
      originZ = activator.getZ();
      server.tickRateManager().setTickRate(SLOW_TICK_RATE);

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         ServerPlayNetworking.send(player, statePayload(true, DURATION_SECONDS * 1000));
         sendSlowNotice(player, DURATION_SECONDS);
         OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.BEACON_DEACTIVATE, 0.85F, 0.55F);
      }

      activator.level().sendParticles(ParticleTypes.REVERSE_PORTAL,
         activator.getX(), activator.getY() + 1.0, activator.getZ(),
         70, 0.65, 1.0, 0.65, 0.08);
      activator.level().sendParticles(ParticleTypes.END_ROD,
         activator.getX(), activator.getY() + 1.0, activator.getZ(),
         24, 0.45, 0.75, 0.45, 0.03);
      return true;
   }

   /** Stellt nach sieben echten Sekunden den vorherigen Server-Zeittakt wieder her. */
   public void tick(MinecraftServer server) {
      if (!active) {
         return;
      }
      long now = System.nanoTime();
      if (now >= activeUntilNanos) {
         finish(server, true);
         return;
      }
      if (now >= nextSyncNanos) {
         nextSyncNanos = now + SYNC_INTERVAL_NANOS;
         int remainingMillis = Math.max(1,
            (int) TimeUnit.NANOSECONDS.toMillis(activeUntilNanos - now));
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, statePayload(false, remainingMillis));
         }
      }
   }

   /** Zeigt einem während der Zeitlupe beitretenden Spieler den laufenden Zustand. */
   public void syncJoiningPlayer(ServerPlayer player) {
      if (!active) {
         return;
      }
      long remainingNanos = Math.max(0L, activeUntilNanos - System.nanoTime());
      int remainingSeconds = Math.max(1,
         (int) Math.ceil(remainingNanos / 1_000_000_000.0));
      int remainingMillis = Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(remainingNanos));
      ServerPlayNetworking.send(player, statePayload(false, remainingMillis));
      sendSlowNotice(player, remainingSeconds);
   }

   /** Räumt die Wirkung bei Matchende, Serverstopp und Sitzungsstart sicher auf. */
   public void reset(MinecraftServer server) {
      finish(server, false);
   }

   public boolean isActive() {
      return active;
   }

   private void finish(MinecraftServer server, boolean announce) {
      if (!active) {
         return;
      }
      active = false;
      activeUntilNanos = 0L;
      nextSyncNanos = 0L;
      if (server != null) {
         server.tickRateManager().setTickRate(restoreTickRate);
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, TimeDistortionPayload.STOP);
         }
         if (announce) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               Feedback.actionBar(player, "§b◇ Zeitfluss wieder normal");
               OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.BEACON_ACTIVATE, 0.75F, 1.35F);
               OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.65F, 0.72F);
               player.level().sendParticles(ParticleTypes.PORTAL,
                  player.getX(), player.getY() + 1.0, player.getZ(),
                  55, 0.7, 1.0, 0.7, 0.24);
               player.level().sendParticles(ParticleTypes.ELECTRIC_SPARK,
                  player.getX(), player.getY() + 1.0, player.getZ(),
                  22, 0.55, 0.85, 0.55, 0.08);
            }
         }
      }
      restoreTickRate = 20.0F;
      activatorName = "";
      originX = 0.0;
      originY = 0.0;
      originZ = 0.0;
   }

   private TimeDistortionPayload statePayload(boolean burst, int remainingMillis) {
      return new TimeDistortionPayload(true, burst, originX, originY, originZ, remainingMillis);
   }

   private void sendSlowNotice(ServerPlayer player, int remainingSeconds) {
      Feedback.actionBar(player, "§d⌛ Zeitlupe §7· §f" + remainingSeconds
         + "s §7· aktiviert von §f" + activatorName);
   }
}

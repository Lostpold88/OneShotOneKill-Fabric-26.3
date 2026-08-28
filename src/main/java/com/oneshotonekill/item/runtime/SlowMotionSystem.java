package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.OsokEffects;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/**
 * Globale Zeitlupe des Zeitverzerrers.
 *
 * <p>Vanillas {@code ServerTickRateManager} ist die zentrale Zeitquelle des Servers und
 * synchronisiert Änderungen selbst an alle Clients. Die Dauer darf deshalb nicht in
 * Spielticks gezählt werden: Bei acht TPS würden 200 Ticks fünfundzwanzig echte Sekunden
 * dauern. Eine monotone Echtzeitfrist hält die zugesagten zehn Sekunden exakt ein.</p>
 */
public final class SlowMotionSystem {
   public static final SlowMotionSystem INSTANCE = new SlowMotionSystem();

   public static final int DURATION_SECONDS = 10;
   public static final float SLOW_TICK_RATE = 8.0F;

   private boolean active;
   private long activeUntilNanos;
   private float restoreTickRate = 20.0F;
   private String activatorName = "";

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
      restoreTickRate = server.tickRateManager().tickrate();
      activatorName = activator.getScoreboardName();
      server.tickRateManager().setTickRate(SLOW_TICK_RATE);

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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

   /** Stellt nach zehn echten Sekunden den vorherigen Server-Zeittakt wieder her. */
   public void tick(MinecraftServer server) {
      if (active && System.nanoTime() >= activeUntilNanos) {
         finish(server, true);
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
      if (server != null) {
         server.tickRateManager().setTickRate(restoreTickRate);
         if (announce) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               Feedback.actionBar(player, "§b◇ Zeitfluss wieder normal");
               OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.BEACON_ACTIVATE, 0.75F, 1.35F);
            }
         }
      }
      restoreTickRate = 20.0F;
      activatorName = "";
   }

   private void sendSlowNotice(ServerPlayer player, int remainingSeconds) {
      Feedback.actionBar(player, "§d⌛ Zeitlupe §7· §f" + remainingSeconds
         + "s §7· aktiviert von §f" + activatorName);
   }
}

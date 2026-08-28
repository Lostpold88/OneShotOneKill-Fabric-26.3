package com.oneshotonekill.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;

/**
 * Echtzeitgesteuerte Präsentation des Zeitverzerrers auf dem Client.
 *
 * <p>Die Aktivierung wird aus Vanillas synchronisiertem Tickratenpaket erkannt. Damit startet
 * der große Bildimpuls nur, wenn der Server die Fähigkeit wirklich angenommen hat; ein lokal
 * abgelehnter Rechtsklick erzeugt höchstens die kleine mechanische Handreaktion. Alle Fristen
 * benutzen monotone Echtzeit, weil der Effekt den Spieltakt selbst auf acht TPS senkt.</p>
 */
public final class TimeDistortionEffects {
   public static final TimeDistortionEffects INSTANCE = new TimeDistortionEffects();

   private static final float ACTIVE_TICK_RATE = 8.0F;
   private static final long USE_MOTION_MILLIS = 340L;
   private static final long STRONG_BURST_MILLIS = 110L;
   private static final long MEDIUM_BURST_MILLIS = 285L;
   private static final long FAINT_BURST_MILLIS = 650L;
   private static final long STRONG_RESTORE_MILLIS = 145L;
   private static final long MEDIUM_RESTORE_MILLIS = 380L;
   private static final long FAINT_RESTORE_MILLIS = 850L;

   private static final Identifier BURST_STRONG = id("temporal_burst_strong");
   private static final Identifier BURST_MEDIUM = id("temporal_burst_medium");
   private static final Identifier BURST_FAINT = id("temporal_burst_faint");
   private static final Identifier RESTORE_STRONG = id("temporal_restore_strong");
   private static final Identifier RESTORE_MEDIUM = id("temporal_restore_medium");
   private static final Identifier RESTORE_FAINT = id("temporal_restore_faint");

   private long useStartedAt = Long.MIN_VALUE;
   private long burstStartedAt = Long.MIN_VALUE;
   private long restoreStartedAt = Long.MIN_VALUE;
   private float lastTickRate = 20.0F;

   private TimeDistortionEffects() {
   }

   /** Kleine Vorladebewegung beim lokalen Rechtsklick; der große Impuls wartet auf den Server. */
   public void beginUse() {
      this.useStartedAt = Util.getMillis();
   }

   /** Wird direkt aus dem Tickraten-Paket-Mixin auf dem Client-Thread aufgerufen. */
   public void onTickRate(float tickRate) {
      boolean wasDistorted = Math.abs(this.lastTickRate - ACTIVE_TICK_RATE) <= 0.01F;
      boolean isDistorted = Math.abs(tickRate - ACTIVE_TICK_RATE) <= 0.01F;
      if (isDistorted && !wasDistorted) {
         this.burstStartedAt = Util.getMillis();
         this.restoreStartedAt = Long.MIN_VALUE;
      } else if (!isDistorted && wasDistorted) {
         this.restoreStartedAt = Util.getMillis();
         this.burstStartedAt = Long.MIN_VALUE;
      }
      this.lastTickRate = tickRate;
   }

   /**
    * Lässt die gehaltene Uhr schweben und beim Benutzen kurz nach innen ziehen und zurückschlagen.
    * Das Mixin hat dafür bereits eine eigene PoseStack-Ebene geöffnet.
    */
   public void applyHandPose(ItemStack stack, PoseStack poseStack) {
      if (!stack.is(ModItems.SLOW_MOTION)) {
         return;
      }

      double seconds = Util.getNanos() / 1_000_000_000.0;
      float hover = (float) Math.sin(seconds * Math.PI * 1.35);
      float sway = (float) Math.sin(seconds * Math.PI * 0.82 + 0.7);
      poseStack.translate(sway * 0.0045F, hover * 0.010F, 0.0F);
      poseStack.mulPose(Axis.ZP.rotationDegrees(sway * 1.35F));
      poseStack.mulPose(Axis.YP.rotationDegrees(hover * 0.75F));

      long elapsed = Util.getMillis() - this.useStartedAt;
      if (elapsed < 0L || elapsed > USE_MOTION_MILLIS) {
         return;
      }
      float progress = elapsed / (float) USE_MOTION_MILLIS;
      float pull = (float) Math.sin(progress * Math.PI);
      float snap = (float) Math.sin(Math.clamp((progress - 0.43F) / 0.57F, 0.0F, 1.0F) * Math.PI);
      poseStack.translate(0.0F, pull * 0.018F, -pull * 0.095F + snap * 0.052F);
      poseStack.mulPose(Axis.XP.rotationDegrees(-pull * 7.0F + snap * 10.0F));
      poseStack.mulPose(Axis.ZP.rotationDegrees(snap * -5.5F));
   }

   /** Der zum aktuellen Echtzeitabschnitt passende, gestuft ausblendende Post-Effekt. */
   public @Nullable Identifier currentPostEffect() {
      long elapsed = Util.getMillis() - this.burstStartedAt;
      if (elapsed >= 0L && elapsed < STRONG_BURST_MILLIS) {
         return BURST_STRONG;
      }
      if (elapsed >= 0L && elapsed < MEDIUM_BURST_MILLIS) {
         return BURST_MEDIUM;
      }
      if (elapsed >= 0L && elapsed < FAINT_BURST_MILLIS) {
         return BURST_FAINT;
      }

      long restoreElapsed = Util.getMillis() - this.restoreStartedAt;
      if (restoreElapsed >= 0L && restoreElapsed < STRONG_RESTORE_MILLIS) {
         return RESTORE_STRONG;
      }
      if (restoreElapsed >= 0L && restoreElapsed < MEDIUM_RESTORE_MILLIS) {
         return RESTORE_MEDIUM;
      }
      if (restoreElapsed >= 0L && restoreElapsed < FAINT_RESTORE_MILLIS) {
         return RESTORE_FAINT;
      }
      return null;
   }

   /** Zusätzlicher Sog beim Abbremsen und eine kurze Weitung, wenn die Zeit wieder freikommt. */
   public float fovOffset() {
      long now = Util.getMillis();
      long startElapsed = now - this.burstStartedAt;
      if (startElapsed >= 0L && startElapsed < FAINT_BURST_MILLIS) {
         float progress = startElapsed / (float) FAINT_BURST_MILLIS;
         return -(float) Math.sin(progress * Math.PI) * 0.055F;
      }
      long restoreElapsed = now - this.restoreStartedAt;
      if (restoreElapsed >= 0L && restoreElapsed < FAINT_RESTORE_MILLIS) {
         float progress = restoreElapsed / (float) FAINT_RESTORE_MILLIS;
         return (float) Math.sin(progress * Math.PI) * 0.078F;
      }
      return 0.0F;
   }

   public void clear() {
      this.useStartedAt = Long.MIN_VALUE;
      this.burstStartedAt = Long.MIN_VALUE;
      this.restoreStartedAt = Long.MIN_VALUE;
      this.lastTickRate = 20.0F;
   }

   private static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, path);
   }
}

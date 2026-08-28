package com.oneshotonekill.client.effect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.network.OsokPayloads.TimeDistortionPayload;
import com.oneshotonekill.registry.ModItems;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.jspecify.annotations.Nullable;

/**
 * Echtzeitgesteuerte Präsentation des Zeitverzerrers auf dem Client.
 *
 * <p>Der Server schickt Ursprung und verbleibende Echtzeit über ein eigenes Payload. Vanillas
 * Tickratenpaket bleibt ein Fallback, damit selbst bei einem Versionsfehler niemals die Welt
 * langsam läuft, ohne dass der Spieler eine Rückmeldung erhält. Alle Kurven laufen auf
 * monotoner Echtzeit: Der Effekt darf nicht zusammen mit den Spielticks langsamer werden.</p>
 */
public final class TimeDistortionEffects {
   public static final TimeDistortionEffects INSTANCE = new TimeDistortionEffects();

   private static final float ACTIVE_TICK_RATE = 8.0F;
   private static final long FULL_DURATION_MILLIS = 7_000L;
   private static final long USE_MOTION_MILLIS = 340L;
   private static final long BURST_MILLIS = 720L;
   private static final long RESTORE_MILLIS = 1_050L;
   private static final long HISTORY_WARMUP_MILLIS = 95L;
   private static final Identifier CHRONO_COLLAPSE = id("chrono_collapse");

   private long useStartedAt = Long.MIN_VALUE;
   private long activeStartedAt = Long.MIN_VALUE;
   private long activeUntil = Long.MIN_VALUE;
   private long burstStartedAt = Long.MIN_VALUE;
   private long restoreStartedAt = Long.MIN_VALUE;
   private float lastTickRate = 20.0F;
   private boolean active;
   private boolean hasOrigin;
   private Vec3 origin = Vec3.ZERO;

   private TimeDistortionEffects() {
   }

   /** Kleine Vorladebewegung beim lokalen Rechtsklick; der große Impuls wartet auf den Server. */
   public void beginUse() {
      this.useStartedAt = Util.getMillis();
   }

   /** Übernimmt den räumlichen, echtzeitgenauen Zustand vom Server. */
   public void handle(TimeDistortionPayload payload) {
      long now = Util.getMillis();
      if (!payload.active()) {
         beginRestore(now);
         this.lastTickRate = 20.0F;
         return;
      }

      long remaining = Math.max(1L, payload.remainingMillis());
      long elapsed = Math.max(0L, FULL_DURATION_MILLIS - remaining);
      this.active = true;
      this.activeStartedAt = now - elapsed;
      this.activeUntil = now + remaining;
      this.restoreStartedAt = Long.MIN_VALUE;
      this.burstStartedAt = payload.burst() ? now : Long.MIN_VALUE;
      this.origin = new Vec3(payload.x(), payload.y(), payload.z());
      this.hasOrigin = true;
      this.lastTickRate = ACTIVE_TICK_RATE;
   }

   /** Fallback direkt aus dem Vanilla-Tickraten-Paket-Mixin. */
   public void onTickRate(float tickRate) {
      boolean wasDistorted = Math.abs(this.lastTickRate - ACTIVE_TICK_RATE) <= 0.01F;
      boolean isDistorted = Math.abs(tickRate - ACTIVE_TICK_RATE) <= 0.01F;
      long now = Util.getMillis();
      if (isDistorted && !wasDistorted && !this.active) {
         this.active = true;
         this.activeStartedAt = now;
         this.activeUntil = now + FULL_DURATION_MILLIS;
         this.burstStartedAt = now;
         this.restoreStartedAt = Long.MIN_VALUE;
         this.hasOrigin = false;
      } else if (!isDistorted && wasDistorted) {
         beginRestore(now);
      }
      this.lastTickRate = tickRate;
   }

   private void beginRestore(long now) {
      if (this.active || this.restoreStartedAt == Long.MIN_VALUE) {
         this.restoreStartedAt = now;
      }
      this.active = false;
      this.activeUntil = Long.MIN_VALUE;
      this.burstStartedAt = Long.MIN_VALUE;
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
      float field = isActive() ? 1.45F : 1.0F;
      poseStack.translate(sway * 0.0045F * field, hover * 0.010F * field, 0.0F);
      poseStack.mulPose(Axis.ZP.rotationDegrees(sway * 1.35F * field));
      poseStack.mulPose(Axis.YP.rotationDegrees(hover * 0.75F * field));

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

   /** Ein einziges Post-Programm; seine Werte werden vom {@code PostPass}-Mixin pro Frame gesetzt. */
   public @Nullable Identifier currentPostEffect() {
      refreshDeadline();
      if (this.active) {
         return CHRONO_COLLAPSE;
      }
      long restoreElapsed = Util.getMillis() - this.restoreStartedAt;
      return restoreElapsed >= 0L && restoreElapsed < RESTORE_MILLIS ? CHRONO_COLLAPSE : null;
   }

   /** Schreibt die animierten Werte in Vanillas Uniformbuffer des Chrono-Passes. */
   public void writePostUniforms(@Nullable GpuBuffer buffer) {
      if (buffer == null || buffer.isClosed()) {
         return;
      }
      ShaderState state = shaderState(Util.getMillis());
      Vector2f screenOrigin = projectOrigin();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         ByteBuffer data = Std140Builder.onStack(stack, Math.toIntExact(buffer.size()))
            .putFloat(state.strength)
            .putFloat(state.chroma)
            .putFloat(state.direction)
            .putFloat(state.waveCenter)
            .putFloat(state.warmth)
            .putFloat(state.atmosphere)
            .putFloat(state.historyMix)
            .putFloat(state.pulse)
            .putVec2(screenOrigin.x, screenOrigin.y)
            .putFloat(state.elapsedSeconds)
            .putFloat(state.restoring)
            .get();
         com.mojang.blaze3d.systems.RenderSystem.getDevice()
            .createCommandEncoder().writeToBuffer(buffer.slice(), data);
      }
   }

   private ShaderState shaderState(long now) {
      if (this.active) {
         float age = Math.max(0L, now - this.activeStartedAt) / 1_000.0F;
         float burstAge = Math.max(0L, now - this.burstStartedAt) / (float) BURST_MILLIS;
         float burst = this.burstStartedAt == Long.MIN_VALUE || burstAge >= 1.0F
            ? 0.0F : square(1.0F - burstAge);
         float atmosphere = smooth(Math.clamp(age / 0.48F, 0.0F, 1.0F));
         float secondPhase = fractional(age);
         float pulse = (float) Math.exp(-secondPhase * 8.5F) * atmosphere;
         float ready = Math.clamp((now - this.activeStartedAt) / (float) HISTORY_WARMUP_MILLIS, 0.0F, 1.0F);
         float wave = burst > 0.0F ? Math.clamp(burstAge * 1.18F, 0.02F, 1.18F)
            : 0.16F + secondPhase * 0.74F;
         return new ShaderState(
            0.105F * atmosphere + 1.05F * burst + 0.035F * pulse,
            0.0014F * atmosphere + 0.015F * burst,
            -1.0F,
            wave,
            0.0F,
            atmosphere,
            ready * (0.14F * atmosphere + 0.17F * burst),
            pulse,
            age,
            0.0F);
      }

      float progress = restoreProgress(now);
      float inverse = 1.0F - progress;
      float snap = square(inverse);
      float crest = (float) Math.sin(progress * Math.PI);
      return new ShaderState(
         1.22F * snap + 0.10F * crest,
         0.018F * snap,
         1.0F,
         0.03F + progress * 1.22F,
         smooth(progress),
         inverse,
         0.30F * inverse,
         crest,
         progress * RESTORE_MILLIS / 1_000.0F,
         1.0F);
   }

   /** Räumlicher Ursprung in Bildschirm-UV; beim Zeitlupenende und bei Kopfdrehungen zentriert. */
   private Vector2f projectOrigin() {
      if (!this.active || !this.hasOrigin) {
         return new Vector2f(0.5F, 0.5F);
      }

      Minecraft client = Minecraft.getInstance();
      Camera camera = client.gameRenderer.mainCamera();
      if (!camera.isInitialized()) {
         return new Vector2f(0.5F, 0.5F);
      }

      Vec3 delta = this.origin.subtract(camera.position());
      if (delta.lengthSqr() <= 9.0) {
         return new Vector2f(0.5F, 0.5F);
      }

      Vector3fc forward = camera.forwardVector();
      double depth = delta.x * forward.x() + delta.y * forward.y() + delta.z * forward.z();
      if (depth <= 0.25) {
         return new Vector2f(0.5F, 0.5F);
      }

      Vector3fc up = camera.upVector();
      Vector3fc left = camera.leftVector();
      double horizontal = delta.x * left.x() + delta.y * left.y() + delta.z * left.z();
      double vertical = delta.x * up.x() + delta.y * up.y() + delta.z * up.z();
      double tangent = Math.tan(Math.toRadians(camera.getFov()) * 0.5);
      double aspect = Math.max(0.1, client.getWindow().getWidth() / (double) client.getWindow().getHeight());
      float x = (float) (0.5 - horizontal / (depth * tangent * aspect) * 0.5);
      float y = (float) (0.5 - vertical / (depth * tangent) * 0.5);
      if (x < 0.05F || x > 0.95F || y < 0.05F || y > 0.95F) {
         return new Vector2f(0.5F, 0.5F);
      }
      return new Vector2f(x, y);
   }

   /** Zusätzlicher Sog beim Abbremsen und eine kurze Weitung, wenn die Zeit wieder freikommt. */
   public float fovOffset() {
      refreshDeadline();
      long now = Util.getMillis();
      if (this.active) {
         float age = Math.max(0L, now - this.activeStartedAt) / 1_000.0F;
         float start = age < 0.72F ? -(float) Math.sin(age / 0.72F * Math.PI) * 0.055F : 0.0F;
         float breathing = (float) Math.sin(age * Math.PI * 2.0F) * 0.0035F * modelActivePower();
         return start + breathing - modelUrgency() * 0.012F;
      }
      float restore = restoreProgress(now);
      return restore < 1.0F ? (float) Math.sin(restore * Math.PI) * 0.082F : 0.0F;
   }

   /** Faktor für den {@code SoundEngine}-Mixin; UI, Sprache und der eigene Master-Hum bleiben klar. */
   public float soundFactor(SoundSource source) {
      if (source == SoundSource.MASTER || source == SoundSource.UI || source == SoundSource.VOICE) {
         return 1.0F;
      }
      refreshDeadline();
      if (this.active) {
         float floor = source == SoundSource.MUSIC || source == SoundSource.RECORDS ? 0.30F : 0.48F;
         return 1.0F + (floor - 1.0F) * modelActivePower();
      }
      float restore = restoreProgress(Util.getMillis());
      return restore < 1.0F ? 0.48F + 0.52F * smooth(restore) : 1.0F;
   }

   /** Faktor für die Frequenzabsenkung (Pitch-Drop / Zeitlupen-Dumpfklang) in SoundEngine. */
   public float soundPitchFactor(SoundSource source) {
      if (source == SoundSource.MASTER || source == SoundSource.UI || source == SoundSource.VOICE) {
         return 1.0F;
      }
      refreshDeadline();
      if (this.active) {
         return 1.0F + (0.55F - 1.0F) * modelActivePower();
      }
      float restore = restoreProgress(Util.getMillis());
      return restore < 1.0F ? 0.55F + 0.45F * smooth(restore) : 1.0F;
   }

   public boolean isActive() {
      refreshDeadline();
      return this.active;
   }

   public boolean isVisible() {
      return currentPostEffect() != null;
   }

   public float remainingFraction() {
      if (!isActive()) {
         return 0.0F;
      }
      return Math.clamp((this.activeUntil - Util.getMillis()) / (float) FULL_DURATION_MILLIS, 0.0F, 1.0F);
   }

   public float remainingSeconds() {
      return isActive() ? Math.max(0L, this.activeUntil - Util.getMillis()) / 1_000.0F : 0.0F;
   }

   public float activationProgress() {
      if (!isActive()) {
         return 0.0F;
      }
      return smooth(Math.clamp((Util.getMillis() - this.activeStartedAt) / 620.0F, 0.0F, 1.0F));
   }

   public float modelActivePower() {
      if (!this.active) {
         float restore = restoreProgress(Util.getMillis());
         return restore < 1.0F ? 1.0F - restore : 0.0F;
      }
      return smooth(Math.clamp((Util.getMillis() - this.activeStartedAt) / 480.0F, 0.0F, 1.0F));
   }

   public float modelUrgency() {
      if (!isActive()) {
         return 0.0F;
      }
      return 1.0F - Math.clamp(remainingSeconds(), 0.0F, 1.0F);
   }

   public float modelRestoreFlash() {
      float progress = restoreProgress(Util.getMillis());
      return progress < 1.0F ? (float) Math.sin(progress * Math.PI) : 0.0F;
   }

   public void clear() {
      this.useStartedAt = Long.MIN_VALUE;
      this.activeStartedAt = Long.MIN_VALUE;
      this.activeUntil = Long.MIN_VALUE;
      this.burstStartedAt = Long.MIN_VALUE;
      this.restoreStartedAt = Long.MIN_VALUE;
      this.lastTickRate = 20.0F;
      this.active = false;
      this.hasOrigin = false;
      this.origin = Vec3.ZERO;
   }

   private void refreshDeadline() {
      long now = Util.getMillis();
      // Reserve für das Ende-Paket: verhindert einen permanenten Shader bei Verbindungsfehlern.
      if (this.active && this.activeUntil != Long.MIN_VALUE && now > this.activeUntil + 350L) {
         beginRestore(now);
      }
   }

   private float restoreProgress(long now) {
      long elapsed = now - this.restoreStartedAt;
      if (elapsed < 0L || elapsed >= RESTORE_MILLIS) {
         return 1.0F;
      }
      return Math.clamp(elapsed / (float) RESTORE_MILLIS, 0.0F, 1.0F);
   }

   private static float fractional(float value) {
      return value - (float) Math.floor(value);
   }

   private static float square(float value) {
      return value * value;
   }

   private static float smooth(float value) {
      float t = Math.clamp(value, 0.0F, 1.0F);
      return t * t * (3.0F - 2.0F * t);
   }

   private static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, path);
   }

   private record ShaderState(float strength, float chroma, float direction, float waveCenter,
                              float warmth, float atmosphere, float historyMix, float pulse,
                              float elapsedSeconds, float restoring) {
   }
}

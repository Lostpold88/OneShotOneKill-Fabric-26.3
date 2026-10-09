package com.oneshotonekill.client.effect;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
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
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.jspecify.annotations.Nullable;

/**
 * Echtzeitgesteuerte Präsentation des Zeitverzerrers auf dem Client.
 * <p>
 * <p>Der Server schickt Ursprung und verbleibende Echtzeit über ein eigenes Payload. Vanillas
 * Tickratenpaket bleibt ein Fallback, damit selbst bei einem Versionsfehler niemals die Welt
 * langsam läuft, ohne dass der Spieler eine Rückmeldung erhält. Alle Kurven laufen auf
 * monotoner Echtzeit: Der Effekt darf nicht zusammen mit den Spielticks langsamer werden.</p>
 * <p>
 * <p>Der Zustand für den Shader wird genau einmal je Bild in {@link #beginFrame()} berechnet und
 * zwischengespeichert. Beide Durchgänge mit der Gruppe {@code TemporalConfig} bekommen damit
 * dieselben Werte, und die Bildzeit wird nicht doppelt gezählt. Alle übrigen Abfragen sind reine
 * Leser; nur {@link #currentPostEffect()} und {@link #clientTick()} verschieben den Zustand.</p>
 */
@SuppressWarnings("SameParameterValue")
public final class TimeDistortionEffects {
   public static final TimeDistortionEffects INSTANCE = new TimeDistortionEffects();

   private static final float ACTIVE_TICK_RATE = 8.0F;
   private static final long FULL_DURATION_MILLIS = 7_000L;
   private static final long USE_MOTION_MILLIS = 340L;
   private static final long BURST_MILLIS = 720L;
   private static final long RESTORE_MILLIS = 1_050L;
   private static final Identifier CHRONO_COLLAPSE = id("chrono_collapse");

   /** Zeitkonstante der Verlaufsmittelung; bestimmt die Länge der Bewegungsspuren. */
   private static final float HISTORY_TAU = 0.16F;
   /** So viele Bilder lang bleibt der Verlaufspuffer gesperrt und wird neu befüllt. */
   private static final int HISTORY_WARMUP_FRAMES = 6;
   /**
    * Zeitkonstante für Ursprungsnachführung und Richtungswechsel.
    * <p>
    * <p>Deutlich träger als die übrigen Kurven. Der Ursprung ist ein Weltpunkt; jede Bewegung des
    * Spielers verschiebt seine Bildschirmlage. Bei kurzer Zeitkonstante wandert das Zentrum beim
    * Sprinten und Springen sichtbar hin und her.</p>
    */
   private static final float ORIGIN_TAU = 0.28F;
   /**
    * So lange behält der Weltursprung die Hoheit über das Bildzentrum.
    * <p>
    * <p>Die Einschlagwelle soll aus der Richtung kommen, in der sie ausgelöst wurde – das ist die
    * räumliche Information, auf die es ankommt. Das anschließende Dauerpulsieren darf dagegen
    * nicht an einem Weltpunkt kleben, sonst rutscht es beim Laufen unter dem Fadenkreuz weg.
    * Nach dieser Zeit sitzt alles fest in der Bildmitte.</p>
    */
   private static final float ORIGIN_AUTHORITY_SECONDS = 1.2F;
   private static final float WARP_TAU = 0.12F;
   /** Abstand zweier Druckwellen und ihre Lebensdauer; die Wellen überlappen sich zur Hälfte. */
   private static final float RING_INTERVAL = 0.85F;
   private static final float RING_TRAVEL = RING_INTERVAL * 2.0F;

   private long useStartedAt = Long.MIN_VALUE;
   private long activeStartedAt = Long.MIN_VALUE;
   private long activeUntil = Long.MIN_VALUE;
   private long burstStartedAt = Long.MIN_VALUE;
   private long restoreStartedAt = Long.MIN_VALUE;
   private float lastTickRate = 20.0F;
   private boolean active;
   private boolean hasOrigin;
   private Vec3 origin = Vec3.ZERO;

   // Vorführzustand. Er gehört allein dem Renderpfad und wird je Bild einmal fortgeschrieben.
   private int activationSeq;
   private int renderedSeq = -1;
   private long lastFrameNanos = Long.MIN_VALUE;
   private float showSeconds;
   private int warmupFrames;
   private float smoothOriginX = 0.5F;
   private float smoothOriginY = 0.5F;
   private float smoothFocus;
   private float smoothWarp = -1.0F;
   private ShaderState cached = ShaderState.IDLE;

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
      if (!this.active) {
         // Eine frische Aktivierung: Vorführuhr und Verlaufspuffer fangen bei null an, auch wenn
         // die Restzeit den Beginn zurückdatiert. Sonst zeigte ein währenddessen beitretender
         // Spieler im ersten Bild den stehengebliebenen Inhalt der vorigen Zeitlupe.
         this.activationSeq++;
      }
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
         this.activationSeq++;
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
      float field = this.active ? 1.45F : 1.0F;
      poseStack.translate(sway * 0.0045F * field, hover * 0.010F * field, 0.0F);
      poseStack.rotate(Axis.ZP.rotationDegrees(sway * 1.35F * field));
      poseStack.rotate(Axis.YP.rotationDegrees(hover * 0.75F * field));

      long elapsed = Util.getMillis() - this.useStartedAt;
      if (elapsed < 0L || elapsed > USE_MOTION_MILLIS) {
         return;
      }
      float progress = elapsed / (float) USE_MOTION_MILLIS;
      float pull = (float) Math.sin(progress * Math.PI);
      float snap = (float) Math.sin(Math.clamp((progress - 0.43F) / 0.57F, 0.0F, 1.0F) * Math.PI);
      poseStack.translate(0.0F, pull * 0.018F, -pull * 0.095F + snap * 0.052F);
      poseStack.rotate(Axis.XP.rotationDegrees(-pull * 7.0F + snap * 10.0F));
      poseStack.rotate(Axis.ZP.rotationDegrees(snap * -5.5F));
   }

   /**
    * Ein einziges Post-Programm; seine Werte werden vom {@code PostPass}-Mixin pro Frame gesetzt.
    * <p>
    * <p>Das ist zugleich der eine Punkt im Bild, an dem die Frist geprüft wird.</p>
    */
   public @Nullable Identifier currentPostEffect() {
      refreshDeadline();
      return isVisible() ? CHRONO_COLLAPSE : null;
   }

   /** Einmal je Tick, damit die Frist auch ohne laufendes Postprocessing ablaufen kann. */
   public void clientTick() {
      refreshDeadline();
   }

   /**
    * Schreibt den Vorführzustand für dieses Bild fort.
    * <p>
    * <p>Die Bildzeit kommt aus {@link Util#getNanos()} und nicht aus dem Tickzähler: Bei acht TPS
    * liefe sonst jede Kurve selbst in Zeitlupe.</p>
    */
   public void beginFrame() {
      long now = Util.getMillis();
      long nanos = Util.getNanos();
      float delta = this.lastFrameNanos == Long.MIN_VALUE
         ? 1.0F / 60.0F
         : Math.clamp((nanos - this.lastFrameNanos) / 1_000_000_000.0F, 1.0F / 480.0F, 0.2F);
      this.lastFrameNanos = nanos;

      if (this.renderedSeq != this.activationSeq) {
         this.renderedSeq = this.activationSeq;
         this.showSeconds = 0.0F;
         this.warmupFrames = HISTORY_WARMUP_FRAMES;
         this.smoothOriginX = 0.5F;
         this.smoothOriginY = 0.5F;
         this.smoothFocus = 0.0F;
         this.smoothWarp = -1.0F;
      } else {
         // Eine einzige monotone Uhr über Zeitlupe und Rücklauf hinweg. Ein Rücksprung ließe
         // jede Welle, jedes Flimmern und jeden Riss auf einen Schlag die Phase wechseln.
         this.showSeconds += delta;
      }

      updateOrigin(delta);
      float warpTarget = this.active ? -1.0F : 1.0F;
      this.smoothWarp += (warpTarget - this.smoothWarp) * approach(delta, WARP_TAU);

      float historyAlpha;
      if (this.warmupFrames > 0) {
         this.warmupFrames--;
         // Die ersten beiden Bilder ersetzen den Puffer vollständig; danach beginnt die Mittelung.
         historyAlpha = this.warmupFrames >= HISTORY_WARMUP_FRAMES - 2 ? 1.0F : approach(delta, HISTORY_TAU);
      } else {
         historyAlpha = approach(delta, HISTORY_TAU);
      }
      float historyReady = 1.0F - this.warmupFrames / (float) HISTORY_WARMUP_FRAMES;

      this.cached = buildState(now, historyAlpha, historyReady);
   }

   /** Schreibt die animierten Werte in Vanillas Uniformbuffer des Chrono-Passes. */
   public void writePostUniforms(@Nullable GpuBuffer buffer) {
      if (buffer == null || buffer.isClosed()) {
         return;
      }
      ShaderState state = this.cached;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         ByteBuffer data = Std140Builder.onStack(stack, Math.toIntExact(buffer.size()))
            .putFloat(state.strength)
            .putFloat(state.chroma)
            .putFloat(state.warpDirection)
            .putFloat(state.warmth)
            .putFloat(state.atmosphere)
            .putFloat(state.historyMix)
            .putFloat(state.pulse)
            .putFloat(state.restoring)
            .putVec2(state.originX, state.originY)
            .putFloat(state.originFocus)
            .putFloat(state.elapsed)
            .putVec2(state.ringRadiusA, state.ringRadiusB)
            .putVec2(state.ringEnergyA, state.ringEnergyB)
            .putFloat(state.historyAlpha)
            .putFloat(state.radialBlur)
            .putFloat(state.pinch)
            .putFloat(state.sweepAngle)
            .putFloat(state.sweepEnergy)
            .putFloat(state.trailLength)
            .putFloat(state.grain)
            .putFloat(state.rimGain)
            .get();
         com.mojang.blaze3d.systems.RenderSystem.getDevice()
            .createCommandEncoder().writeToBuffer(buffer.slice(), data);
      }
   }

   private ShaderState buildState(long now, float historyAlpha, float historyReady) {
      if (this.active) {
         float burstAge = Math.max(0L, now - this.burstStartedAt) / (float) BURST_MILLIS;
         float burst = this.burstStartedAt == Long.MIN_VALUE || burstAge >= 1.0F
            ? 0.0F : square(1.0F - burstAge);
         float atmosphere = smooth(Math.clamp(this.showSeconds / 0.48F, 0.0F, 1.0F));
         float secondPhase = fractional(this.showSeconds);
         float pulse = (float) Math.exp(-secondPhase * 8.5F) * atmosphere;
         float urgency = modelUrgency();
         float warmth = smooth(urgency) * 0.45F;

         // Zwei laufende Wellen. Die jüngste wird gerade geboren, die ältere klingt aus; keine
         // von beiden springt, weil Energie und Radius am Anfang und Ende bei null liegen.
         float cycle = this.showSeconds / RING_INTERVAL;
         int newest = (int) Math.floor(cycle);
         float ageA = ringAge(cycle, newest);
         float ageB = ringAge(cycle, newest - 1);
         float energyA = ringEnergy(ageA, newest) + burst;
         float energyB = ringEnergy(ageB, newest - 1);

         float strength = 0.105F * atmosphere + 1.05F * burst + 0.035F * pulse + 0.05F * urgency;
         return new ShaderState(
            strength,
            0.0020F * atmosphere + 0.024F * burst + 0.0030F * urgency,
            this.smoothWarp,
            warmth,
            atmosphere,
            (0.62F * atmosphere + 0.25F * burst) * historyReady,
            pulse,
            0.0F,
            this.smoothOriginX,
            this.smoothOriginY,
            this.smoothFocus,
            this.showSeconds,
            0.02F + ageA * 1.16F,
            0.02F + ageB * 1.16F,
            energyA,
            energyB,
            historyAlpha,
            0.016F * burst + 0.0016F * atmosphere + 0.006F * urgency,
            0.048F * burst + 0.0035F * atmosphere + 0.009F * urgency,
            sweepAngle(),
            atmosphere * (0.55F + 0.45F * urgency),
            1.0F + 2.2F * burst + 1.6F * urgency,
            0.012F * atmosphere,
            (0.30F + 0.35F * strength) * atmosphere);
      }

      float progress = restoreProgress(now);
      float inverse = 1.0F - progress;
      float snap = square(inverse);
      float crest = (float) Math.sin(progress * Math.PI);
      return new ShaderState(
         1.22F * snap + 0.10F * crest,
         0.018F * snap,
         this.smoothWarp,
         smooth(progress),
         inverse,
         0.55F * inverse * historyReady,
         crest,
         1.0F,
         this.smoothOriginX,
         this.smoothOriginY,
         this.smoothFocus,
         this.showSeconds,
         0.03F + progress * 1.22F,
         0.0F,
         crest,
         0.0F,
         historyAlpha,
         0.016F * snap,
         -0.055F * snap,
         sweepAngle(),
         0.0F,
         1.0F + 2.4F * snap,
         0.010F * inverse,
         0.25F * inverse);
   }


   /**
    * Der Zeiger dreht sich einmal je Sekunde und liegt damit auf demselben Takt wie Puls und
    * Herzschlag. Der Wert wird auf einen Vollkreis gefaltet; der Shader normalisiert die Differenz
    * ohnehin auf -PI..PI, ein Ueberlauf ist dort also unsichtbar.
    */
   private float sweepAngle() {
      return (float) ((this.showSeconds * Math.PI * 2.0) % (Math.PI * 2.0));
   }

   private static float ringAge(float cycle, int index) {
      return Math.clamp((cycle - index) * RING_INTERVAL / RING_TRAVEL, 0.0F, 1.0F);
   }

   /** Wellen vor dem Aktivierungszeitpunkt hat es nie gegeben; sonst stünde bei null schon eine. */
   private static float ringEnergy(float age, int index) {
      return index < 0 ? 0.0F : (float) Math.sin(age * Math.PI);
   }

   /**
    * Führt den Bildschirmursprung weich nach.
    * <p>
    * <p>Früher schnappte er an drei harten Kanten in die Mitte – bei drei Blöcken Abstand, hinter
    * der Kamera und am Bildrand. Jede Kopfdrehung ließ das Wellenzentrum in einem Bild springen.
    * Stattdessen wird der Punkt auf einen Kreis um die Mitte geklemmt, mit einem weichen Gewicht
    * versehen und exponentiell nachgezogen.</p>
    */
   private void updateOrigin(float delta) {
      float targetX = 0.5F;
      float targetY = 0.5F;
      float targetFocus = 0.0F;

      if (this.active && this.hasOrigin) {
         Minecraft client = Minecraft.getInstance();
         Camera camera = client.gameRenderer.mainCamera();
         if (camera.isInitialized()) {
            Vec3 delta3 = this.origin.subtract(camera.position());
            double distance = delta3.length();
            Vector3fc forward = camera.forwardVector();
            double depth = delta3.x * forward.x() + delta3.y * forward.y() + delta3.z * forward.z();
            if (depth > 0.05 && distance > 0.01) {
               Vector3fc up = camera.upVector();
               Vector3fc left = camera.leftVector();
               double horizontal = delta3.x * left.x() + delta3.y * left.y() + delta3.z * left.z();
               double vertical = delta3.x * up.x() + delta3.y * up.y() + delta3.z * up.z();
               double tangent = Math.tan(Math.toRadians(camera.getFov()) * 0.5);
               double aspect = Math.max(0.1,
                  client.getWindow().getWidth() / (double) client.getWindow().getHeight());
               float offsetX = (float) (-horizontal / (depth * tangent * aspect) * 0.5);
               float offsetY = (float) (-vertical / (depth * tangent) * 0.5);
               float length = (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY);
               if (length > 0.40F) {
                  float scale = 0.40F / length;
                  offsetX *= scale;
                  offsetY *= scale;
               }
               targetX = 0.5F + offsetX;
               targetY = 0.5F + offsetY;
               targetFocus = smooth(Math.clamp(((float) depth - 0.4F) / 1.6F, 0.0F, 1.0F))
                  * smooth(Math.clamp(((float) distance - 2.0F) / 3.5F, 0.0F, 1.0F));
            }
         }
      }

      // Die Hoheit des Weltpunktes klingt über die ersten Sekunden ab; danach liegt der Ursprung
      // fest in der Bildmitte und folgt der Bewegung des Spielers nicht mehr.
      float authority = this.active
         ? 1.0F - smooth(Math.clamp(this.showSeconds / ORIGIN_AUTHORITY_SECONDS, 0.0F, 1.0F))
         : 0.0F;
      float weight = targetFocus * authority;
      targetX = 0.5F + (targetX - 0.5F) * weight;
      targetY = 0.5F + (targetY - 0.5F) * weight;
      targetFocus = weight;
      float rate = approach(delta, ORIGIN_TAU);
      this.smoothOriginX += (targetX - this.smoothOriginX) * rate;
      this.smoothOriginY += (targetY - this.smoothOriginY) * rate;
      this.smoothFocus += (targetFocus - this.smoothFocus) * rate;
   }

   /** Zusätzlicher Sog beim Abbremsen und eine kurze Weitung, wenn die Zeit wieder freikommt. */
   public float fovOffset() {
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
      if (this.active) {
         return 1.0F + (0.55F - 1.0F) * modelActivePower();
      }
      float restore = restoreProgress(Util.getMillis());
      return restore < 1.0F ? 0.55F + 0.45F * smooth(restore) : 1.0F;
   }

   public boolean isActive() {
      return this.active;
   }

   public boolean isVisible() {
      return this.active || restoreProgress(Util.getMillis()) < 1.0F;
   }

   public float remainingFraction() {
      if (!this.active) {
         return 0.0F;
      }
      return Math.clamp((this.activeUntil - Util.getMillis()) / (float) FULL_DURATION_MILLIS, 0.0F, 1.0F);
   }

   public float remainingSeconds() {
      return this.active ? Math.max(0L, this.activeUntil - Util.getMillis()) / 1_000.0F : 0.0F;
   }

   public float activationProgress() {
      if (!this.active) {
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
      if (!this.active) {
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
      this.lastFrameNanos = Long.MIN_VALUE;
      this.showSeconds = 0.0F;
      this.warmupFrames = 0;
      this.smoothOriginX = 0.5F;
      this.smoothOriginY = 0.5F;
      this.smoothFocus = 0.0F;
      this.smoothWarp = -1.0F;
      this.cached = ShaderState.IDLE;
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

   /** Bildratenunabhängiger Annäherungsfaktor für eine exponentielle Nachführung. */
   private static float approach(float delta, float tau) {
      return 1.0F - (float) Math.exp(-delta / tau);
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

   /** Reihenfolge und Anzahl müssen exakt der Gruppe {@code TemporalConfig} im Shader entsprechen. */
   private record ShaderState(float strength, float chroma, float warpDirection, float warmth,
                              float atmosphere, float historyMix, float pulse, float restoring,
                              float originX, float originY, float originFocus, float elapsed,
                              float ringRadiusA, float ringRadiusB, float ringEnergyA,
                              float ringEnergyB, float historyAlpha,
                              float radialBlur, float pinch, float sweepAngle,
                              float sweepEnergy, float trailLength, float grain, float rimGain) {
      private static final ShaderState IDLE = new ShaderState(
         0.0F, 0.0F, -1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
         0.5F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
         0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F);
   }
}

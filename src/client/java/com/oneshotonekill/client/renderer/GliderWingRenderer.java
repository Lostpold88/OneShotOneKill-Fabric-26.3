package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * Die Tragflächen des Gleitflugs – auf jedem Client gezeichnet, nicht als Entity.
 * <p>
 * <p>Vorher hingen zwei {@code Display.ItemDisplay} am Spieler. Jede Entity läuft über eigene
 * Positionspakete und wird zwischen zwei Ticks interpoliert; bei Fluggeschwindigkeit hinkten die
 * Flügel dadurch sichtbar hinterher. Hier setzt sie jeder Client unmittelbar an die
 * interpolierte Position des Spielers – sie kleben, egal wie schnell er ist.</p>
 * <p>
 * <p>Gebaut wie das Magnetfeld nebenan: {@link LevelExtractionEvents#END_EXTRACTION} sammelt je
 * sichtbarem Spieler ein paar Zahlen ein, {@link LevelRenderEvents#COLLECT_SUBMITS} zeichnet daraus.
 * Zwischen beiden liegt der Wechsel in den Renderthread, deshalb wandern nur unveränderliche
 * Daten hinüber und keine Spielerobjekte.</p>
 * <p>
 * <h2>Aufbau</h2>
 * <p>
 * <p>Gezeichnet wird in zwei Durchgängen, und das ist der Kern der Sache. Der erste legt mit
 * {@code debugQuads} den festen Körper hin: Rückeneinheit, Holm, Rippen, Randbogen und die
 * Triebwerksgondeln. Der zweite setzt mit {@code lightning} das Leuchtende darüber –
 * Energiehaut, Hinterkante, Düsenflammen. Der Unterschied ist keine Kosmetik: {@code lightning}
 * mischt additiv, seine Flächen hellen also auf, statt zu verdecken, und genau daran erkennt
 * man ein Kraftfeld statt eines bemalten Bretts. Dafür ist dort die Rückseitenverwerfung an,
 * weshalb jede leuchtende Fläche zweimal geschrieben wird – einmal je Umlaufrichtung.</p>
 * <p>
 * <p>Die Flügel sind nicht starr: Sie tragen eine V-Stellung, atmen langsam auf und ab und
 * ziehen ihre Pfeilung mit der Geschwindigkeit nach hinten. Ein Flügel, der bei Tempo 0 und bei
 * Tempo 1,4 gleich aussieht, wirkt wie ein Aufkleber.</p>
 */
@SuppressWarnings({"resource", "SameParameterValue"})
public final class GliderWingRenderer {
   private static final RenderStateDataKey<List<WingFrame>> FRAMES =
      RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":glider_wings");

   /** Höhe der Aufhängung über den Füßen des Spielers, in Blöcken. */
   private static final float SHOULDER = 1.32F;

   // --- Grundriss eines Flügels, alles in Blöcken ---
   /** Spannweite ab der Wurzel. */
   private static final float SPAN = 1.62F;
   /** Abstand der Wurzel von der Körpermitte. */
   private static final float ROOT_OUT = 0.32F;
   /** Vorderkante der Wurzel, gemessen von der Aufhängung nach vorn. */
   private static final float LEAD = 0.30F;
   private static final float ROOT_CHORD = 0.74F;
   private static final float TIP_CHORD = 0.26F;
   /** Pfeilung bei stehendem Flug; mit dem Tempo wächst sie. */
   private static final float SWEEP = 0.46F;
   private static final float SWEEP_PER_SPEED = 0.30F;
   /** V-Stellung: so weit hebt sich die Spitze gegenüber der Wurzel. */
   private static final float DIHEDRAL = 0.30F;
   /** Halbe Dicke an der Wurzel; zur Spitze hin läuft sie aus. */
   private static final float HALF_THICKNESS = 0.048F;
   /** Anteil der Tiefe, den der feste Holm einnimmt – der Rest ist Energiehaut. */
   private static final float SPAR_SHARE = 0.26F;
   /** So viele Felder je Flügel. Mehr glättet die Pfeilung, kostet aber Ecken. */
   private static final int PANELS = 7;
   /** Der Randbogen an der Spitze, nach oben gestellt. */
   private static final float WINGLET_RISE = 0.30F;

   // --- Rückeneinheit ---
   private static final float PACK_HALF_WIDTH = 0.20F;
   private static final float PACK_TOP = 0.30F;
   private static final float PACK_BOTTOM = -0.26F;
   private static final float PACK_FRONT = -0.14F;
   private static final float PACK_BACK = -0.38F;

   // --- Triebwerke an der Flügelwurzel ---
   private static final float NOZZLE_OUT = 0.40F;
   private static final float NOZZLE_RADIUS = 0.105F;
   private static final float NOZZLE_FRONT = -0.16F;
   private static final float NOZZLE_BACK = -0.44F;
   private static final float FLAME_MIN = 0.30F;
   private static final float FLAME_PER_SPEED = 1.30F;
   private static final int FLAME_STEPS = 6;

   // --- Farben ---
   private static final float[] HULL = {0.13F, 0.16F, 0.21F};
   private static final float[] HULL_LIT = {0.26F, 0.31F, 0.39F};
   private static final float[] SPAR = {0.52F, 0.60F, 0.70F};
   private static final float[] TRIM = {0.20F, 0.68F, 0.86F};
   private static final float[] MEMBRANE = {0.16F, 0.62F, 0.95F};
   private static final float[] MEMBRANE_HOT = {0.62F, 0.94F, 1.00F};
   private static final float[] EDGE_GLOW = {0.80F, 0.98F, 1.00F};
   private static final float[] FLAME_CORE = {0.86F, 0.98F, 1.00F};
   private static final float[] FLAME_TAIL = {0.10F, 0.42F, 1.00F};

   /** Schräglage in der Kurve: Grad je Grad Gierdrehung, und wie viel höchstens. */
   private static final float BANK_PER_TURN = 2.6F;
   private static final float BANK_LIMIT = 38.0F;
   /** Wie schnell die Schräglage der Kurve folgt – klein genug, dass sie nicht zappelt. */
   private static final float BANK_EASE = 0.22F;

   private static final Map<UUID, Float> intensities = new HashMap<>();
   private static final Map<UUID, Float> previousIntensities = new HashMap<>();
   private static final Map<UUID, Float> banks = new HashMap<>();
   private static final Map<UUID, Float> previousBanks = new HashMap<>();
   private static float animationTicks;

   private GliderWingRenderer() {
   }

   /**
    * Hängt die Tragflächen in den Renderdurchlauf ein.
    * <p>
    * {@code END_EXTRACTION} entspricht NeoForges {@code ExtractLevelRenderStateEvent} und
    * sammelt am Ende der Zustandserfassung die unveränderlichen Zahlen dieses Bildes ein;
    * {@code COLLECT_SUBMITS} entspricht {@code SubmitCustomGeometryEvent} und zeichnet daraus.
    */
   public static void register() {
      LevelExtractionEvents.END_EXTRACTION.register(GliderWingRenderer::onExtract);
      LevelRenderEvents.COLLECT_SUBMITS.register(GliderWingRenderer::onSubmit);
   }

   /** Fährt die Flügel weich aus und lässt sie nach dem Flug wieder einklappen. */
   public static void tick() {
      Set<UUID> active = GlideState.INSTANCE.activePlayers();
      Set<UUID> known = new HashSet<>(intensities.keySet());
      known.addAll(active);
      for (UUID uuid : known) {
         float previous = intensities.getOrDefault(uuid, 0.0F);
         previousIntensities.put(uuid, previous);
         float current = active.contains(uuid)
            ? Math.min(1.0F, previous + 0.16F)
            : Math.max(0.0F, previous - 0.24F);
         if (current <= 0.0F && !active.contains(uuid)) {
            intensities.remove(uuid);
            previousIntensities.remove(uuid);
            banks.remove(uuid);
            previousBanks.remove(uuid);
         } else {
            intensities.put(uuid, current);
         }
      }
      tickBanking();
      animationTicks += 1.0F;
   }

   /**
    * Die Schräglage folgt der Kurve – wie bei allem, was fliegt.
    * <p>
    * Gemessen wird die Gierdrehung des Körpers seit dem letzten Tick; daraus wird ein Zielwinkel
    * und der wird nachgezogen, statt ihn direkt zu setzen. Ohne dieses Nachziehen zappelten die
    * Flügel bei jeder Mausbewegung. Der Zustand liegt hier und nicht im Zeichenschritt, weil
    * eine Ableitung nach der Zeit einen Takt braucht und der Zeichenschritt beliebig oft je Tick
    * läuft.
    */
   private static void tickBanking() {
      var level = Minecraft.getInstance().level;
      if (level == null) {
         return;
      }
      for (AbstractClientPlayer player : level.players()) {
         UUID uuid = player.getUUID();
         if (!intensities.containsKey(uuid)) {
            continue;
         }
         float previous = banks.getOrDefault(uuid, 0.0F);
         previousBanks.put(uuid, previous);
         float turn = Mth.degreesDifference(player.yBodyRotO, player.yBodyRot);
         // Rechtskurve heißt Gierwinkel aufwärts; die rechte Fläche muss dabei nach unten.
         float target = Math.clamp(-turn * BANK_PER_TURN, -BANK_LIMIT, BANK_LIMIT);
         banks.put(uuid, previous + (target - previous) * BANK_EASE);
      }
   }

   public static void clear() {
      intensities.clear();
      previousIntensities.clear();
      banks.clear();
      previousBanks.clear();
      animationTicks = 0.0F;
   }

   private static void onExtract(LevelExtractionContext context) {
      float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
      var camera = context.camera().position();
      List<WingFrame> frames = new ArrayList<>();

      for (AbstractClientPlayer player : context.level().players()) {
         float visible = Mth.lerp(partialTick,
            previousIntensities.getOrDefault(player.getUUID(), 0.0F),
            intensities.getOrDefault(player.getUUID(), 0.0F));
         if (visible <= 0.001F || player.isInvisible()) {
            continue;
         }

         double x = Mth.lerp(partialTick, player.xo, player.getX());
         double y = Mth.lerp(partialTick, player.yo, player.getY());
         double z = Mth.lerp(partialTick, player.zo, player.getZ());

         // Für Gegner/andere Spieler: Nicht durch Wände rendern, wenn keine Sichtlinie besteht
         if (player != Minecraft.getInstance().player) {
            Vec3 wingAnchor = new Vec3(x, y + SHOULDER, z);
            Vec3 eyePos = new Vec3(x, y + player.getEyeHeight(), z);
            BlockHitResult hitAnchor = context.level().clip(new ClipContext(camera, wingAnchor, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            BlockHitResult hitEye = context.level().clip(new ClipContext(camera, eyePos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            if (hitAnchor.getType() != HitResult.Type.MISS && hitEye.getType() != HitResult.Type.MISS) {
               continue;
            }
         }

         // Der Körperwinkel, nicht der Kopfwinkel: sonst drehen sich die Flügel beim Umsehen mit.
         float bodyRot = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
         float speed = (float) Math.min(1.0, player.getDeltaMovement().length() / 1.4);
         // Die Nase kippt mit dem Steigen und Sinken – zaghaft, sonst steht der Spieler schief
         // in seinem eigenen Geschirr.
         float pitch = (float) Math.clamp(player.getDeltaMovement().y * 22.0, -22.0, 22.0);
         float bank = Mth.lerp(partialTick,
            previousBanks.getOrDefault(player.getUUID(), 0.0F),
            banks.getOrDefault(player.getUUID(), 0.0F));
         frames.add(new WingFrame((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z),
            bodyRot, pitch, bank, visible, speed, animationTicks + partialTick));
      }
      context.levelState().setData(FRAMES, frames.isEmpty() ? null : List.copyOf(frames));
   }

   private static void onSubmit(LevelRenderContext context) {
      LevelRenderState levelState = context.levelState();
      List<WingFrame> frames = levelState.getData(FRAMES);
      if (frames == null) {
         return;
      }

      PoseStack poseStack = context.poseStack();
      for (WingFrame frame : frames) {
         poseStack.pushPose();
         poseStack.translate(frame.x, frame.y + SHOULDER, frame.z);
         // Minecraft misst den Körperwinkel im Uhrzeigersinn von Süden; im Renderraum ist das
         // eine Drehung um die Hochachse mit umgekehrtem Vorzeichen.
         poseStack.rotate(new Quaternionf().rotationY((float) Math.toRadians(-frame.bodyRot)));
         poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(-frame.pitch)));
         poseStack.rotate(new Quaternionf().rotationZ((float) Math.toRadians(frame.bank)));

         context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
            (pose, buffer) -> {
               pack(pose.pose(), buffer, frame);
               for (int side = -1; side <= 1; side += 2) {
                  solidWing(pose.pose(), buffer, side, frame);
                  nozzle(pose.pose(), buffer, side, frame);
               }
            });
         context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(),
            (pose, buffer) -> {
               for (int side = -1; side <= 1; side += 2) {
                  membrane(pose.pose(), buffer, side, frame);
                  flame(pose.pose(), buffer, side, frame);
               }
            });
         poseStack.popPose();
      }
   }

   // -- Fester Körper --------------------------------------------------------

   /** Die Rückeneinheit zwischen den Schultern, mit einem hellen Streifen auf der Rückseite. */
   private static void pack(Matrix4fc pose, VertexConsumer buffer, WingFrame frame) {
      float grow = frame.intensity;
      float half = PACK_HALF_WIDTH * grow;
      float top = PACK_TOP * grow;
      float bottom = PACK_BOTTOM * grow;
      box(pose, buffer, -half, bottom, PACK_BACK, half, top, PACK_FRONT, HULL, HULL_LIT, 1.0F);

      // Kühlrippen als schmale Bänder auf dem Rücken der Einheit.
      for (int rib = 0; rib < 3; rib++) {
         float y = bottom + (top - bottom) * (0.28F + rib * 0.22F);
         quadBoth(pose, buffer,
            -half * 0.8F, y, PACK_BACK - 0.012F, half * 0.8F, y, PACK_BACK - 0.012F,
            half * 0.8F, y + 0.035F * grow, PACK_BACK - 0.012F, -half * 0.8F, y + 0.035F * grow, PACK_BACK - 0.012F,
            TRIM, 1.0F);
      }
   }

   /**
    * Holm, Rippen und Randbogen – alles, was am Flügel Material ist.
    * <p>
    * Der Holm liegt an der Vorderkante und nimmt nur den vorderen Teil der Tiefe ein; dahinter
    * spannt sich die Energiehaut, die {@link #membrane} zeichnet. Diese Aufteilung ist der
    * Grund, warum der Flügel überhaupt wie ein Gerät aussieht und nicht wie ein Brett.
    */
   private static void solidWing(Matrix4fc pose, VertexConsumer buffer, int side, WingFrame frame) {
      Station inner = station(side, 0.0F, frame);
      for (int index = 1; index <= PANELS; index++) {
         Station outer = station(side, index / (float) PANELS, frame);

         // Holm: ein flacher Kasten entlang der Vorderkante.
         float innerSpar = inner.lead - inner.chord * SPAR_SHARE;
         float outerSpar = outer.lead - outer.chord * SPAR_SHARE;
         quad(pose, buffer, inner.x, inner.y + inner.thick, inner.lead, outer.x, outer.y + outer.thick, outer.lead,
            outer.x, outer.y + outer.thick, outerSpar, inner.x, inner.y + inner.thick, innerSpar, SPAR, 1.0F);
         quad(pose, buffer, inner.x, inner.y - inner.thick, innerSpar, outer.x, outer.y - outer.thick, outerSpar,
            outer.x, outer.y - outer.thick, outer.lead, inner.x, inner.y - inner.thick, inner.lead, HULL, 1.0F);
         // Die Vorderkante selbst, als schmales Band zwischen Ober- und Unterseite.
         quad(pose, buffer, inner.x, inner.y + inner.thick, inner.lead, inner.x, inner.y - inner.thick, inner.lead,
            outer.x, outer.y - outer.thick, outer.lead, outer.x, outer.y + outer.thick, outer.lead, HULL_LIT, 1.0F);

         // Rippe an jedem zweiten Feld, quer über die ganze Tiefe.
         if (index % 2 == 0) {
            quadBoth(pose, buffer,
               outer.x, outer.y + outer.thick, outer.lead, outer.x, outer.y + outer.thick, outer.trail,
               outer.x, outer.y - outer.thick, outer.trail, outer.x, outer.y - outer.thick, outer.lead,
               SPAR, 1.0F);
         }
         inner = outer;
      }

      // Randbogen: ein kurzes, nach oben gestelltes Blatt an der Spitze.
      float rise = WINGLET_RISE * frame.intensity;
      quadBoth(pose, buffer,
         inner.x, inner.y, inner.lead, inner.x, inner.y + rise, inner.lead - inner.chord * 0.25F,
         inner.x, inner.y + rise, inner.lead - inner.chord * 0.75F, inner.x, inner.y, inner.trail,
         SPAR, 1.0F);
   }

   /** Die Triebwerksgondel an der Flügelwurzel: ein kurzes Achteckrohr aus Blech. */
   private static void nozzle(Matrix4fc pose, VertexConsumer buffer, int side, WingFrame frame) {
      float grow = frame.intensity;
      float centreX = side * NOZZLE_OUT * grow;
      float radius = NOZZLE_RADIUS * grow;
      float front = NOZZLE_FRONT * grow;
      float back = NOZZLE_BACK * grow;

      for (int face = 0; face < 8; face++) {
         double angleA = face * Math.PI / 4.0;
         double angleB = (face + 1) * Math.PI / 4.0;
         float ax = centreX + (float) Math.cos(angleA) * radius;
         float ay = (float) Math.sin(angleA) * radius;
         float bx = centreX + (float) Math.cos(angleB) * radius;
         float by = (float) Math.sin(angleB) * radius;
         // Der Rand hinten ist heller: dort sitzt die Düse.
         quad(pose, buffer, ax, ay, front, bx, by, front, bx, by, back, ax, ay, back,
            face % 2 == 0 ? HULL : HULL_LIT, 1.0F);
      }
      // Düsenring, damit die Flamme nicht aus dem Nichts kommt.
      for (int face = 0; face < 8; face++) {
         double angleA = face * Math.PI / 4.0;
         double angleB = (face + 1) * Math.PI / 4.0;
         quadBoth(pose, buffer,
            centreX + (float) Math.cos(angleA) * radius, (float) Math.sin(angleA) * radius, back,
            centreX + (float) Math.cos(angleB) * radius, (float) Math.sin(angleB) * radius, back,
            centreX + (float) Math.cos(angleB) * radius * 0.62F, (float) Math.sin(angleB) * radius * 0.62F, back + 0.03F,
            centreX + (float) Math.cos(angleA) * radius * 0.62F, (float) Math.sin(angleA) * radius * 0.62F, back + 0.03F,
            TRIM, 1.0F);
      }
   }

   // -- Leuchtender Anteil ---------------------------------------------------

   /**
    * Die Energiehaut zwischen Holm und Hinterkante.
    * <p>
    * Sie läuft nach außen und nach hinten durchsichtiger aus und trägt eine wandernde Welle –
    * ein gleichmäßig eingefärbtes Feld sähe aus wie farbiges Glas. Die Hinterkante bekommt zum
    * Schluss noch einen schmalen, fast weißen Streifen; der zeichnet die Silhouette nach.
    */
   private static void membrane(Matrix4fc pose, VertexConsumer buffer, int side, WingFrame frame) {
      Station inner = station(side, 0.0F, frame);
      for (int index = 1; index <= PANELS; index++) {
         float outerShare = index / (float) PANELS;
         Station outer = station(side, outerShare, frame);
         float innerSpar = inner.lead - inner.chord * SPAR_SHARE;
         float outerSpar = outer.lead - outer.chord * SPAR_SHARE;

         float innerWave = wave(frame.time, inner.share);
         float outerWave = wave(frame.time, outer.share);
         float innerAlpha = (0.30F + innerWave * 0.22F) * (1.0F - inner.share * 0.35F) * frame.intensity;
         float outerAlpha = (0.30F + outerWave * 0.22F) * (1.0F - outer.share * 0.35F) * frame.intensity;

         gradientQuadBoth(pose, buffer,
            inner.x, inner.y, innerSpar, outer.x, outer.y, outerSpar,
            outer.x, outer.y, outer.trail, inner.x, inner.y, inner.trail,
            mix(MEMBRANE, MEMBRANE_HOT, innerWave), mix(MEMBRANE, MEMBRANE_HOT, outerWave),
            innerAlpha, outerAlpha);

         // Hinterkante als eigener, heller Streifen.
         float lip = 0.035F * frame.intensity;
         gradientQuadBoth(pose, buffer,
            inner.x, inner.y, inner.trail + lip, outer.x, outer.y, outer.trail + lip,
            outer.x, outer.y, outer.trail, inner.x, inner.y, inner.trail,
            EDGE_GLOW, EDGE_GLOW,
            0.85F * frame.intensity, 0.55F * frame.intensity);
         inner = outer;
      }
   }

   /**
    * Die Düsenflamme: ein Kegel nach hinten, dessen Länge am Tempo hängt.
    * <p>
    * Vorn weiß und dicht, hinten blau und offen – und mit einem Flackern, das für beide Seiten
    * unterschiedlich läuft. Zwei gleich atmende Flammen sehen sofort nach Kopie aus.
    */
   private static void flame(Matrix4fc pose, VertexConsumer buffer, int side, WingFrame frame) {
      float grow = frame.intensity;
      float centreX = side * NOZZLE_OUT * grow;
      float start = NOZZLE_BACK * grow;
      float flicker = 0.84F + (float) Math.sin(frame.time * 1.9F + side * 2.1F) * 0.16F;
      float length = (FLAME_MIN + frame.speed * FLAME_PER_SPEED) * grow * flicker;
      float radius = NOZZLE_RADIUS * 0.78F * grow;

      for (int step = 0; step < FLAME_STEPS; step++) {
         float from = step / (float) FLAME_STEPS;
         float to = (step + 1) / (float) FLAME_STEPS;
         float[] nearColour = mix(FLAME_CORE, FLAME_TAIL, from);
         float[] farColour = mix(FLAME_CORE, FLAME_TAIL, to);
         float nearAlpha = (0.95F - from * 0.80F) * grow;
         float farAlpha = (0.95F - to * 0.80F) * grow;
         float nearRadius = radius * (1.0F - from * 0.75F);
         float farRadius = radius * (1.0F - to * 0.75F);

         for (int face = 0; face < 6; face++) {
            double angleA = face * Math.PI / 3.0;
            double angleB = (face + 1) * Math.PI / 3.0;
            gradientQuadBoth(pose, buffer,
               centreX + (float) Math.cos(angleA) * nearRadius, (float) Math.sin(angleA) * nearRadius, start - length * from,
               centreX + (float) Math.cos(angleB) * nearRadius, (float) Math.sin(angleB) * nearRadius, start - length * from,
               centreX + (float) Math.cos(angleB) * farRadius, (float) Math.sin(angleB) * farRadius, start - length * to,
               centreX + (float) Math.cos(angleA) * farRadius, (float) Math.sin(angleA) * farRadius, start - length * to,
               nearColour, farColour, nearAlpha, farAlpha);
         }
      }
   }

   // -- Rechnerei ------------------------------------------------------------

   /**
    * Ein Schnitt durch den Flügel an der Stelle {@code share} zwischen Wurzel und Spitze.
    * <p>
    * Hier steckt die ganze Form: Pfeilung mit dem Tempo, V-Stellung samt langsamem Auf und Ab,
    * Verjüngung der Tiefe und das Auslaufen der Dicke. Wer die Silhouette ändern will, ändert
    * diese Methode und sonst nichts.
    */
   private static Station station(int side, float share, WingFrame frame) {
      float span = SPAN * frame.intensity;
      float sweep = (SWEEP + SWEEP_PER_SPEED * frame.speed) * span;
      // Ein Atmen von etwa drei Zentimetern an der Spitze – zu sehen, ohne zu flattern.
      float flap = (float) Math.sin(frame.time * 0.14F + side) * 0.05F;
      float x = side * (ROOT_OUT * frame.intensity + span * share);
      float y = (DIHEDRAL + flap) * span * share * share * frame.intensity;
      float lead = LEAD * frame.intensity - sweep * share;
      float chord = (ROOT_CHORD + (TIP_CHORD - ROOT_CHORD) * share) * frame.intensity;
      float thick = HALF_THICKNESS * (1.0F - share * 0.82F) * frame.intensity;
      return new Station(share, x, y, lead, lead - chord, chord, thick);
   }

   /** Die wandernde Helligkeitswelle in der Energiehaut, zwischen null und eins. */
   private static float wave(float time, float share) {
      return 0.5F + 0.5F * (float) Math.sin(time * 0.32F - share * 4.2F);
   }

   private static float[] mix(float[] a, float[] b, float t) {
      float clamped = Math.clamp(t, 0.0F, 1.0F);
      return new float[] {
         a[0] + (b[0] - a[0]) * clamped,
         a[1] + (b[1] - a[1]) * clamped,
         a[2] + (b[2] - a[2]) * clamped,
      };
   }

   // -- Bausteine ------------------------------------------------------------

   /** Ein Quader mit heller Ober- und dunkler Unterseite. */
   private static void box(Matrix4fc pose, VertexConsumer buffer,
                           float x0, float y0, float z0, float x1, float y1, float z1,
                           float[] colour, float[] topColour, float alpha) {
      quad(pose, buffer, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, topColour, alpha);
      quad(pose, buffer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, colour, alpha);
      quad(pose, buffer, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, colour, alpha);
      quad(pose, buffer, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, colour, alpha);
      quad(pose, buffer, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, colour, alpha);
      quad(pose, buffer, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, colour, alpha);
   }

   private static void quad(Matrix4fc pose, VertexConsumer buffer,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float[] colour, float alpha) {
      vertex(pose, buffer, ax, ay, az, colour, alpha);
      vertex(pose, buffer, bx, by, bz, colour, alpha);
      vertex(pose, buffer, cx, cy, cz, colour, alpha);
      vertex(pose, buffer, dx, dy, dz, colour, alpha);
   }

   /**
    * Dieselbe Fläche in beiden Umlaufrichtungen.
    * <p>
    * {@code RenderPipelines.LIGHTNING} verwirft Rückseiten, und eine Tragfläche wird von beiden
    * Seiten gesehen. Auch im festen Durchgang steht das für alles, was nur eine Fläche dick ist
    * – Rippen und Randbogen haben keine Rückseite, an der sich eine Umlaufrichtung festmachen
    * ließe.
    */
   private static void quadBoth(Matrix4fc pose, VertexConsumer buffer,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float cx, float cy, float cz, float dx, float dy, float dz,
                                float[] colour, float alpha) {
      quad(pose, buffer, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, colour, alpha);
      quad(pose, buffer, dx, dy, dz, cx, cy, cz, bx, by, bz, ax, ay, az, colour, alpha);
   }

   /** Wie {@link #quadBoth}, aber mit einem Farb- und Deckkraftverlauf von innen nach außen. */
   private static void gradientQuadBoth(Matrix4fc pose, VertexConsumer buffer,
                                        float ax, float ay, float az, float bx, float by, float bz,
                                        float cx, float cy, float cz, float dx, float dy, float dz,
                                        float[] innerColour, float[] outerColour,
                                        float innerAlpha, float outerAlpha) {
      for (int pass = 0; pass < 2; pass++) {
         if (pass == 0) {
            vertex(pose, buffer, ax, ay, az, innerColour, innerAlpha);
            vertex(pose, buffer, bx, by, bz, outerColour, outerAlpha);
            vertex(pose, buffer, cx, cy, cz, outerColour, outerAlpha);
            vertex(pose, buffer, dx, dy, dz, innerColour, innerAlpha);
         } else {
            vertex(pose, buffer, dx, dy, dz, innerColour, innerAlpha);
            vertex(pose, buffer, cx, cy, cz, outerColour, outerAlpha);
            vertex(pose, buffer, bx, by, bz, outerColour, outerAlpha);
            vertex(pose, buffer, ax, ay, az, innerColour, innerAlpha);
         }
      }
   }

   private static void vertex(Matrix4fc pose, VertexConsumer buffer,
                              float x, float y, float z, float[] colour, float alpha) {
      buffer.addVertex(pose, x, y, z).setColor(colour[0], colour[1], colour[2], alpha);
   }

   /** Ein Schnitt durch den Flügel; alle Werte im Zeichenraum, +Z nach vorn und +X nach rechts. */
   private record Station(float share, float x, float y, float lead, float trail, float chord, float thick) {
   }

   /** Ein paar unveränderliche Zahlen je Spieler – alles, was in den Renderthread wandert. */
   private record WingFrame(float x, float y, float z, float bodyRot, float pitch, float bank,
                            float intensity, float speed, float time) {
   }
}

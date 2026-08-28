package com.oneshotonekill.nuke;

import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.shared.Hologram;

import com.oneshotonekill.registry.ModItems;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Der Atompilz über einem Luftangriff – als Körper, nicht als Partikelwolke.
 *
 * Die erste Fassung zeichnete den Pilz aus {@code DustParticleOptions}. Sichtbar war davon fast
 * nichts: ein Staubpartikel ist ein Fleck von wenigen Pixeln, der mit dem Abstand verblasst und
 * jenseits weniger Dutzend Blöcke gar nicht mehr gezeichnet wird. Ein Atompilz ist aber genau
 * das, was man aus der Ferne sehen soll.
 *
 * Deshalb besteht er jetzt aus echten Modellen an {@link Display.ItemDisplay}-Entities: einem
 * Wolkenballen, einem Ring für die Druckwelle und einem Erdbrocken (siehe
 * {@code tools/generate_blast_3d.py}). Drei Eigenschaften tragen das:
 *
 * <ul>
 *   <li><b>Farbe zur Laufzeit.</b> Die Modelle sind fast weiß und färben sich über
 *       {@code minecraft:dye} nach dem {@code DyedItemColor} des gezeigten Stapels. Ein einziges
 *       Modell trägt damit den weißglühenden Feuerball ebenso wie den schwarzen Rauch danach –
 *       und die Glut kann über Sekunden abkühlen, statt zwischen festen Texturen zu springen.</li>
 *   <li><b>Bewegung in der Matrix.</b> Jedes Teil sitzt als Entity fest auf dem Einschlag;
 *       verschoben, gedreht und skaliert wird nur seine Transformationsmatrix. Die interpoliert
 *       der Client von sich aus, der Aufstieg läuft also weich und ohne ein einziges
 *       Bewegungspaket. Eine {@code Display} ohne gesetzte Ausmaße wird zudem nie aus dem
 *       Sichtfeld geschnitten – ein Teil bleibt sichtbar, auch wenn es zwanzig Blöcke von seiner
 *       Entity entfernt schwebt.</li>
 *   <li><b>Skalierung 1 ist ein Block.</b> Alle drei Modelle füllen genau ihre 16 Einheiten; die
 *       Größen hier unten sind deshalb unmittelbar Blöcke.</li>
 * </ul>
 *
 * Der Ablauf besteht aus sechs Teilen, die nacheinander einsetzen: Feuerball, Stiel, Hut,
 * Bodenwelle, herausgeschleuderte Brocken und der Ring der Druckwelle. An der Karte ändert das
 * nichts – der Krater kommt aus {@code ArenaDemolition}, hier entsteht nur das Bild.
 */
public final class MushroomCloud {
   public static final MushroomCloud INSTANCE = new MushroomCloud();

   /**
    * Sichtweite der Teile, in Vielfachen von 64 Blöcken
    * ({@code Display#shouldRenderAtSqrDistance}). Großzügig, denn ein Atompilz ist genau das,
    * was man vom anderen Ende der Karte aus sehen soll.
    */
   private static final float VIEW_RANGE = 6.0F;
   /** Über so viele Ticks schiebt der Client ein Teil zu seiner neuen Pose weiter. */
   private static final int INTERPOLATION_TICKS = 2;

   private static final int DURATION_TICKS = 140;
   /** Ab hier schrumpft alles zusammen, bis nichts mehr da ist. */
   private static final int FADE_START = 106;

   /** Höhe, die ein Pilz in voller Größe braucht. Maßstab für Arenen mit Decke. */
   private static final double FULL_HEIGHT = 48.0;
   private static final double MIN_SIZE = 0.30;

   // --- Feuerball ---
   private static final double FIREBALL_WIDTH = 11.0;
   private static final int FIREBALL_GROW_TICKS = 12;
   private static final int FIREBALL_END = 44;
   private static final double FIREBALL_RISE = 7.0;

   // --- Stiel ---
   private static final int STEM_PARTS = 10;
   private static final int STEM_INTERVAL_TICKS = 2;
   private static final int STEM_RISE_TICKS = 24;
   private static final double STEM_TOP = 32.0;
   private static final double STEM_WIDTH_BOTTOM = 9.5;
   private static final double STEM_WIDTH_TOP = 6.0;
   /** Seitlicher Versatz nach oben hin – ein kerzengerader Stiel wirkt wie ein Rohr. */
   private static final double STEM_SWAY = 2.0;
   /** Tick, an dem der letzte Stielballen einsetzt – ab dann steigt nur noch er. */
   private static final int LAST_STEM_BORN = 4 + (STEM_PARTS - 1) * STEM_INTERVAL_TICKS;

   /**
    * Das langsame Nachtreiben, wenn der Aufstieg vorbei ist.
    *
    * Ohne das steht der Pilz ab der dritten Sekunde bewegungslos in der Luft, und genau das
    * verrät ihn als Kulisse. Ein echter Pilz wächst noch minutenlang weiter, nur eben zäh.
    */
   private static final int DRIFT_START = 44;
   private static final int DRIFT_TICKS = 84;
   private static final double DRIFT_RISE = 7.0;
   private static final double DRIFT_SPREAD = 4.0;

   // --- Hut ---
   private static final int CAP_START = 20;
   private static final int CAP_SPREAD_TICKS = 44;
   private static final int CAP_INNER_PARTS = 6;
   private static final int CAP_OUTER_PARTS = 10;
   private static final int CAP_CROWN_PARTS = 5;
   private static final double CAP_RADIUS = 13.0;
   private static final double CAP_WIDTH = 10.0;
   /** So tief hängt der äußere Kranz am Ende – das ist die Krempe, die den Pilz ausmacht. */
   private static final double CAP_RIM_DROP = 8.0;
   private static final double CAP_LIFT = 4.0;

   // --- Bodenwelle ---
   private static final int SURGE_PARTS = 10;
   private static final int SURGE_START = 2;
   private static final int SURGE_TICKS = 46;
   private static final double SURGE_RADIUS = 18.0;
   private static final double SURGE_WIDTH = 8.0;
   private static final double SURGE_RISE = 4.5;

   // --- Brocken ---
   private static final int SHARD_PARTS = 10;
   private static final int SHARD_TICKS = 58;
   private static final double SHARD_SPEED = 1.35;
   private static final double SHARD_LIFT = 1.5;
   private static final double SHARD_GRAVITY = 0.055;
   private static final double SHARD_WIDTH = 2.4;

   // --- Druckwelle ---
   private static final int WAVE_TICKS = 34;
   private static final double WAVE_RADIUS = 38.0;
   /** Höhe des Bandes in Blöcken. */
   private static final double WAVE_BAND = 1.6;
   /**
    * Höhe des Ringmodells bei Skalierung 1.
    *
    * Steht so in der Ausgabe von {@code tools/generate_blast_3d.py} und muss mit ihr
    * übereinstimmen: der Ring wird quer und hoch verschieden skaliert, damit er beim Auslaufen
    * ein flaches Band bleibt statt zu einem Reifen anzuschwellen.
    */
   private static final double WAVE_BAND_UNIT = 0.0978;

   /** Der Feuerball kühlt von Weißglut über Gelb und Orange nach Dunkelrot ab. */
   private static final int[] HEAT = {0xFFF3D8, 0xFFD25E, 0xFF8E2E, 0xD9481C, 0x7C2A16};
   private static final int SMOKE_LIGHT = 0xB9B2A6;
   private static final int SMOKE_MID = 0x7C766C;
   private static final int SMOKE_DARK = 0x413B34;
   private static final int DUST = 0xA08863;

   private final List<Cloud> clouds = new ArrayList<>();

   private MushroomCloud() {
   }

   /**
    * Zündet einen Pilz.
    *
    * {@code headroom} ist die Höhe, die über dem Einschlag frei ist. In einer Arena mit Decke
    * wüchse der Pilz sonst durch sie hindurch; statt ihn abzuschneiden, wird er als Ganzes
    * kleiner – ein gestauchter Pilz ist immer noch ein Pilz, ein halber wäre ein Fehler.
    */
   public void detonate(ServerLevel level, Vec3 impact, double headroom) {
      clouds.add(new Cloud(level, impact, headroom));

      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4.0F, 0.42F);
      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.0F, 0.5F);
      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 2.0F, 0.55F);
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.96F, 0.88F),
         impact.x, impact.y + 3.0, impact.z, 5, 0.0, 0.0, 0.0, 0.0);
   }

   public void tick() {
      Iterator<Cloud> iterator = clouds.iterator();
      while (iterator.hasNext()) {
         Cloud cloud = iterator.next();
         if (cloud.age++ >= DURATION_TICKS) {
            cloud.dismantle();
            iterator.remove();
            continue;
         }
         cloud.draw();
      }
   }

   public void reset() {
      for (Cloud cloud : clouds) {
         cloud.dismantle();
      }
      clouds.clear();
   }

   // ---------------------------------------------------------------------
   // Farbe und Kurven
   // ---------------------------------------------------------------------

   /** Farbe der Glut zum Zeitpunkt 0 (Weißglut) bis 1 (fast erloschen), weich überblendet. */
   private static int heatAt(double share) {
      double position = Math.clamp(share, 0.0, 1.0) * (HEAT.length - 1);
      int low = Math.min(HEAT.length - 2, (int) position);
      return mix(HEAT[low], HEAT[low + 1], position - low);
   }

   /** Blendet zwei Farben kanalweise; {@code share} 0 liefert die erste, 1 die zweite. */
   private static int mix(int from, int to, double share) {
      double amount = Math.clamp(share, 0.0, 1.0);
      int red = channel(from, 16) + (int) Math.round((channel(to, 16) - channel(from, 16)) * amount);
      int green = channel(from, 8) + (int) Math.round((channel(to, 8) - channel(from, 8)) * amount);
      int blue = channel(from, 0) + (int) Math.round((channel(to, 0) - channel(from, 0)) * amount);
      return (red << 16) | (green << 8) | blue;
   }

   private static int channel(int colour, int shift) {
      return (colour >> shift) & 0xFF;
   }

   private static double progress(int age, int from, int span) {
      return Math.clamp((age - from) / (double) span, 0.0, 1.0);
   }

   /** Schnell los, sanft ankommen – so bewegt sich alles an einer Explosion. */
   private static double easeOut(double share) {
      double rest = 1.0 - share;
      return 1.0 - rest * rest;
   }

   private static double lerp(double from, double to, double share) {
      return from + (to - from) * share;
   }

   // ---------------------------------------------------------------------
   // Ein Pilz
   // ---------------------------------------------------------------------

   private static final class Cloud {
      private final ServerLevel level;
      private final Vec3 impact;
      /** Größenfaktor, auf die freie Höhe eingepasst. */
      private final double size;

      private final Part fireball;
      private final Part wave;
      private final List<Part> stem = new ArrayList<>();
      private final List<Part> cap = new ArrayList<>();
      private final List<Part> crown = new ArrayList<>();
      private final List<Part> surge = new ArrayList<>();
      private final List<Part> shards = new ArrayList<>();
      /** Wurfgeschwindigkeit je Brocken, gleiche Reihenfolge wie {@link #shards}. */
      private final List<Vec3> shardVelocity = new ArrayList<>();

      private int age;
      private int nextIndex;

      private Cloud(ServerLevel level, Vec3 impact, double headroom) {
         this.level = level;
         this.impact = impact;
         this.size = Math.clamp(headroom / FULL_HEIGHT, MIN_SIZE, 1.0);

         RandomSource random = level.getRandom();
         Item puff = ModItems.BLAST_PUFF;

         this.fireball = make(puff, 0, 0.0, random);
         this.wave = make(ModItems.BLAST_RING, 0, 0.0, random);

         for (int index = 0; index < STEM_PARTS; index++) {
            add(stem, make(puff, 4 + index * STEM_INTERVAL_TICKS, index * 1.7, random));
         }
         for (int index = 0; index < CAP_INNER_PARTS + CAP_OUTER_PARTS; index++) {
            boolean outer = index >= CAP_INNER_PARTS;
            int inRing = outer ? index - CAP_INNER_PARTS : index;
            int ringSize = outer ? CAP_OUTER_PARTS : CAP_INNER_PARTS;
            add(cap, make(puff, CAP_START + inRing % 3, inRing * Math.PI * 2.0 / ringSize, random));
         }
         for (int index = 0; index < CAP_CROWN_PARTS; index++) {
            add(crown, make(puff, CAP_START + 4 + index, index * 2.399963, random));
         }
         for (int index = 0; index < SURGE_PARTS; index++) {
            add(surge, make(puff, SURGE_START + index % 4, index * Math.PI * 2.0 / SURGE_PARTS + 0.3, random));
         }
         for (int index = 0; index < SHARD_PARTS; index++) {
            Part shard = make(ModItems.BLAST_SHARD, 0, index * 2.399963, random);
            if (shard == null) {
               continue;
            }
            shards.add(shard);
            double speed = SHARD_SPEED * (0.55 + random.nextDouble() * 0.9);
            shardVelocity.add(new Vec3(Math.cos(shard.bearing) * speed,
               SHARD_LIFT * (0.5 + random.nextDouble()), Math.sin(shard.bearing) * speed));
         }
      }

      private void add(List<Part> group, Part part) {
         if (part != null) {
            group.add(part);
         }
      }

      private Part make(Item shape, int born, double bearing, RandomSource random) {
         Display.ItemDisplay display = Hologram.spawnEffect(level, impact, new ItemStack(shape), VIEW_RANGE);
         if (display == null) {
            return null;
         }
         Vector3f axis = new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F);
         if (axis.lengthSquared() < 1.0E-4F) {
            axis.set(0.0F, 1.0F, 0.0F);
         }
         return new Part(display, shape, nextIndex++, born, bearing, random.nextDouble(),
            0.78 + random.nextDouble() * 0.44, axis.normalize(), (random.nextDouble() - 0.5) * 0.06);
      }

      private void draw() {
         poseFireball();
         poseStem();
         poseCap();
         poseSurge();
         poseShards();
         poseWave();
         sparks();
         rumble();
      }

      private void dismantle() {
         if (fireball != null) {
            Hologram.remove(fireball.display);
         }
         if (wave != null) {
            Hologram.remove(wave.display);
         }
         for (List<Part> group : List.of(stem, cap, crown, surge, shards)) {
            for (Part part : group) {
               Hologram.remove(part.display);
            }
         }
      }

      // --- die einzelnen Teile ---

      /** Der Feuerball quillt auf, kühlt ab und steigt in den Stiel hinein davon. */
      private void poseFireball() {
         if (fireball == null) {
            return;
         }
         double grow = easeOut(progress(age, 0, FIREBALL_GROW_TICKS));
         double leave = 1.0 - progress(age, FIREBALL_END - 18, 18);
         double width = FIREBALL_WIDTH * grow * leave;
         // Auf dem Boden aufsitzen statt zur Hälfte darin stecken.
         double height = 1.0 + width * 0.42 + FIREBALL_RISE * progress(age, 6, 34);
         place(fireball, 0.0, height, 0.0, width, heatAt(progress(age, 0, 34)));
      }

      /** Der Stiel wächst Ballen für Ballen nach oben und kühlt dabei von unten her aus. */
      private void poseStem() {
         for (int index = 0; index < stem.size(); index++) {
            Part part = stem.get(index);
            double share = (index + 1) / (double) STEM_PARTS;
            double rise = easeOut(progress(age, part.born, STEM_RISE_TICKS));
            double sway = STEM_SWAY * share * rise;
            double width = lerp(STEM_WIDTH_BOTTOM, STEM_WIDTH_TOP, share) * part.bulk * (0.3 + 0.7 * rise);
            double glow = Math.clamp(1.15 - share * 0.85 - (age - part.born) / 70.0, 0.0, 1.0);
            place(part,
               Math.cos(part.bearing) * sway,
               1.5 + (STEM_TOP * rise + DRIFT_RISE * drift()) * share,
               Math.sin(part.bearing) * sway,
               width,
               mix(SMOKE_MID, heatAt(0.25 + share * 0.5), glow));
         }
      }

      /**
       * Höhe der Stielspitze.
       *
       * Hut und Haube hängen daran, statt auf einer festen Höhe zu warten. Sonst steht der Hut
       * fertig oben, während der Stiel noch unterwegs ist – und dazwischen klafft eine Lücke,
       * die den Pilz zu einer Scheibe über einer Rauchsäule macht.
       */
      private double stemTop() {
         return 1.5 + STEM_TOP * easeOut(progress(age, LAST_STEM_BORN, STEM_RISE_TICKS))
            + DRIFT_RISE * drift();
      }

      /** Anteil des Nachtreibens, 0 bis 1. */
      private double drift() {
         return progress(age, DRIFT_START, DRIFT_TICKS);
      }

      /**
       * Der Hut: zwei Kränze und eine Haube darüber.
       *
       * Der äußere Kranz sackt mit dem Quadrat des Aufreißens ab. Ohne dieses Absacken entsteht
       * kein Pilz, sondern eine Scheibe auf einem Stab – an der überhängenden Krempe erkennt
       * das Auge die Form.
       */
      private void poseCap() {
         double spread = easeOut(progress(age, CAP_START, CAP_SPREAD_TICKS));
         double top = stemTop();
         double warmth = 1.0 - progress(age, CAP_START, 32);

         for (int index = 0; index < cap.size(); index++) {
            Part part = cap.get(index);
            boolean outer = index >= CAP_INNER_PARTS;
            double reach = outer ? 1.0 : 0.55;
            double radius = (1.5 + (CAP_RADIUS - 1.5) * spread + DRIFT_SPREAD * drift()) * reach * part.bulk;
            double drop = outer ? CAP_RIM_DROP * spread * spread : CAP_RIM_DROP * 0.22 * spread;
            double width = CAP_WIDTH * part.bulk * (0.35 + 0.65 * spread) * (outer ? 1.0 : 0.85);
            place(part,
               Math.cos(part.bearing) * radius,
               top + CAP_LIFT * (0.4 + 0.6 * spread) - drop,
               Math.sin(part.bearing) * radius,
               width,
               mix(outer ? SMOKE_DARK : SMOKE_MID, heatAt(0.6), warmth * (outer ? 0.45 : 1.0)));
         }

         for (Part part : crown) {
            double radius = CAP_RADIUS * 0.28 * spread * part.bulk;
            place(part,
               Math.cos(part.bearing) * radius,
               top + CAP_LIFT + 3.5 * spread + part.phase * 2.0,
               Math.sin(part.bearing) * radius,
               CAP_WIDTH * 0.8 * part.bulk * (0.35 + 0.65 * spread),
               mix(SMOKE_LIGHT, heatAt(0.5), warmth));
         }
      }

      /** Die Bodenwelle: aufgewirbelte Erde, die flach nach außen rollt. */
      private void poseSurge() {
         double push = easeOut(progress(age, SURGE_START, SURGE_TICKS));
         for (Part part : surge) {
            double radius = 2.0 + SURGE_RADIUS * push * part.bulk;
            double width = SURGE_WIDTH * part.bulk * (0.3 + 0.7 * push);
            place(part,
               Math.cos(part.bearing) * radius,
               0.4 + width * 0.34 + SURGE_RISE * push,
               Math.sin(part.bearing) * radius,
               width,
               mix(DUST, SMOKE_DARK, push));
         }
      }

      /** Herausgeschleuderte Brocken auf einer Wurfbahn – sie geben dem Einschlag Wucht. */
      private void poseShards() {
         for (int index = 0; index < shards.size(); index++) {
            Part part = shards.get(index);
            if (age > SHARD_TICKS) {
               hide(part);
               continue;
            }
            Vec3 velocity = shardVelocity.get(index);
            double height = 1.0 + velocity.y * age - 0.5 * SHARD_GRAVITY * age * age;
            place(part,
               velocity.x * age,
               Math.max(0.5, height),
               velocity.z * age,
               SHARD_WIDTH * part.bulk * (1.0 - progress(age, SHARD_TICKS - 16, 16)),
               mix(heatAt(0.75), SMOKE_DARK, progress(age, 0, 20)));
         }
      }

      /**
       * Die Druckwelle als flaches Band über dem Boden.
       *
       * Als einziges Teil wird sie ungleich skaliert: quer wächst der Kreis, hoch bleibt das
       * Band flach. Bei gleichmäßiger Skalierung würde aus dem Ring über siebzig Blöcken
       * Durchmesser ein Reifen von mehreren Blöcken Höhe.
       */
      private void poseWave() {
         if (wave == null) {
            return;
         }
         if (age > WAVE_TICKS) {
            hide(wave);
            return;
         }
         double share = progress(age, 0, WAVE_TICKS);
         float across = (float) (2.0 * WAVE_RADIUS * easeOut(share) * size);
         float band = (float) (WAVE_BAND / WAVE_BAND_UNIT * size * (1.0 - share * 0.6));
         Hologram.setPose(wave.display,
            new Vector3f(0.0F, (float) (0.9 * size), 0.0F),
            new Quaternionf(),
            new Vector3f(across, band, across),
            INTERPOLATION_TICKS);
         tint(wave, mix(SMOKE_DARK, DUST, 1.0 - share));
      }

      /**
       * Ein paar Funken und Rauchfahnen obendrauf.
       *
       * Die Modelle tragen die Form, aber Partikel können, was Modelle nicht können: flimmern.
       * Sparsam eingesetzt geben sie dem Feuerball seine Unruhe.
       */
      private void sparks() {
         if (age < 26 && (age & 1) == 0) {
            level.sendParticles(ParticleTypes.LAVA, impact.x, impact.y + 2.0 * size, impact.z,
               6, 3.0 * size, 1.4 * size, 3.0 * size, 0.06);
            level.sendParticles(ParticleTypes.SMALL_FLAME, impact.x, impact.y + 3.0 * size, impact.z,
               14, 4.5 * size, 2.4 * size, 4.5 * size, 0.05);
         }
         if (age % 6 == 0 && age < 90) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, impact.x, impact.y + 1.2 * size, impact.z,
               10, 6.0 * size, 0.8 * size, 6.0 * size, 0.02);
         }
      }

      /** Das Grollen zieht sich über den ganzen Aufstieg, statt mit dem Knall zu enden. */
      private void rumble() {
         if (age % 12 != 0 || age > 72) {
            return;
         }
         float fade = 1.0F - age / 72.0F;
         level.playSound(null, impact.x, impact.y, impact.z,
            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.6F * fade, 0.42F);
      }

      // --- Pose und Farbe eines Teils ---

      /**
       * Setzt ein Teil an seinen Platz. Maße in Blöcken, bezogen auf den Einschlag.
       *
       * Aktualisiert wird nur jeden zweiten Tick, versetzt nach Teilenummer. Der Client schiebt
       * die Matrix ohnehin über zwei Ticks weiter, sichtbar ist der Unterschied also nicht – die
       * Zahl der Pakete aber halbiert sich, und bei über vierzig Teilen je Pilz zählt das.
       */
      private void place(Part part, double x, double y, double z, double width, int colour) {
         if (part == null || age < part.born || ((age + part.index) & 1) != 0) {
            return;
         }
         float scale = (float) Math.max(Hologram.HIDDEN_SCALE, width * size * fade());
         boolean vanished = scale <= Hologram.HIDDEN_SCALE;
         if (vanished && part.hidden) {
            // Schon unsichtbar und bleibt es – ein weiteres Paket brächte nichts. Ohne diese
            // Sperre sendet ein längst erloschener Feuerball noch sekundenlang seine Pose.
            return;
         }
         part.hidden = vanished;
         Hologram.setPose(part.display,
            new Vector3f((float) (x * size), (float) (y * size), (float) (z * size)),
            new Quaternionf().rotationAxis((float) (part.phase * Math.PI * 2.0 + age * part.spin), part.axis),
            new Vector3f(scale, scale, scale),
            INTERPOLATION_TICKS);
         tint(part, colour);
      }

      private void hide(Part part) {
         if (part == null || part.hidden || ((age + part.index) & 1) != 0) {
            return;
         }
         part.hidden = true;
         Hologram.setPose(part.display, new Vector3f(), new Quaternionf(),
            new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
      }

      /**
       * Färbt ein Teil ein.
       *
       * Die Farbe steckt im Gegenstand, nicht in der Matrix – jede Änderung ist also ein eigenes
       * Paket. Deshalb wird sie auf Stufen von acht gerundet und nur bei echter Änderung gesetzt;
       * vom sekundenlangen Abkühlen bleiben so eine Handvoll Pakete übrig statt eines je Tick
       * und Teil.
       */
      private void tint(Part part, int colour) {
         int stepped = colour & 0xF8F8F8;
         if (part.colour == stepped) {
            return;
         }
         part.colour = stepped;
         ItemStack stack = new ItemStack(part.shape);
         stack.set(DataComponents.DYED_COLOR, new DyedItemColor(stepped));
         part.display.getSlot(0).set(stack);
      }

      private double fade() {
         return age < FADE_START ? 1.0 : 1.0 - progress(age, FADE_START, DURATION_TICKS - FADE_START);
      }
   }

   /**
    * Ein Bauteil der Wolke.
    *
    * Die Entity steht die ganze Zeit still auf dem Einschlag; unterwegs ist nur ihre Matrix.
    * {@code bearing}, {@code phase} und {@code bulk} sorgen dafür, dass sich die Ballen weder
    * gleichzeitig noch gleich groß bewegen – sonst sieht man vierzig Kopien desselben Modells
    * statt einer Wolke.
    */
   private static final class Part {
      private final Display.ItemDisplay display;
      private final Item shape;
      private final int index;
      private final int born;
      private final double bearing;
      private final double phase;
      private final double bulk;
      private final Vector3f axis;
      private final double spin;
      private int colour = -1;
      /** Schon auf unsichtbar gesetzt – siehe {@code Cloud#place}. Anfangs stimmt das. */
      private boolean hidden = true;

      private Part(Display.ItemDisplay display, Item shape, int index, int born, double bearing,
                   double phase, double bulk, Vector3f axis, double spin) {
         this.display = display;
         this.shape = shape;
         this.index = index;
         this.born = born;
         this.bearing = bearing;
         this.phase = phase;
         this.bulk = bulk;
         this.axis = axis;
         this.spin = spin;
      }
   }
}

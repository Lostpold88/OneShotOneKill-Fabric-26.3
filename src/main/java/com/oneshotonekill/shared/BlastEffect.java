package com.oneshotonekill.shared;


import com.oneshotonekill.registry.ModItems;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Die Explosion einer einzelnen Ladung – als Körper, nicht als Partikelwolke.
 * <p>
 * Der kleine Bruder von {@link com.oneshotonekill.nuke.MushroomCloud} und aus demselben Grund entstanden: ein
 * Partikelstoß ist nach einem Sechzigstel der Zeit vorbei, die man braucht, um hinzusehen, und
 * verblasst mit dem Abstand. Hier wachsen stattdessen ein Feuerball, ein Kranz Rauchballen und
 * ein paar Erdbrocken über gut zwei Sekunden auseinander.
 * <p>
 * Die Modelle sind dieselben wie beim Atompilz ({@code tools/generate_blast_3d.py}), nur in
 * anderer Choreografie – ein zweiter Satz Modelle wäre dieselbe Geometrie unter neuem Namen.
 * Alle Maße folgen dem Sprengradius, damit derselbe Effekt vom Explosiv-Schuss bis zur
 * C4-Ladung trägt.
 * <p>
 * Gezündet wird über {@link Blast#detonate}; getickt wird aus {@code ServerEvents}.
 */
public final class BlastEffect {
   public static final BlastEffect INSTANCE = new BlastEffect();

   private static final float VIEW_RANGE = 4.0F;
   private static final int INTERPOLATION_TICKS = 2;

   private static final int DURATION_TICKS = 46;
   /** Ab hier schrumpft alles zusammen, bis nichts mehr da ist. */
   private static final int FADE_START = 30;

   /** Der Feuerball misst ein Vielfaches des Sprengradius – er soll ihn ausfüllen, nicht andeuten. */
   private static final double CORE_WIDTH = 1.5;
   private static final int CORE_GROW_TICKS = 5;
   private static final int CORE_END = 20;

   private static final int PUFF_PARTS = 9;
   private static final int PUFF_SPREAD_TICKS = 24;
   private static final double PUFF_REACH = 1.05;
   private static final double PUFF_WIDTH = 0.85;
   private static final double PUFF_RISE = 0.45;

   private static final int SHARD_PARTS = 7;
   private static final int SHARD_TICKS = 34;
   // Wurfbahn in Vielfachen des Sprengradius. Die Werte sind so gewaehlt, dass ein Brocken
   // etwa auf halber Hoehe des Radius umkehrt und binnen anderthalb Sekunden wieder unten ist -
   // ohne das fliegen sie bei jedem groesseren Radius ueber die halbe Arena.
   private static final double SHARD_SPEED = 0.035;
   private static final double SHARD_LIFT = 0.07;
   private static final double SHARD_GRAVITY = 0.010;
   private static final double SHARD_WIDTH = 0.34;

   private static final int[] HEAT = {0xFFF6E2, 0xFFD361, 0xFF8B2C, 0xCE451B};
   private static final int SMOKE = 0x6C665D;
   private static final int SMOKE_DARK = 0x3A342E;

   private final List<Burst> bursts = new ArrayList<>();

   private BlastEffect() {
   }

   /** Zündet einen Feuerball vom angegebenen Sprengradius. */
   public void burst(ServerLevel level, Vec3 centre, double radius) {
      bursts.add(new Burst(level, centre, radius));
   }

   public void tick() {
      Iterator<Burst> iterator = bursts.iterator();
      while (iterator.hasNext()) {
         Burst burst = iterator.next();
         if (burst.age++ >= DURATION_TICKS) {
            burst.dismantle();
            iterator.remove();
            continue;
         }
         burst.draw();
      }
   }

   public void reset() {
      for (Burst burst : bursts) {
         burst.dismantle();
      }
      bursts.clear();
   }

   private static int heatAt(double share) {
      double position = Math.clamp(share, 0.0, 1.0) * (HEAT.length - 1);
      int low = Math.min(HEAT.length - 2, (int) position);
      return Hologram.mixColor(HEAT[low], HEAT[low + 1], position - low);
   }

   private static double progress(int age, int from, int span) {
      return Math.clamp((age - from) / (double) span, 0.0, 1.0);
   }

   private static double easeOut(double share) {
      double rest = 1.0 - share;
      return 1.0 - rest * rest;
   }

   private static final class Burst {
      private final ServerLevel level;
      private final Vec3 centre;
      private final double radius;

      private final Part core;
      private final List<Part> puffs = new ArrayList<>();
      private final List<Part> shards = new ArrayList<>();
      private final List<Vec3> shardVelocity = new ArrayList<>();

      private int age;
      private int nextIndex;

      private Burst(ServerLevel level, Vec3 centre, double radius) {
         this.level = level;
         this.centre = centre;
         this.radius = radius;

         RandomSource random = level.getRandom();
         Item puff = ModItems.BLAST_PUFF;
         this.core = make(puff, 0.0, random);

         for (int index = 0; index < PUFF_PARTS; index++) {
            Part part = make(puff, index * Math.PI * 2.0 / PUFF_PARTS + 0.4, random);
            if (part != null) {
               puffs.add(part);
            }
         }
         for (int index = 0; index < SHARD_PARTS; index++) {
            Part shard = make(ModItems.BLAST_SHARD, index * 2.399963, random);
            if (shard == null) {
               continue;
            }
            shards.add(shard);
            double speed = SHARD_SPEED * radius * (0.6 + random.nextDouble());
            shardVelocity.add(new Vec3(Math.cos(shard.bearing) * speed,
               SHARD_LIFT * radius * (0.5 + random.nextDouble()), Math.sin(shard.bearing) * speed));
         }
      }

      private Part make(Item shape, double bearing, RandomSource random) {
         Display.ItemDisplay display = Hologram.spawnEffect(level, centre, new ItemStack(shape), VIEW_RANGE);
         if (display == null) {
            return null;
         }
         return new Part(display, shape, nextIndex++, bearing, random.nextDouble(),
            0.75 + random.nextDouble() * 0.5, Hologram.randomRotationAxis(random));
      }

      private void draw() {
         drawCore();
         drawPuffs();
         drawShards();
      }

      private void dismantle() {
         if (core != null) {
            Hologram.remove(core.display);
         }
         for (Part part : puffs) {
            Hologram.remove(part.display);
         }
         for (Part part : shards) {
            Hologram.remove(part.display);
         }
      }

      private void drawCore() {
         double grow = easeOut(progress(age, 0, CORE_GROW_TICKS));
         double leave = 1.0 - progress(age, CORE_END - 12, 12);
         place(core, 0.0, 0.0, 0.0, CORE_WIDTH * radius * grow * leave, heatAt(progress(age, 0, 18)));
      }

      private void drawPuffs() {
         double push = easeOut(progress(age, 1, PUFF_SPREAD_TICKS));
         for (Part part : puffs) {
            double reach = PUFF_REACH * radius * push * part.bulk;
            place(part,
               Math.cos(part.bearing) * reach,
               PUFF_RISE * radius * push * (0.4 + part.phase),
               Math.sin(part.bearing) * reach,
               PUFF_WIDTH * radius * part.bulk * (0.3 + 0.7 * push),
               Hologram.mixColor(heatAt(0.5), push > 0.4 ? SMOKE_DARK : SMOKE, progress(age, 4, 20)));
         }
      }

      private void drawShards() {
         for (int index = 0; index < shards.size(); index++) {
            Part part = shards.get(index);
            if (age > SHARD_TICKS) {
               hide(part);
               continue;
            }
            Vec3 velocity = shardVelocity.get(index);
            place(part,
               velocity.x * age,
               Math.max(-radius * 0.4, velocity.y * age - 0.5 * SHARD_GRAVITY * radius * age * age),
               velocity.z * age,
               SHARD_WIDTH * radius * part.bulk * (1.0 - progress(age, SHARD_TICKS - 12, 12)),
               Hologram.mixColor(heatAt(0.8), SMOKE_DARK, progress(age, 0, 14)));
         }
      }

      private void place(Part part, double x, double y, double z, double width, int colour) {
         if (part == null || ((age + part.index) & 1) != 0) {
            return;
         }
         float scale = (float) Math.max(Hologram.HIDDEN_SCALE, width * fade());
         boolean vanished = scale <= Hologram.HIDDEN_SCALE;
         if (vanished && part.hidden) {
            return;
         }
         part.hidden = vanished;
         Hologram.setPose(part.display,
            new Vector3f((float) x, (float) y, (float) z),
            new Quaternionf().rotationAxis((float) (part.phase * Math.PI * 2.0 + age * 0.05), part.axis),
            new Vector3f(scale, scale, scale),
            INTERPOLATION_TICKS);
         part.colour = Hologram.tint(part.display, part.shape, part.colour, colour);
      }

      private void hide(Part part) {
         if (part != null && !part.hidden && ((age + part.index) & 1) == 0) {
            part.hidden = true;
            Hologram.hide(part.display);
         }
      }

      private double fade() {
         if (age < FADE_START) {
            return 1.0;
         }
         return 1.0 - progress(age, FADE_START, DURATION_TICKS - FADE_START);
      }
   }

   /** Ein Teil des Feuerballs; die Entity steht still, unterwegs ist nur ihre Matrix. */
   private static final class Part {
      private final Display.ItemDisplay display;
      private final Item shape;
      private final int index;
      private final double bearing;
      private final double phase;
      private final double bulk;
      private final Vector3f axis;
      private int colour = -1;
      private boolean hidden = true;

      private Part(Display.ItemDisplay display, Item shape, int index, double bearing,
                   double phase, double bulk, Vector3f axis) {
         this.display = display;
         this.shape = shape;
         this.index = index;
         this.bearing = bearing;
         this.phase = phase;
         this.bulk = bulk;
         this.axis = axis;
      }
   }
}

package com.oneshotonekill.shared;

import com.oneshotonekill.arena.Arena;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Sprengtrümmer: Die Blöcke eines Kraters fliegen sichtbar davon, statt einfach zu verschwinden.
 * <p>
 * Es sind reine Bildchen. Die Karte ist längst leer, wenn die Trümmer starten, und sie setzen nie
 * einen Block – ein echter {@code FallingBlock} würde beim Aufprall wieder einen Block setzen und
 * damit an {@link ArenaDemolition} und dessen Wiederherstellung vorbei die Karte verändern. Jeder
 * Brocken ist ein {@link Display.ItemDisplay} mit dem Itemmodell <em>seines</em> Blocks: Was fliegt,
 * ist genau das, was zerstört wurde. Die Flugbahn rechnet der Server, die Matrix interpoliert der
 * Client (siehe {@code Hologram#setPose}).
 * <p>
 * Ausgewählt wird nach Art: Oberflächenblöcke – das, was man vor dem Einschlag gesehen hat –
 * kommen bevorzugt dran, aber jede Blockart im Krater ist vertreten.
 */
public final class BlockDebris {
   public static final BlockDebris INSTANCE = new BlockDebris();

   /** Obergrenze der Brocken je Einschlag; mehr trüge kein Client mehr flüssig. */
   private static final int MAX_CHUNKS = 380;
   /** Anteil der Brocken, die von der Oberfläche des Kraters stammen. */
   private static final double SURFACE_SHARE = 0.6;
   /** Anteil der Brocken, die als Fontäne fast senkrecht in die Höhe schießen. */
   private static final double FOUNTAIN_SHARE = 0.16;
   private static final float VIEW_RANGE = 5.0F;
   private static final int INTERPOLATION_TICKS = 3;
   /** Pose-Update alle drei Ticks, passend zur Interpolation – bei hunderten Brocken zählt jedes Paket. */
   private static final int POSE_EVERY = 3;
   private static final double GRAVITY = 0.07;
   private static final double DRAG = 0.988;
   private static final int MAX_AGE = 100;
   /** Ab hier schrumpft ein Brocken, damit er nicht plötzlich verschwindet. */
   private static final int SHRINK_FROM = 80;
   private static final double OUTWARD_SPEED = 1.7;
   private static final double LIFT_BASE = 0.9;
   private static final double LIFT_RANDOM = 1.4;
   private static final double FOUNTAIN_LIFT = 1.9;
   private static final double FOUNTAIN_LIFT_RANDOM = 1.2;
   /** Anteil der Brocken, die beim Aufprall einen Klang abgeben – alle würden zum Prasseln verschwimmen. */
   private static final int LANDING_SOUND_ONE_IN = 7;
   /** Erst nach diesen Ticks prüft ein Brocken den Boden, sonst „landet“ er im frisch geleerten Krater. */
   private static final int LANDING_GRACE_TICKS = 4;

   private final List<Chunk> chunks = new ArrayList<>();

   private BlockDebris() {
   }

   /**
    * Schleudert gesprengte Blöcke vom Einschlag weg.
    *
    * @param centre Mittelpunkt der Sprengung
    * @param radius Kraterradius; je näher am Zentrum, desto stärker der Wurf
    * @param arena  begrenzt die Flughöhe bei Hallen mit Decke
    */
   public void launch(ServerLevel level, Arena arena, Vec3 centre, Map<BlockPos, BlockState> destroyed, int radius) {
      RandomSource random = level.getRandom();
      double ceiling = arena != null && arena.getHasCeiling() ? arena.getCeilingY() - 1.0 : Double.MAX_VALUE;

      // Oberfläche: Blöcke, über denen vorher Luft war oder die am Kraterrand lagen.
      List<Map.Entry<BlockPos, BlockState>> surface = new ArrayList<>();
      List<Map.Entry<BlockPos, BlockState>> inside = new ArrayList<>();
      for (Map.Entry<BlockPos, BlockState> entry : destroyed.entrySet()) {
         if (entry.getValue().getBlock().asItem() == Items.AIR) {
            continue;
         }
         (destroyed.containsKey(entry.getKey().above()) ? inside : surface).add(entry);
      }
      Collections.shuffle(surface, new java.util.Random(random.nextLong()));
      Collections.shuffle(inside, new java.util.Random(random.nextLong()));

      int fromSurface = Math.min(surface.size(), (int) Math.round(MAX_CHUNKS * SURFACE_SHARE));
      int fromInside = Math.min(inside.size(), MAX_CHUNKS - fromSurface);
      // Bleibt innen Platz übrig, weil der Krater flach ist, füllt die Oberfläche auf.
      fromSurface = Math.min(surface.size(), MAX_CHUNKS - fromInside);

      List<Map.Entry<BlockPos, BlockState>> picked = new ArrayList<>(fromSurface + fromInside);
      picked.addAll(surface.subList(0, fromSurface));
      picked.addAll(inside.subList(0, fromInside));

      int particleBursts = 0;
      for (Map.Entry<BlockPos, BlockState> entry : picked) {
         Item item = entry.getValue().getBlock().asItem();
         BlockPos pos = entry.getKey();
         Vec3 start = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
         Display.ItemDisplay display = Hologram.spawnEffect(level, start, new ItemStack(item), VIEW_RANGE);
         if (display == null) {
            continue;
         }

         double dx = start.x - centre.x;
         double dz = start.z - centre.z;
         double flat = Math.sqrt(dx * dx + dz * dz);
         // Genau im Zentrum gibt es keine Richtung – dann irgendeine.
         double angle = flat < 0.05 ? random.nextDouble() * Math.PI * 2.0 : Math.atan2(dz, dx);
         double nearness = 1.0 - Math.clamp(flat / Math.max(1.0, radius), 0.0, 1.0);

         double speed;
         double lift;
         if (random.nextDouble() < FOUNTAIN_SHARE) {
            // Fontäne: kaum seitlich, dafür weit hinauf.
            speed = 0.15 + random.nextDouble() * 0.45;
            lift = FOUNTAIN_LIFT + random.nextDouble() * FOUNTAIN_LIFT_RANDOM;
            angle = random.nextDouble() * Math.PI * 2.0;
         } else {
            speed = OUTWARD_SPEED * (0.3 + 0.95 * nearness) * (0.55 + random.nextDouble() * 0.9);
            lift = LIFT_BASE * (0.4 + nearness) + random.nextDouble() * LIFT_RANDOM;
            // Leichte Streuung der Richtung, damit kein sauberer Strahlenkranz entsteht.
            angle += (random.nextDouble() - 0.5) * 0.5;
         }

         // Mal ein Splitter, mal ein ganzer Brocken, selten ein Felsen.
         float size = (float) (0.45 + random.nextDouble() * 0.8 + (random.nextDouble() < 0.1 ? 0.9 : 0.0));
         boolean burning = nearness > 0.55 && random.nextDouble() < 0.5;
         Vector3f axis = Hologram.randomRotationAxis(random);
         chunks.add(new Chunk(level, display, entry.getValue(), start,
            new Vec3(Math.cos(angle) * speed, lift, Math.sin(angle) * speed),
            axis, (random.nextDouble() - 0.5) * 1.1, random.nextDouble() * Math.PI * 2.0, size, burning, ceiling));

         // Dazu ein Schwall Splitter in der Farbe des Blocks, damit der Wurf von Anfang an dicht wirkt.
         if (particleBursts < 90) {
            particleBursts++;
            double splash = 0.4 + 0.7 * nearness;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, entry.getValue()),
               start.x, start.y, start.z, 0, Math.cos(angle) * splash, 0.5 + random.nextDouble() * 0.6,
               Math.sin(angle) * splash, 1.0);
         }
      }
   }

   public void tick() {
      Iterator<Chunk> iterator = chunks.iterator();
      while (iterator.hasNext()) {
         Chunk chunk = iterator.next();
         ServerLevel level = chunk.level;
         chunk.age++;
         if (chunk.age > MAX_AGE || chunk.display.isRemoved()) {
            Hologram.remove(chunk.display);
            iterator.remove();
            continue;
         }

         chunk.velocity = new Vec3(chunk.velocity.x * DRAG, (chunk.velocity.y - GRAVITY) * DRAG, chunk.velocity.z * DRAG);
         Vec3 previous = chunk.offset;
         chunk.offset = chunk.offset.add(chunk.velocity);
         Vec3 world = chunk.origin.add(chunk.offset);

         if (world.y > chunk.ceiling && chunk.velocity.y > 0.0) {
            // Unter der Hallendecke prallt der Brocken ab, statt durch sie hindurchzufliegen.
            chunk.velocity = new Vec3(chunk.velocity.x, -chunk.velocity.y * 0.4, chunk.velocity.z);
            chunk.offset = previous;
            world = chunk.origin.add(chunk.offset);
         }

         if (chunk.age > LANDING_GRACE_TICKS && chunk.velocity.y < 0.0 && landed(level, world)) {
            // Aufschlag: Splitter in Blockfarbe, ab und zu ein Klang.
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, chunk.state),
               world.x, world.y, world.z, 5, 0.25, 0.15, 0.25, 0.08);
            if (level.getRandom().nextInt(LANDING_SOUND_ONE_IN) == 0) {
               level.playSound(null, world.x, world.y, world.z, chunk.state.getSoundType().getBreakSound(),
                  SoundSource.BLOCKS, 1.4F, 0.6F + level.getRandom().nextFloat() * 0.4F);
            }
            Hologram.remove(chunk.display);
            iterator.remove();
            continue;
         }

         if (chunk.burning && chunk.age % 3 == 0 && chunk.age < 60) {
            level.sendParticles(ParticleTypes.SMALL_FLAME, world.x, world.y, world.z, 1, 0.1, 0.1, 0.1, 0.01);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, world.x, world.y, world.z, 1, 0.12, 0.12, 0.12, 0.01);
         }

         // Versetzt nach Brockennummer, damit nicht alle Pakete im selben Tick hinausgehen.
         if ((chunk.age + chunk.id) % POSE_EVERY != 0) {
            continue;
         }
         float size = chunk.age < SHRINK_FROM ? chunk.size
            : (float) Math.max(Hologram.HIDDEN_SCALE, chunk.size * (1.0 - (chunk.age - SHRINK_FROM) / (double) (MAX_AGE - SHRINK_FROM)));
         Hologram.setPose(chunk.display,
            new Vector3f((float) chunk.offset.x, (float) chunk.offset.y, (float) chunk.offset.z),
            new Quaternionf().rotationAxis((float) (chunk.phase + chunk.age * chunk.spin), chunk.axis),
            new Vector3f(size, size, size), INTERPOLATION_TICKS);
      }
   }

   /** Liegt an der Stelle ein fester Block? Dann ist der Brocken gelandet. */
   private static boolean landed(ServerLevel level, Vec3 at) {
      return !level.getBlockState(BlockPos.containing(at)).isAir();
   }

   public void reset() {
      for (Chunk chunk : chunks) {
         Hologram.remove(chunk.display);
      }
      chunks.clear();
   }

   private static int nextId;

   private static final class Chunk {
      private final ServerLevel level;
      private final Display.ItemDisplay display;
      private final BlockState state;
      private final Vec3 origin;
      private final Vector3f axis;
      private final double spin;
      private final double phase;
      private final float size;
      private final boolean burning;
      private final double ceiling;
      private final int id = nextId++;
      private Vec3 velocity;
      private Vec3 offset = Vec3.ZERO;
      private int age;

      private Chunk(ServerLevel level, Display.ItemDisplay display, BlockState state, Vec3 origin, Vec3 velocity,
                    Vector3f axis, double spin, double phase, float size, boolean burning, double ceiling) {
         this.level = level;
         this.display = display;
         this.state = state;
         this.origin = origin;
         this.velocity = velocity;
         this.axis = axis;
         this.spin = spin;
         this.phase = phase;
         this.size = size;
         this.burning = burning;
         this.ceiling = ceiling;
      }
   }
}

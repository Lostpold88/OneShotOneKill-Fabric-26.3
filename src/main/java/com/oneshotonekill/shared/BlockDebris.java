package com.oneshotonekill.shared;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * Es sind reine Bildchen. Die Karte ist längst leer, wenn die Trümmer starten, und sie landen nie
 * als Block – ein echter {@code FallingBlock} würde beim Aufprall wieder einen Block setzen und
 * damit an {@link ArenaDemolition} und dessen Wiederherstellung vorbei die Karte verändern. Jeder
 * Brocken ist ein {@link Display.ItemDisplay} mit dem Itemmodell seines Blocks. Die Flugbahn
 * rechnet der Server, die Matrix interpoliert der Client (siehe {@code Hologram#setPose}).
 */
public final class BlockDebris {
   public static final BlockDebris INSTANCE = new BlockDebris();

   /** Mehr Brocken als das trüge kein Client, und der Krater hat ohnehin mehr Blöcke als nötig. */
   private static final int MAX_CHUNKS = 120;
   private static final float VIEW_RANGE = 4.0F;
   private static final int INTERPOLATION_TICKS = 2;
   private static final double GRAVITY = 0.075;
   private static final double DRAG = 0.985;
   private static final int MAX_AGE = 70;
   /** Ab hier schrumpft ein Brocken, damit er nicht plötzlich verschwindet. */
   private static final int SHRINK_FROM = 52;
   private static final double OUTWARD_SPEED = 1.15;
   private static final double LIFT_BASE = 0.9;
   private static final double LIFT_RANDOM = 1.5;

   private final List<Chunk> chunks = new ArrayList<>();

   private BlockDebris() {
   }

   /**
    * Schleudert eine Auswahl der gesprengten Blöcke vom Einschlag weg.
    *
    * @param centre Mittelpunkt der Sprengung
    * @param radius Kraterradius; je näher am Zentrum, desto stärker der Wurf
    */
   public void launch(ServerLevel level, Vec3 centre, Map<BlockPos, BlockState> destroyed, int radius) {
      RandomSource random = level.getRandom();
      // Jeder n-te Block genügt: die Auswahl soll den Krater füllen, nicht jeden einzelnen abbilden.
      int stride = Math.max(1, destroyed.size() / MAX_CHUNKS);
      int index = 0;
      int launched = 0;
      for (Map.Entry<BlockPos, BlockState> entry : destroyed.entrySet()) {
         if (launched >= MAX_CHUNKS) {
            break;
         }
         if (index++ % stride != 0) {
            continue;
         }
         Item item = entry.getValue().getBlock().asItem();
         if (item == Items.AIR) {
            continue;
         }

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
         double speed = OUTWARD_SPEED * (0.35 + 0.9 * nearness) * (0.7 + random.nextDouble() * 0.6);
         double lift = LIFT_BASE * (0.5 + nearness) + random.nextDouble() * LIFT_RANDOM;

         Vector3f axis = Hologram.randomRotationAxis(random);
         chunks.add(new Chunk(display, start, new Vec3(Math.cos(angle) * speed, lift, Math.sin(angle) * speed),
            axis, (random.nextDouble() - 0.5) * 0.7, random.nextDouble() * Math.PI * 2.0));
         launched++;
      }
   }

   public void tick() {
      Iterator<Chunk> iterator = chunks.iterator();
      while (iterator.hasNext()) {
         Chunk chunk = iterator.next();
         chunk.age++;
         if (chunk.age > MAX_AGE || chunk.display.isRemoved()) {
            Hologram.remove(chunk.display);
            iterator.remove();
            continue;
         }

         chunk.velocity = new Vec3(chunk.velocity.x * DRAG, (chunk.velocity.y - GRAVITY) * DRAG, chunk.velocity.z * DRAG);
         chunk.offset = chunk.offset.add(chunk.velocity);

         // Jeder zweite Tick genügt: der Client schiebt die Matrix ohnehin über zwei Ticks weiter.
         if ((chunk.age & 1) != 0) {
            continue;
         }
         float size = chunk.age < SHRINK_FROM ? 1.0F
            : (float) Math.max(Hologram.HIDDEN_SCALE, 1.0 - (chunk.age - SHRINK_FROM) / (double) (MAX_AGE - SHRINK_FROM));
         Hologram.setPose(chunk.display,
            new Vector3f((float) chunk.offset.x, (float) chunk.offset.y, (float) chunk.offset.z),
            new Quaternionf().rotationAxis((float) (chunk.phase + chunk.age * chunk.spin), chunk.axis),
            new Vector3f(size, size, size), INTERPOLATION_TICKS);
      }
   }

   public void reset() {
      for (Chunk chunk : chunks) {
         Hologram.remove(chunk.display);
      }
      chunks.clear();
   }

   private static final class Chunk {
      private final Display.ItemDisplay display;
      private final Vector3f axis;
      private final double spin;
      private final double phase;
      private Vec3 velocity;
      private Vec3 offset = Vec3.ZERO;
      private int age;

      private Chunk(Display.ItemDisplay display, Vec3 start, Vec3 velocity, Vector3f axis, double spin, double phase) {
         this.display = display;
         this.velocity = velocity;
         this.axis = axis;
         this.spin = spin;
         this.phase = phase;
      }
   }
}

package com.oneshotonekill.shared;


import com.mojang.math.Transformation;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.entity.OwnerVisibleItemDisplay;
import com.oneshotonekill.registry.ModEntities;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Sichtbare Objekte in der Arena, ohne einen einzigen Block zu setzen.
 * <p>
 * Alles, was das Minigame abstellt – C4, Frost-Falle, Bomber, Item-Boxen – ist eine
 * {@link Display.ItemDisplay}-Entity mit der Textur des jeweiligen Items. Damit bleibt die Karte
 * unangetastet und muss nach dem Match nicht zurückgesetzt werden.
 */
@SuppressWarnings({"ConstantValue", "SameParameterValue", "unused"})
public final class Hologram {
   /** Voll ausgeleuchtet, damit die Objekte auch in dunklen Ecken lesbar bleiben. */
   private static final Brightness FULL_BRIGHT = new Brightness(15, 15);
   /**
    * Größe für ein Teil, das gerade nicht zu sehen sein soll.
    * <p>
    * Nicht null, sondern ein Zehntelmillimeter: eine Matrix mit Größe null ist singulär, und
    * der Renderer bildet aus ihr die Normalenmatrix durch Invertieren. Aus null würde dort
    * NaN – unsichtbar wäre das Teil so oder so, aber der Weg dahin ginge über eine kaputte
    * Matrix im Stapel.
    */
   public static final float HIDDEN_SCALE = 1.0E-4F;
   /** Suchbereich beim Aufräumen – großzügig genug für jede Arena dieser Mod. */
   private static final AABB SWEEP_AREA = new AABB(-5000.0, -128.0, -5000.0, 5000.0, 512.0, 5000.0);

   private Hologram() {
   }

   /**
    * Ersetzt den sichtbaren Gegenstand eines Item-Displays.
    * <p>
    * Vanilla deklariert {@link Display.ItemDisplay#getSlot(int)} allgemein als nullable; für den
    * fest definierten Slot {@code 0} liefert ein Item-Display jedoch garantiert seinen Item-Slot.
    */
   public static void setItem(Display.ItemDisplay display, ItemStack stack) {
      Objects.requireNonNull(display.getSlot(0), "Item display slot 0").set(stack);
   }

   /** Liest den garantiert vorhandenen Item-Slot {@code 0}. */
   public static ItemStack item(Display.ItemDisplay display) {
      return Objects.requireNonNull(display.getSlot(0), "Item display slot 0").get();
   }

   /**
    * Die Displays, die diese Sitzung selbst aufgehängt hat.
    * <p>
    * Nur im Speicher, mit Absicht: was hier nicht steht, stammt aus einer früheren Sitzung und
    * gehört weg. Genau daran erkennt {@link #isLive} ein Überbleibsel, ohne es an der Welt
    * ablesen zu müssen – eine gespeicherte Markierung überlebte den Neustart und wäre wertlos.
    */
   private static final Set<UUID> live = new HashSet<>();

   public static Display.ItemDisplay spawn(ServerLevel level, Vec3 position, ItemStack stack, float scale, int glowArgb) {
      Display.ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
      if (display == null) {
         return null;
      }

      display.setPos(position.x, position.y, position.z);
      Hologram.setItem(display, stack);
      // Dank Access Transformer direkt aufrufbar – auf Fabric brauchte das einen Accessor-Mixin.
      display.setBrightnessOverride(FULL_BRIGHT);
      display.setViewRange(4.0F);
      display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
      if (glowArgb != 0) {
         display.setGlowColorOverride(glowArgb);
         display.setGlowingTag(true);
      }
      // Eine Interpolationsdauer sorgt dafür, dass Drehung und Bewegung weich laufen statt zu
      // springen; ohne sie ruckelt jedes Update sichtbar.
      display.setTransformationInterpolationDelay(0);
      display.setTransformationInterpolationDuration(2);
      setTransform(display, scale, 0.0F);

      live.add(display.getUUID());
      level.addFreshEntity(display);
      return display;
   }

   /**
    * Ein Display für einen Effekt: ohne Leuchtrand, mit eigener Sichtweite.
    * <p>
    * Die Sichtweite zählt in Vielfachen von 64 Blöcken ({@code Display#shouldRenderAtSqrDistance}).
    * Für einen Atompilz ist der Regelwert zu knapp – ein Einschlag, den man vom anderen Ende der
    * Arena aus nicht sieht, verfehlt seinen Zweck.
    */
   public static Display.ItemDisplay spawnEffect(ServerLevel level, Vec3 position, ItemStack stack, float viewRange) {
      return spawnEffect(level, position, stack, viewRange, true);
   }

   /**
    * Ein Display ohne Leuchtrand und ohne Helligkeits-Override.
    * <p>
    * Versteckte Geräte sollen sich in die Beleuchtung der Karte einfügen. Mit dem sonst für
    * Effekte sinnvollen {@link #FULL_BRIGHT} sähen sie im Dunkeln selbst ohne Glowing-Tag wie
    * eine Lichtquelle aus.
    */
   public static Display.ItemDisplay spawnNaturallyLit(ServerLevel level, Vec3 position, ItemStack stack,
                                                        float viewRange) {
      Display.ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
      return finishEffectSpawn(level, display, position, stack, viewRange, false);
   }

   /**
    * Natürlich beleuchtetes Display, das vorerst ausschließlich sein Besitzer erhält.
    * <p>
    * Sobald das Gerät aufgedeckt wird, ersetzt das aufrufende System diese Entity durch ein
    * gewöhnliches Display. Damit greift Vanillas Tracking-Filter bereits beim Spawnpaket.
    */
   public static OwnerVisibleItemDisplay spawnOwnerVisible(ServerLevel level, Vec3 position, ItemStack stack,
                                                            float viewRange, UUID owner) {
      return spawnOwnerVisible(level, position, stack, viewRange, owner, false);
   }

   /** Besitzerexklusives, voll ausgeleuchtetes Display für Energieeffekte wie den Reflektor-Schild. */
   public static OwnerVisibleItemDisplay spawnOwnerVisibleEffect(ServerLevel level, Vec3 position, ItemStack stack,
                                                                  float viewRange, UUID owner) {
      return spawnOwnerVisible(level, position, stack, viewRange, owner, true);
   }

   private static OwnerVisibleItemDisplay spawnOwnerVisible(ServerLevel level, Vec3 position, ItemStack stack,
                                                              float viewRange, UUID owner, boolean fullBright) {
      OwnerVisibleItemDisplay display = ModEntities.OWNER_VISIBLE_ITEM_DISPLAY
         .create(level, EntitySpawnReason.TRIGGERED);
      if (display != null) {
         display.setOwner(owner);
      }
      return finishEffectSpawn(level, display, position, stack, viewRange, fullBright);
   }

   private static Display.ItemDisplay spawnEffect(ServerLevel level, Vec3 position, ItemStack stack,
                                                   float viewRange, boolean fullBright) {
      Display.ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
      return finishEffectSpawn(level, display, position, stack, viewRange, fullBright);
   }

   private static <T extends Display.ItemDisplay> T finishEffectSpawn(ServerLevel level, T display, Vec3 position,
                                                                      ItemStack stack, float viewRange,
                                                                      boolean fullBright) {
      if (display == null) {
         return null;
      }

      display.setPos(position.x, position.y, position.z);
      Hologram.setItem(display, stack);
      if (fullBright) {
         display.setBrightnessOverride(FULL_BRIGHT);
      }
      display.setViewRange(viewRange);
      display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
      // Unsichtbar starten: die Teile eines Effekts setzen erst nacheinander ein.
      setPose(display, new Vector3f(), new org.joml.Quaternionf(),
         new Vector3f(HIDDEN_SCALE, HIDDEN_SCALE, HIDDEN_SCALE), 0);

      live.add(display.getUUID());
      level.addFreshEntity(display);
      return display;
   }

   /**
    * Volle Pose: Verschiebung, Drehung und Größe je Achse.
    * <p>
    * Die Verschiebung liegt hier bewusst in der Matrix und nicht in der Entity-Position. Der
    * Client interpoliert die Matrix von sich aus über die angegebene Dauer, während eine
    * versetzte Entity ein eigenes Bewegungspaket bräuchte und trotzdem gröber liefe. Sie zählt
    * in Weltachsen, solange die Entity selbst ungedreht steht: {@code DisplayRenderer#submit}
    * legt erst die Entity-Ausrichtung und dann diese Matrix auf den Stapel.
    * <p>
    * Ausgeschnitten wird an ihr nichts – eine {@code Display} ohne gesetzte Größe meldet
    * {@code noCulling}, das Teil bleibt also auch weit ab von seiner Entity sichtbar.
    */
   public static void setPose(Display.ItemDisplay display, Vector3f translation, org.joml.Quaternionf rotation,
                              Vector3f scale, int interpolationTicks) {
      display.setTransformationInterpolationDelay(0);
      display.setTransformationInterpolationDuration(interpolationTicks);
      display.setTransformation(new Transformation(translation, rotation, scale, new org.joml.Quaternionf()));
   }

   /** Setzt Größe und Drehung um die Hochachse. */
   public static void setTransform(Display.ItemDisplay display, float scale, float yawRadians) {
      setTransform(display, scale, yawRadians, 0.0F, 0.0F);
   }

   /** Setzt Größe, Drehung um die Hochachse und zusätzliche Neigung. */
   public static void setTransform(Display.ItemDisplay display, float scale, float yawRadians, float pitchRadians) {
      setTransform(display, scale, yawRadians, pitchRadians, 0.0F);
   }

   /** Setzt Größe, Drehung um die Hochachse, zusätzliche Neigung und vertikalen Versatz mit voller Client-Interpolation. */
   public static void setTransform(Display.ItemDisplay display, float scale, float yawRadians, float pitchRadians, float offsetY) {
      org.joml.Quaternionf rotation = new org.joml.Quaternionf().rotateY(yawRadians);
      if (pitchRadians != 0.0F) {
         rotation.rotateX(pitchRadians);
      }
      // Durch wiederholtes Setzen des Delays triggert der Client auf jedem Frame eine weiche Matrix-Interpolation
      display.setTransformationInterpolationDelay(0);
      display.setTransformationInterpolationDuration(2);
      display.setTransformation(new Transformation(
         new Vector3f(0.0F, offsetY, 0.0F),
         rotation,
         new Vector3f(scale, scale, scale),
         new org.joml.Quaternionf()));
   }

   public static void move(Display.ItemDisplay display, Vec3 position) {
      display.setPos(position.x, position.y, position.z);
   }

   public static void remove(Display.ItemDisplay display) {
      if (display == null) {
         return;
      }
      live.remove(display.getUUID());
      if (!display.isRemoved()) {
         display.discard();
      }
   }

   /**
    * Gehört dieses Display noch zu einem laufenden System dieser Sitzung?
    * <p>
    * Die Frage ist nötig, weil {@link #discardOrphans} nur geladene Chunks erreicht. Ein Display
    * in einem entladenen Chunk übersteht jede Sammelaktion, wird mit der Welt gespeichert und
    * taucht Stunden später wieder auf – dann bewegungslos und ohne Wirkung, weil kein System es
    * mehr kennt. Genau solche werden beim Laden abgefangen (siehe {@code ServerEvents}).
    */
   public static boolean isLive(Display.ItemDisplay display) {
      return live.contains(display.getUUID());
   }

   /** Erkennt ein Display dieser Mod am Namensraum des gezeigten Gegenstands. */
   public static boolean belongsToMod(Display.ItemDisplay display) {
      ItemStack shown = Hologram.item(display);
      return !shown.isEmpty()
         && BuiltInRegistries.ITEM.getKey(shown.getItem()).getNamespace().equals(OneShotOneKill.MOD_ID);
   }

   /**
    * Räumt jedes Display der Mod aus allen Welten, auch die, von denen sie nichts mehr weiß.
    * <p>
    * Die Systeme merken sich ihre Displays und entfernen sie ordentlich – solange der Server
    * ordentlich beendet wird. Stürzt er ab, während ein Bomber fliegt oder eine Ladung liegt,
    * speichert die Welt diese Entities mit. Beim nächsten Start lädt sie sie als gewöhnliche
    * Entities, aber keine Liste kennt sie mehr: sie bleiben für immer stehen.
    * <p>
    * Erkannt werden sie am Namensraum des gezeigten Gegenstands. Ein fester Katalog wäre die
    * naheliegende Alternative, ginge aber beim nächsten neuen Item still kaputt.
    * <p>
    * <p><b>Nur aufrufen, wenn nichts laufen darf</b> – beim Serverstart, Match-Stopp und
    * Arena-Reset. Mitten im Match nähme das auch die lebenden Fallen und Türme mit.
    */
   public static void discardOrphans(MinecraftServer server) {
      sweep(server, display -> true);
   }

   /**
    * Entfernt jedes Display der Mod, das zu keinem laufenden System gehört.
    * <p>
    * Der Abgleich beim Laden einer Entity reicht nicht aus: er greift nur, wenn ein Chunk neu
    * hereinkommt. Ein Überbleibsel, das beim Serverstart schon in einem geladenen Chunk stand,
    * würde nie geprüft. Deshalb läuft dieser Abgleich zusätzlich in groben Abständen mit –
    * er ist die Auffanglinie, die keinen Weg offen lässt, auf dem so ein Ding entstehen kann.
    */
   public static void sweepUntracked(MinecraftServer server) {
      sweep(server, display -> !live.contains(display.getUUID()));
   }

   private static void sweep(MinecraftServer server, Predicate<Display.ItemDisplay> extra) {
      if (server == null) {
         return;
      }
      int removed = 0;
      for (ServerLevel level : server.getAllLevels()) {
         if (level == null) {
            continue;
         }
         for (Display.ItemDisplay display : level.getEntitiesOfClass(Display.ItemDisplay.class, SWEEP_AREA,
               display -> belongsToMod(display) && extra.test(display))) {
            live.remove(display.getUUID());
            display.discard();
            removed++;
         }
      }
      if (removed > 0) {
         OneShotOneKill.INSTANCE.getLOGGER().info("{} herrenlose Display-Objekte entfernt.", removed);
      }
   }

}

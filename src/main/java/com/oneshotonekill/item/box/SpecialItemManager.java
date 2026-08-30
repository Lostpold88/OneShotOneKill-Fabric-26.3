package com.oneshotonekill.item.box;

import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.arena.RandomTpSystem;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.shared.Feedback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import com.oneshotonekill.match.ScoreboardManager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings({"ConstantValue", "resource", "UnusedReturnValue", "unused"})
public final class SpecialItemManager {
   public static final SpecialItemManager INSTANCE = new SpecialItemManager();
   /** So viele Spezial-Items bekommt, wer einen Kopfgeldträger erledigt. */
   private static final int BOUNTY_ITEMS = 2;
   private static final String WEIGHT_FILE = "osok_item_weights.txt";
   public static final int DEFAULT_WEIGHT = 10;
   public static final int MAX_WEIGHT = 1_000;
   public static final int GROUND_SPAWN_PERIOD_TICKS = 300;
   public static final int GROUND_DESPAWN_DELAY_TICKS = 1_200;
   /**
    * So viele Boxen dürfen gleichzeitig liegen.
    * <p>
    * Bei einer Box alle 30 Sekunden und einer Minute Lebensdauer waren nie mehr als zwei
    * gleichzeitig da – auf einer ganzen Karte findet man die praktisch nie. Häufiger und
    * mehrere zugleich verteilt sie über die Fläche, statt sie zur Rarität zu machen.
    */
   public static final int MAX_ACTIVE_BOXES = 6;

   private static final Map<SpecialItem, Integer> weights = new LinkedHashMap<>();
   /** Die aktiven 3D-Item-Boxen auf dem Arenaboden. */
   private static final List<GroundBox> activeBoxes = new ArrayList<>();
   private static int ticksUntilSpawn = GROUND_SPAWN_PERIOD_TICKS;
   private static SpecialItem.Mode itemMode = SpecialItem.Mode.BOTH;

   /** Umdrehung je Tick im Bogenmaß – gut sichtbar, ohne zu flimmern. */
   private static final float SPIN_PER_TICK = 0.10F;
   /** Schwebeweg nach oben und unten. Die alten 0.04 Blöcke waren schlicht nicht zu sehen. */
   private static final float BOB_AMPLITUDE = 0.22F;
   private static final float BOB_PER_TICK = 0.13F;
   /** Leichte Schräglage, damit die Box schwebt statt auf der Stelle zu kreiseln. */
   private static final float TILT = 0.16F;
   private static final float TILT_PER_TICK = 0.07F;

   private static final float BOX_SCALE = 0.85F;
   private static final float EXPIRING_SCALE = 0.72F;
   /** Ab hier funkt und schrumpft die Box als Warnung. */
   private static final int EXPIRING_TICKS = 200;

   /** Schwebehöhe der Box über dem Boden. */
   private static final double HOVER_HEIGHT = 0.70;
   /** Waagerechte Reichweite zum Einsammeln, gemessen von der Mitte der Box. */
   private static final double PICKUP_RADIUS = 1.6;
   /** Senkrechte Reichweite – großzügig, damit Springen und Fallen nicht daneben gehen. */
   private static final double PICKUP_HEIGHT = 2.6;

   /** Jede wievielte Eliminierung in Folge ein Spezial-Item bringt. */
   private static final int STREAK_REWARD_EVERY = 3;

   private SpecialItemManager() {
   }

   public SpecialItem.Mode getItemMode() {
      return itemMode;
   }

   public int getTotalWeight() {
      int total = 0;
      for (SpecialItem item : SpecialItem.values()) {
         total += weightOf(item);
      }
      return total;
   }

   public int weightOf(SpecialItem item) {
      return weights.getOrDefault(item, DEFAULT_WEIGHT);
   }

   public void setWeight(SpecialItem item, int weight) {
      weights.put(item, Math.clamp(weight, 0, MAX_WEIGHT));
      saveWeights();
   }

   /**
    * Legt die Gewichte neben die Welt.
    * <p>
    * Sie lagen bisher nur im Speicher und waren nach jedem Neustart wieder auf dem Standardwert.
    * Wer sie eingestellt hatte, sah beim nächsten Start wieder gleichverteilte Ziehungen und
    * musste annehmen, die Gewichtung wirke nicht – dabei war sie schlicht weg.
    */
   private static Path weightFile() {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      return server == null ? null : server.getWorldPath(LevelResource.ROOT).resolve(WEIGHT_FILE);
   }

   private void saveWeights() {
      Path file = weightFile();
      if (file == null) {
         return;
      }
      StringBuilder text = new StringBuilder();
      text.append(itemMode.name()).append('\n');
      for (Map.Entry<SpecialItem, Integer> entry : weights.entrySet()) {
         text.append(entry.getKey().getId()).append('=').append(entry.getValue()).append('\n');
      }
      try {
         Files.writeString(file, text.toString());
      } catch (Exception exception) {
         OneShotOneKill.INSTANCE.getLOGGER().error(
            "Itemgewichte ließen sich nicht sichern: {}", exception.getMessage());
      }
   }

   private void loadWeights() {
      Path file = weightFile();
      if (file == null || !Files.exists(file)) {
         return;
      }
      try {
         boolean first = true;
         for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
               continue;
            }
            if (first) {
               first = false;
               try {
                  itemMode = SpecialItem.Mode.valueOf(trimmed);
                  continue;
               } catch (IllegalArgumentException ignored) {
                  // Keine Betriebsart in der ersten Zeile – dann ist es schon ein Gewicht.
               }
            }
            int split = trimmed.indexOf('=');
            if (split <= 0) {
               continue;
            }
            SpecialItem item = SpecialItem.fromId(trimmed.substring(0, split));
            if (item != null) {
               weights.put(item, Math.clamp(Integer.parseInt(trimmed.substring(split + 1)), 0, MAX_WEIGHT));
            }
         }
         OneShotOneKill.INSTANCE.getLOGGER().info("{} gespeicherte Itemgewichte geladen.", weights.size());
      } catch (Exception exception) {
         OneShotOneKill.INSTANCE.getLOGGER().warn(
            "Itemgewichte nicht lesbar ({}), nutze Standardwerte.", exception.getMessage());
      }
   }

   public void resetWeights() {
      weights.clear();
      saveWeights();
   }

   public void setItemMode(SpecialItem.Mode mode) {
      itemMode = mode;
      saveWeights();
   }

   public void resetForServerSession() {
      clearGroundItems();
      // Gewichte und Betriebsart bleiben: sie sind eine Einstellung des Servers, keine
      // Momentaufnahme eines Matches. Vorher wurden sie hier gelöscht und waren nach jedem
      // Neustart wieder Standard.
      weights.clear();
      itemMode = SpecialItem.Mode.BOTH;
      loadWeights();
      ticksUntilSpawn = GROUND_SPAWN_PERIOD_TICKS;
   }

   public void tick(MinecraftServer server) {
      // Nach dem Einschlag steht das Ergebnis fest; Boxen, die dann noch im Krater erschienen,
      // waeren Ausruestung fuer eine Runde, die es nicht mehr gibt.
      boolean matchRunning = MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING
         && !MatchManager.Countdown.INSTANCE.isCountdownRunning()
         && !MatchManager.INSTANCE.isDecided()
         && MatchManager.INSTANCE.getCurrentGameMode() != MatchManager.GameMode.GUN_GAME
         && itemMode.getAllowsGroundSpawns();

      // Wenn kein Match aktiv läuft: Garantiert keine Boxen in den Welten dulden
      if (!matchRunning) {
         clearGroundItems();
         ticksUntilSpawn = GROUND_SPAWN_PERIOD_TICKS;
         return;
      }

      updateGroundBoxes(server);

      if (--ticksUntilSpawn <= 0) {
         ticksUntilSpawn = GROUND_SPAWN_PERIOD_TICKS;
         if (activeBoxes.size() < MAX_ACTIVE_BOXES) {
            spawnGroundItem(server);
         }
      }
   }

   public void clearGroundItems() {
      for (GroundBox box : activeBoxes) {
         Hologram.remove(box.display());
      }
      activeBoxes.clear();

      // Bereinige auch alle verwaisten Item-Box Displays in allen Dimensionen des Servers
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server != null) {
         for (ServerLevel level : server.getAllLevels()) {
            if (level != null) {
               AABB area = new AABB(-5000, level.getMinY(), -5000, 5000, level.getMaxY(), 5000);
               for (Display.ItemDisplay display : level.getEntitiesOfClass(Display.ItemDisplay.class, area,
                     d -> Hologram.item(d).is(ModItems.ITEM_BOX))) {
                  Hologram.remove(display);
               }
            }
         }
      }
   }

   private void spawnGroundItem(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null || worlds.getActiveLevel() == null) {
         return;
      }

      Arena arena = worlds.getActive();
      ServerLevel level = worlds.getActiveLevel();
      SpecialItem item = rollItem();
      if (item == null) {
         return;
      }

      List<Vec3> occupied = activeBoxes.stream().map(GroundBox::basePosition).toList();
      Vec3 position = RandomTpSystem.INSTANCE.findGroundItemLocation(arena, level, occupied);
      if (position == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Kein freier Bodenplatz für {} auf {}.", item.getId(), arena.getId());
         return;
      }

      // Spawnt die 3D-Box frei schwebend (Höhe 0.70 über dem Boden, Skalierung 0.85 für optimalen Blockabstand)
      Display.ItemDisplay display = Hologram.spawn(level, position.add(0.0, HOVER_HEIGHT, 0.0),
         new ItemStack(ModItems.ITEM_BOX), BOX_SCALE, 0);
      if (display == null) {
         return;
      }

      activeBoxes.add(new GroundBox(item, display, position, server.getTickCount() + GROUND_DESPAWN_DELAY_TICKS, level));
      announceDrop(level, position);
      OneShotOneKill.INSTANCE.getLOGGER().info("Spezialitem {} ist auf {} gespawnt.", item.getId(), arena.getId());
   }

   private void updateGroundBoxes(MinecraftServer server) {
      // Die Animation hängt am Servertakt statt an mitlaufenden Zählern. Damit laufen alle
      // Boxen gleich, und nach Stunden Laufzeit gibt es keine Winkel, die aus dem Ruder laufen.
      int currentTick = server.getTickCount();
      activeBoxes.removeIf(box -> updateBox(currentTick, box));
   }

   private boolean updateBox(int currentTick, GroundBox box) {
      Display.ItemDisplay display = box.display();
      if (display == null || display.isRemoved()) {
         // Auch hier abmelden, nicht nur aus der Liste werfen: entlädt der Chunk einer Box,
         // gilt ihr Display als entfernt, die Entity liegt aber weiter in der Welt. Bliebe die
         // Anmeldung stehen, käme sie beim Nachladen als lebendig durch und stünde für immer.
         Hologram.remove(display);
         return true;
      }

      ServerLevel level = box.level();

      // Physik & Schwerkraft berechnen: Box fällt, wenn der Block unter ihr weggebrochen ist
      if (tickPhysics(box, level)) {
         Hologram.remove(display);
         return true;
      }

      Vec3 pos = box.basePosition();
      int ticksLeft = box.expiresAtTick() - currentTick;

      ServerPlayer collector = findCollector(level, pos);
      if (collector != null && collect(level, box, collector, currentTick)) {
         return true;
      }

      ServerPlayer shooter = checkArrowHit(level, box);
      if (shooter != null && collect(level, box, shooter, currentTick)) {
         return true;
      }

      if (ticksLeft <= 0) {
         level.sendParticles(ParticleTypes.GLOW, pos.x, pos.y + HOVER_HEIGHT, pos.z, 8, 0.2, 0.2, 0.2, 0.02);
         level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4F, 1.8F);
         Hologram.remove(display);
         return true;
      }

      animate(level, display, pos, currentTick, ticksLeft);
      return false;
   }

   /**
    * Berechnet Schwerkraft, freien Fall und Kollision mit dem Boden.
    * <p>
    * @return true, falls die Box ins Void gefallen ist und entfernt werden soll.
    */
   private boolean tickPhysics(GroundBox box, ServerLevel level) {
      Vec3 pos = box.basePosition();

      // Void-Schutz: Fällt unter den Weltboden
      if (pos.y < level.getMinY() - 8.0) {
         return true;
      }

      double currentY = pos.y;
      BlockPos checkPos = BlockPos.containing(pos.x, currentY - 0.08, pos.z);
      BlockState stateBelow = level.getBlockState(checkPos);

      boolean onGround = isSolidGround(stateBelow, currentY, checkPos);

      if (!onGround) {
         // Schwerkraft beschleunigt den freien Fall
         double newSpeed = Math.min(0.75, box.verticalSpeed() + 0.04);
         box.setVerticalSpeed(newSpeed);

         double nextY = currentY - newSpeed;
         int startBlockY = Mth.floor(currentY);
         int endBlockY = Mth.floor(nextY);
         Double landY = null;

         for (int y = startBlockY; y >= endBlockY; y--) {
            BlockPos bp = new BlockPos((int) Math.floor(pos.x), y, (int) Math.floor(pos.z));
            BlockState bs = level.getBlockState(bp);
            if (isSolidBlock(bs)) {
               double blockTop = y + 1.0;
               if (blockTop <= currentY + 0.05 && blockTop >= nextY - 0.05) {
                  landY = blockTop;
                  break;
               }
            }
         }

         if (landY != null) {
            // Landung auf festem Untergrund
            box.setBasePosition(new Vec3(pos.x, landY, pos.z));
            boolean wasHardFall = box.verticalSpeed() > 0.25;
            box.setVerticalSpeed(0.0);
            box.display().setPos(pos.x, landY + HOVER_HEIGHT, pos.z);

            if (wasHardFall) {
               level.playSound(null, pos.x, landY, pos.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.4F);
               level.sendParticles(ParticleTypes.WAX_OFF, pos.x, landY + 0.2, pos.z, 5, 0.25, 0.1, 0.25, 0.01);
            }
         } else {
            // Weiterer freier Fall nach unten
            box.setBasePosition(new Vec3(pos.x, nextY, pos.z));
            box.display().setPos(pos.x, nextY + HOVER_HEIGHT, pos.z);
         }
      } else {
         if (box.verticalSpeed() != 0.0) {
            box.setVerticalSpeed(0.0);
         }
      }

      return false;
   }

   private static boolean isSolidGround(BlockState state, double currentY, BlockPos pos) {
      if (state.isAir() || state.canBeReplaced()) {
         return false;
      }
      return currentY <= pos.getY() + 1.05;
   }

   private static boolean isSolidBlock(BlockState state) {
      return !state.isAir() && !state.canBeReplaced();
   }

   /**
    * Sucht einen Spieler in Reichweite der Box.
    * <p>
    * Gemessen wird waagerecht zur Boxmitte und senkrecht in einem breiten Band, statt zwei
    * Bounding-Boxen zu schneiden. Die Box schwebt und wippt; eine mitwandernde Trefferfläche
    * ließe sie je nach Schwebephase mal greifbar und mal unerreichbar wirken.
    */
   private ServerPlayer findCollector(ServerLevel level, Vec3 pos) {
      for (ServerPlayer player : level.players()) {
         if (!player.isAlive() || player.isSpectator()) {
            continue;
         }
         double dx = player.getX() - pos.x;
         double dz = player.getZ() - pos.z;
         double dy = player.getY() - pos.y;
         if (dx * dx + dz * dz <= PICKUP_RADIUS * PICKUP_RADIUS && dy > -PICKUP_HEIGHT && dy < PICKUP_HEIGHT) {
            return player;
         }
      }
      return null;
   }

   /**
    * Prüft, ob ein fliegender Pfeil in diesem Tick die schwebende Item-Box getroffen hat.
    * <p>
    * Wie beim Geschützturm hat ein Display keine eigene Kollisionsbox: wir prüfen die
    * zurückgelegte Flugstrecke des Pfeils gegen eine Bounding Box um die Box.
    * <p>
    * @return der Schütze, falls ein Pfeil getroffen hat, sonst null
    */
   private ServerPlayer checkArrowHit(ServerLevel level, GroundBox box) {
      Vec3 center = box.basePosition().add(0.0, HOVER_HEIGHT, 0.0);
      AABB hitbox = AABB.ofSize(center, 1.2, 1.2, 1.2);
      for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, hitbox.inflate(4.0))) {
         if (!arrow.isAlive() || arrow.isRemoved()) {
            continue;
         }
         if (!(arrow.getOwner() instanceof ServerPlayer shooter) || !shooter.isAlive() || shooter.isSpectator()) {
            continue;
         }
         Vec3 to = arrow.position();
         Vec3 from = to.subtract(arrow.getDeltaMovement());
         if (hitbox.contains(to) || hitbox.clip(from, to).isPresent()) {
            arrow.discard();
            OsokEffects.INSTANCE.playOwnSound(shooter, SoundEvents.ARROW_HIT_PLAYER, 0.8F, 1.5F);
            return shooter;
         }
      }
      return null;
   }

   private boolean collect(ServerLevel level, GroundBox box, ServerPlayer player, int currentTick) {
      ItemStack reward = box.item().createStack();
      if (!player.getInventory().add(reward)) {
         if (currentTick % 20 == 0) {
            Feedback.actionBar(player, "§c✖ Kein freier Slot — die Item-Box bleibt liegen");
         }
         return false;
      }
      player.containerMenu.broadcastChanges();

      Vec3 pos = box.basePosition();
      playPickupBurst(level, pos.x, pos.y + HOVER_HEIGHT, pos.z);
      // Ohne Ansage bliebe unklar, was drin war - das Item landet irgendwo im Inventar.
      Feedback.actionBar(player, "§6🎁 " + box.item().getDisplayName());
      Hologram.remove(box.display());
      return true;
   }

   /**
    * Dreht, wippt und neigt die Box.
    * <p>
    * Alle drei Bewegungen leiten sich aus dem Servertick ab. Der frühere Weg über mitlaufende
    * Winkel hatte zwei Nachteile: die Boxen liefen auseinander, und nach langer Laufzeit wurden
    * die Werte so groß, dass die Drehung sichtbar ruckelte.
    */
   private void animate(ServerLevel level, Display.ItemDisplay display, Vec3 pos, int currentTick, int ticksLeft) {
      boolean expiring = ticksLeft < EXPIRING_TICKS;
      float spin = currentTick * SPIN_PER_TICK * (expiring ? 1.8F : 1.0F);
      float bob = Mth.sin(currentTick * BOB_PER_TICK) * BOB_AMPLITUDE;
      float tilt = Mth.sin(currentTick * TILT_PER_TICK) * TILT;
      Hologram.setTransform(display, expiring ? EXPIRING_SCALE : BOX_SCALE, spin, tilt, bob);

      if (currentTick % 10 == 0) {
         level.sendParticles(ParticleTypes.WAX_OFF, pos.x, pos.y + HOVER_HEIGHT, pos.z, 1, 0.28, 0.28, 0.28, 0.0);
      }
      // Kurz vor dem Verschwinden funkt sie deutlicher - so lohnt der Sprint noch.
      if (expiring && currentTick % 6 == 0) {
         level.sendParticles(ParticleTypes.GLOW, pos.x, pos.y + HOVER_HEIGHT, pos.z, 2, 0.3, 0.3, 0.3, 0.01);
      }
   }

   /**
    * Belohnt eine Killserie mit einem Spezial-Item.
    * <p>
    * Die Betriebsart {@code STREAK} und der Menuetext versprachen das seit jeher, nur gab es
    * die Vergabe nie: {@code ItemMode#getAllowsStreakRewards} wurde nirgends abgefragt.
    * Belohnt wird jede dritte Eliminierung ohne eigenen Tod, also bei 3, 6, 9 und so weiter.
    * <p>
    * @return true, wenn wirklich etwas vergeben wurde
    */
   public boolean grantStreakReward(ServerPlayer player, int streak) {
      if (!itemMode.getAllowsStreakRewards() || streak <= 0 || streak % STREAK_REWARD_EVERY != 0) {
         return false;
      }
      SpecialItem item = rollItem();
      if (item == null) {
         return false;
      }

      if (!player.getInventory().add(item.createStack())) {
         Feedback.actionBar(player, "§c✖ Killserien-Belohnung: kein freier Slot");
         return false;
      }
      player.containerMenu.broadcastChanges();

      ServerLevel level = player.level();
      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9F, 1.4F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4F, 1.2F);
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(),
         40, 0.5, 0.8, 0.5, 0.2);
      level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 0.4, player.getZ(),
         25, 0.4, 0.9, 0.4, 0.1);

      Feedback.actionBar(player, "§6⚡ SERIE " + streak + " – " + item.getDisplayName());
      KillFeed.streakReward(player, streak, item.getDisplayName());
      return true;
   }

   /**
    * Zahlt das Kopfgeld aus: zwei Spezial-Items für den, der den Träger erledigt hat.
    * <p>
    * Sie werden einzeln gewürfelt, damit die Gewichtung auch hier gilt und nicht zweimal
    * dasselbe herauskommt, nur weil ein Wurf für beide reichen musste.
    */
   public void grantBounty(ServerPlayer killer, ServerPlayer target) {
      int given = 0;
      for (int index = 0; index < BOUNTY_ITEMS; index++) {
         SpecialItem item = rollItem();
         if (item == null) {
            break;
         }
         if (!killer.getInventory().add(item.createStack())) {
            killer.drop(item.createStack(), false);
         }
         given++;
      }
      if (given == 0) {
         return;
      }
      killer.containerMenu.broadcastChanges();

      ServerLevel level = killer.level();
      level.playSound(null, killer.getX(), killer.getY(), killer.getZ(),
         SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.1F);
      level.playSound(null, killer.getX(), killer.getY(), killer.getZ(),
         SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4F, 0.9F);
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, killer.getX(), killer.getY() + 1.0, killer.getZ(),
         60, 0.6, 0.9, 0.6, 0.25);
      Feedback.actionBar(killer, "§6👑 KOPFGELD KASSIERT — " + given + " Bonus-Items");
      OneShotOneKill.INSTANCE.getServer().getPlayerList().broadcastSystemMessage(
         net.minecraft.network.chat.Component.literal("[👑 KOPFGELD] ")
            .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD)
            .append(net.minecraft.network.chat.Component.literal(killer.getName().getString())
               .withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD))
            .append(net.minecraft.network.chat.Component.literal(" hat " + target.getName().getString()
               + " zur Strecke gebracht und kassiert " + given + " Bonus-Items!")
               .withStyle(net.minecraft.ChatFormatting.YELLOW)), false);
   }

   private SpecialItem rollItem() {
      int total = getTotalWeight();
      if (total <= 0) {
         return null;
      }

      int roll = ThreadLocalRandom.current().nextInt(total);
      for (SpecialItem item : SpecialItem.values()) {
         roll -= weightOf(item);
         if (roll < 0) {
            return item;
         }
      }
      return SpecialItem.values()[SpecialItem.values().length - 1];
   }

   /** Landeanmeldung: Melodischer Signalton und feiner Lichtblitz ohne Partikel-Spam. */
   private static void announceDrop(ServerLevel level, Vec3 position) {
      level.playSound(null, position.x, position.y, position.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.1F, 1.6F);
      level.playSound(null, position.x, position.y, position.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4F, 1.1F);
      level.sendParticles(ParticleTypes.WAX_OFF, position.x, position.y + 0.4, position.z, 8, 0.35, 0.2, 0.35, 0.02);
      level.sendParticles(ParticleTypes.GLOW, position.x, position.y + 0.5, position.z, 6, 0.25, 0.2, 0.25, 0.01);
   }

   /**
    * Lässt bei einer Eliminierung mit einer gewissen Wahrscheinlichkeit genau ein Spezial-Item
    * des Opfers als normales Item auf den Boden fallen.
    */
   public void tryDropVictimLoot(ServerPlayer attacker, ServerPlayer victim, KillFeed.Cause cause, Vec3 deathPos) {
      if (MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME) {
         return;
      }

      Inventory inventory = victim.getInventory();
      List<Integer> specialSlots = new ArrayList<>();
      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (!stack.isEmpty() && SpecialItem.fromStack(stack) != null) {
            specialSlots.add(slot);
         }
      }

      if (specialSlots.isEmpty()) {
         return;
      }

      // 1. Basis-Wahrscheinlichkeit: 20%
      double chance = 0.20;

      // 2. Inventardichte: +20% für jedes weitere Spezial-Item
      if (specialSlots.size() > 1) {
         chance += (specialSlots.size() - 1) * 0.20;
      }

      // 3. Shutdown-Bonus: Höhere Chance bei Beenden einer gegnerischen Serie
      int victimStreak = ScoreboardManager.INSTANCE.getStreak(victim.getUUID());
      if (victimStreak >= 6) {
         chance += 0.30;
      } else if (victimStreak >= 3) {
         chance += 0.15;
      }

      // Deckel bei maximal 85%
      chance = Math.min(0.85, chance);

      ServerLevel level = victim.level();
      if (level.getRandom().nextDouble() > chance) {
         return;
      }

      // Genau ein zufälliges Spezial-Item aus dem Inventar des Opfers auswählen
      int chosenIndex = level.getRandom().nextInt(specialSlots.size());
      int slot = specialSlots.get(chosenIndex);
      ItemStack stackToDrop = inventory.getItem(slot).copy();

      // Item aus dem Inventar des Opfers entfernen
      inventory.setItem(slot, ItemStack.EMPTY);
      victim.inventoryMenu.broadcastChanges();

      // Als normales Minecraft ItemEntity an der Sterbeposition in die Welt spawnen
      ItemEntity itemEntity = new ItemEntity(level, deathPos.x, deathPos.y + 0.3, deathPos.z, stackToDrop);
      itemEntity.setDeltaMovement(new Vec3(
         (level.getRandom().nextDouble() - 0.5) * 0.12,
         0.25,
         (level.getRandom().nextDouble() - 0.5) * 0.12
      ));
      itemEntity.setDefaultPickUpDelay();
      level.addFreshEntity(itemEntity);

      // Goldener Glanz- und Partikeleffekt am Sterbeort
      level.sendParticles(ParticleTypes.GLOW, deathPos.x, deathPos.y + 0.5, deathPos.z, 14, 0.3, 0.3, 0.3, 0.04);
      level.sendParticles(ParticleTypes.CRIT, deathPos.x, deathPos.y + 0.5, deathPos.z, 8, 0.25, 0.25, 0.25, 0.1);
      level.playSound(null, deathPos.x, deathPos.y, deathPos.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 0.8F);
   }

   /** Einsammel-Effekt: Kristalliner Doppel-Sound und sauberer Glanz-Plopp. */
   private static void playPickupBurst(ServerLevel level, double x, double y, double z) {
      level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 1.8F);
      level.playSound(null, x, y, z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.1F, 1.2F);
      level.sendParticles(ParticleTypes.WAX_OFF, x, y, z, 8, 0.3, 0.3, 0.3, 0.05);
      level.sendParticles(ParticleTypes.GLOW, x, y, z, 6, 0.2, 0.2, 0.2, 0.03);
   }

   private static final class GroundBox {
      private final SpecialItem item;
      private final Display.ItemDisplay display;
      private Vec3 basePosition;
      private double verticalSpeed;
      private final int expiresAtTick;
      private final ServerLevel level;

      private GroundBox(SpecialItem item, Display.ItemDisplay display, Vec3 basePosition, int expiresAtTick, ServerLevel level) {
         this.item = item;
         this.display = display;
         this.basePosition = basePosition;
         this.verticalSpeed = 0.0;
         this.expiresAtTick = expiresAtTick;
         this.level = level;
      }

      public SpecialItem item() {
         return item;
      }

      public Display.ItemDisplay display() {
         return display;
      }

      public Vec3 basePosition() {
         return basePosition;
      }

      public void setBasePosition(Vec3 basePosition) {
         this.basePosition = basePosition;
      }

      public double verticalSpeed() {
         return verticalSpeed;
      }

      public void setVerticalSpeed(double verticalSpeed) {
         this.verticalSpeed = verticalSpeed;
      }

      public int expiresAtTick() {
         return expiresAtTick;
      }

      public ServerLevel level() {
         return level;
      }
   }
}

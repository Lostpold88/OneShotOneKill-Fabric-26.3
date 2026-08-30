package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.nuke.MushroomCloud;

import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.ArenaShape;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor.Brightness;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Server-authoritative missile strikes with controlled arena damage and server-fed tactical radar. */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "ForLoopReplaceableByForEach", "NullableProblems", "resource"})
public final class AirstrikeSystem {
   public static final AirstrikeSystem INSTANCE = new AirstrikeSystem();

   private static final int WARNING_TICKS = 40;
   /** Abstand der Pfeifgeräusche während des Anflugs. */
   private static final int PAYLOAD_WHISTLE_TICKS = 7;
   /** Größe und Modellmaße der Nuke; ausgegeben von generate_airstrike_nuke_3d.py. */
   private static final float NUKE_SCALE = 3.6F;
   private static final double NUKE_NOSE_OFFSET = 1.7550;
   private static final double NUKE_TAIL_OFFSET = 1.8000;
   private static final float NUKE_VIEW_RANGE = 4.0F;
   private static final int RESTORE_DELAY_TICKS = 20 * 8;
   private static final int CRATER_RADIUS = 9;
   /** Tödlich ist genau der sichtbare Krater – nichts darüber hinaus. Das Radar zeichnet denselben Wert. */
   public static final double KILL_RADIUS = CRATER_RADIUS;
   private static final double CRATER_DEPTH_OFFSET = 2.25;
   /**
    * Höhe, mit der bei offenem Himmel gerechnet wird.
    * <p>
    * Der Atompilz passt sich der freien Höhe an. Ohne Decke gibt es keine zu messen, und
    * großzügig gerechnet heißt hier schlicht: volle Größe.
    */
   private static final double OPEN_SKY_HEADROOM = 128.0;
   private static final int RADAR_CELLS = 96;
   private static final int RADAR_REFRESH_TICKS = 6;

   /** Zellen außerhalb der Arena – der Client zeichnet diesen Wert durchsichtig. */
   public static final int RADAR_VOID_COLOR = 0xFF11161A;
   /** Spalten innerhalb der Arena, in denen kein Block gefunden wurde. */
   private static final int RADAR_SHADOW_COLOR = 0xFF1B1A16;
   /** Warmer Grundton der Oberfläche, auf den der Kartenscan eingefärbt wird. */
   private static final int RADAR_TINT = 0x8A7550;
   private static final double RADAR_TINT_STRENGTH = 0.20;
   private static final double RADAR_SATURATION = 0.88;
   private static final double RADAR_SHADE_FLOOR = 0.62;
   private static final double RADAR_LEVEL_FLOOR = 24.0;
   private static final double RADAR_LEVEL_RANGE = 0.80;

   private final List<PendingStrike> pendingStrikes = new ArrayList<>();
   /** Zuschauer des Radars und die Revision, die sie zuletzt bekommen haben. */
   private final Map<UUID, Integer> radarViewers = new HashMap<>();

   /** Der Geländescan ist teuer, ändert sich aber nur durch Einschläge – daher zwischengespeichert. */
   private RadarPayload terrainCache;
   private String terrainArenaId = "";
   private int terrainRevision;
   /** Stand der Kartenveränderungen, auf dem der zwischengespeicherte Scan beruht. */
   private int terrainSeenDemolition = -1;

   private AirstrikeSystem() {
   }

   public void request(ServerPlayer player, double targetX, double targetZ) {
      if (Deployables.INSTANCE.isFrozen(player)) {
         return;
      }
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null || MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING
         || MatchManager.Countdown.INSTANCE.isCountdownRunning()) {
         return;
      }

      Arena arena = worlds.getActive();
      ServerLevel level = worlds.getActiveLevel();
      if (level == null || worlds.arenaOf(player) != arena || !arena.isInArenaColumn(targetX, targetZ)) {
         return;
      }

      ItemStack stack = findAirstrike(player);
      if (stack == null) {
         return;
      }

      double impactY = playableSurfaceY(level, arena, targetX, targetZ);
      double launchY = arena.getHasCeiling()
         ? Math.min(arena.getCeilingY() - 1.2, impactY + 14.0)
         : impactY + 34.0;
      launchY = Math.max(impactY + 3.0, launchY);
      int requestedAt = level.getServer().getTickCount();
      Display.ItemDisplay nuke = Hologram.spawnNaturallyLit(level, new Vec3(targetX, launchY, targetZ),
         new ItemStack(ModItems.AIRSTRIKE_NUKE), NUKE_VIEW_RANGE);
      if (nuke == null) {
         return;
      }
      Hologram.setPose(nuke, new Vector3f(), new Quaternionf(),
         new Vector3f(NUKE_SCALE, NUKE_SCALE, NUKE_SCALE), 0);
      stack.shrink(1);

      pendingStrikes.add(new PendingStrike(player.getUUID(), arena.getId(), targetX, targetZ, impactY, launchY,
         requestedAt, requestedAt + WARNING_TICKS, nuke));
      level.playSound(null, targetX, launchY, targetZ, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.4F, 0.58F);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, targetX, launchY, targetZ, 18, 0.35, 0.25, 0.35, 0.02);
      raiseAlarm(level.getServer(), worlds, arena, new AirstrikeAlarmPayload(targetX, targetZ, launchY, impactY, WARNING_TICKS), true);
   }

   /** Verteilt eine Alarmmeldung an jeden, der in der Arena steht – auf Wunsch mit Sirene. */
   private void raiseAlarm(MinecraftServer server, ArenaWorlds worlds, Arena arena, AirstrikeAlarmPayload alarm, boolean withSiren) {
      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(listener) != arena) {
            continue;
         }
         if (withSiren) {
            double x = listener.getX();
            double y = listener.getY();
            double z = listener.getZ();
            listener.level().playSound(null, x, y, z, SoundEvents.RAID_HORN, SoundSource.PLAYERS, 1.0F, 0.62F);
            listener.level().playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.32F, 0.55F);
         }
         ServerPlayNetworking.send(listener, alarm);
      }
   }

   public void tick(MinecraftServer server) {
      int currentTick = server.getTickCount();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Iterator<PendingStrike> iterator = pendingStrikes.iterator();
      while (iterator.hasNext()) {
         PendingStrike strike = iterator.next();
         if (worlds == null || !worlds.getActive().getId().equals(strike.arenaId())) {
            discardPayloads(strike);
            iterator.remove();
            continue;
         }

         ServerLevel level = worlds.getActiveLevel();
         if (level == null) {
            discardPayloads(strike);
            iterator.remove();
            continue;
         }

         double impactY = strike.impactY();
         if (currentTick < strike.impactAt()) {
            double previousY = descentY(strike, currentTick - 1);
            double currentY = descentY(strike, currentTick);
            // Die Modellmitte schwebt fast zwei Blöcke über der Spitze. Kontakt wird deshalb
            // an der sichtbaren Bombenspitze geprüft und nicht mitten im Rumpf.
            Double contactY = firstContactY(level, strike,
               previousY - NUKE_NOSE_OFFSET, currentY - NUKE_NOSE_OFFSET);
            if (contactY == null) {
               animateIncomingPayload(level, strike, currentTick, currentY);
               continue;
            }
            // Die Bombe hat ein Dach, einen Vorsprung oder eine Wand berührt – hier wird gezündet.
            impactY = contactY;
         }

         iterator.remove();
         ServerPlayer attacker = server.getPlayerList().getPlayer(strike.attacker());
         discardPayloads(strike);
         if (attacker != null) {
            impact(server, worlds, level, attacker, strike, impactY, currentTick);
         }
      }

      MushroomCloud.INSTANCE.tick();
      updateRadarViewers(server, currentTick);
   }

   public void openRadar(ServerPlayer player) {
      if (!canUseRadar(player)) {
         return;
      }
      RadarPayload terrain = terrainSnapshot();
      radarViewers.put(player.getUUID(), terrain.revision());
      ServerPlayNetworking.send(player, forViewer(terrain, player, true));
   }

   public void closeRadar(ServerPlayer player) {
      radarViewers.remove(player.getUUID());
   }

   public void reset() {
      for (PendingStrike strike : pendingStrikes) {
         discardPayloads(strike);
      }
      pendingStrikes.clear();
      MushroomCloud.INSTANCE.reset();
      radarViewers.clear();
      terrainCache = null;
      terrainArenaId = "";
      terrainSeenDemolition = -1;
   }

   private void updateRadarViewers(MinecraftServer server, int currentTick) {
      if (radarViewers.isEmpty() || currentTick % RADAR_REFRESH_TICKS != 0) {
         return;
      }

      RadarPayload terrain = terrainSnapshot();
      Iterator<Map.Entry<UUID, Integer>> iterator = radarViewers.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Integer> viewer = iterator.next();
         ServerPlayer player = server.getPlayerList().getPlayer(viewer.getKey());
         if (player == null || !canUseRadar(player)) {
            iterator.remove();
            continue;
         }
         // Das Gelände geht nur über die Leitung, wenn es sich seit dem letzten Paket geändert hat.
         boolean needsTerrain = viewer.getValue() != terrain.revision();
         ServerPlayNetworking.send(player, forViewer(terrain, player, needsTerrain));
         viewer.setValue(terrain.revision());
      }
   }

   private boolean canUseRadar(ServerPlayer player) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      return worlds != null
         && MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING
         && worlds.arenaOf(player) == worlds.getActive()
         && findAirstrike(player) != null;
   }

   /**
    * Liefert den Geländescan der aktiven Arena. Der Scan kostet {@value #RADAR_CELLS}² Spaltenabfragen und
    * wird deshalb nur neu berechnet, wenn ein Einschlag oder eine Wiederherstellung ihn ungültig gemacht hat.
    */
   private RadarPayload terrainSnapshot() {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         return RadarPayload.empty(terrainRevision);
      }

      Arena arena = worlds.getActive();
      ServerLevel level = worlds.getActiveLevel();
      if (level == null) {
         return RadarPayload.empty(arena.getId(), terrainRevision);
      }
      // Der Scan gilt weiter, solange seit seiner Aufnahme kein Krater entstanden oder
      // zugewachsen ist – ArenaDemolition zählt beides mit.
      int demolition = ArenaDemolition.INSTANCE.getTerrainRevision();
      if (terrainSeenDemolition == demolition && terrainCache != null && arena.getId().equals(terrainArenaId)) {
         return terrainCache;
      }

      List<ArenaShape> regions = arena.getRegions();
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;
      for (ArenaShape region : regions) {
         minX = Math.min(minX, region.getMinX());
         maxX = Math.max(maxX, region.getMaxX());
         minZ = Math.min(minZ, region.getMinZ());
         maxZ = Math.max(maxZ, region.getMaxZ());
      }
      if (regions.isEmpty()) {
         return RadarPayload.empty(arena.getId(), terrainRevision);
      }

      List<Integer> colors = new ArrayList<>(RADAR_CELLS * RADAR_CELLS);
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int row = 0; row < RADAR_CELLS; row++) {
         int worldZ = (int) Math.floor(minZ + (row + 0.5) / RADAR_CELLS * (maxZ - minZ));
         for (int column = 0; column < RADAR_CELLS; column++) {
            int worldX = (int) Math.floor(minX + (column + 0.5) / RADAR_CELLS * (maxX - minX));
            ArenaShape shape = shapeAt(arena, worldX + 0.5, worldZ + 0.5);
            colors.add(shape == null ? RADAR_VOID_COLOR : radarSurfaceColor(level, pos, arena, shape, worldX, worldZ));
         }
      }

      terrainRevision++;
      terrainArenaId = arena.getId();
      terrainSeenDemolition = demolition;
      terrainCache = new RadarPayload(arena.getId(), RADAR_CELLS, minX, maxX, minZ, maxZ, terrainRevision, colors, List.of());
      return terrainCache;
   }

   /** Baut das Paket für einen Empfänger: Gegnerkontakte immer, Geländefarben nur bei Bedarf. */
   private RadarPayload forViewer(RadarPayload terrain, ServerPlayer recipient, boolean includeTerrain) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      List<RadarPayload.RadarContact> enemies = List.of();
      if (worlds != null && server != null) {
         Arena arena = worlds.getActive();
         enemies = server.getPlayerList().getPlayers().stream()
            .filter(player -> !player.getUUID().equals(recipient.getUUID()))
            .filter(player -> worlds.arenaOf(player) == arena && arena.isInArenaColumn(player.getX(), player.getZ()))
            .map(player -> new RadarPayload.RadarContact(player.getX(), player.getZ(), player.getGameProfile().name()))
            .toList();
      }
      return new RadarPayload(terrain.arenaId(), terrain.cells(), terrain.minX(), terrain.maxX(), terrain.minZ(), terrain.maxZ(),
         terrain.revision(), includeTerrain ? terrain.colors() : List.of(), enemies);
   }

   private int radarSurfaceColor(ServerLevel level, BlockPos.MutableBlockPos pos, Arena arena, ArenaShape shape, int worldX, int worldZ) {
      int upper = columnScanTop(arena, shape);
      int lower = Math.max(level.getMinY(), (int) Math.floor(shape.getMinY()) - 2);
      for (int y = upper; y >= lower; y--) {
         pos.set(worldX, y, worldZ);
         BlockState state = level.getBlockState(pos);
         if (!state.isAir()) {
            int argb = state.getMapColor(level, pos).calculateARGBColor(Brightness.NORMAL);
            return tacticalRadarColor(argb & 0xFFFFFF, y, lower, upper);
         }
      }
      return RADAR_SHADOW_COLOR;
   }

   /**
    * Gradiert die Kartenfarbe eines Blocks auf den Terminal-Look: Blocktöne bleiben erkennbar,
    * bekommen aber den warmen Grundton der Oberfläche und landen in einem Mittelband,
    * damit weder schwarze Löcher noch überstrahlte Flächen entstehen.
    */
   private int tacticalRadarColor(int rgb, int y, int lower, int upper) {
      double heightShade = RADAR_SHADE_FLOOR
         + (1.0 - RADAR_SHADE_FLOOR) * Math.clamp((y - lower) / (double) Math.max(1, upper - lower), 0.0, 1.0);
      double red = (rgb >>> 16) & 0xFF;
      double green = (rgb >>> 8) & 0xFF;
      double blue = rgb & 0xFF;
      double luminance = red * 0.2126 + green * 0.7152 + blue * 0.0722;

      red = gradeChannel(luminance + (red - luminance) * RADAR_SATURATION, heightShade, RADAR_TINT >>> 16 & 0xFF);
      green = gradeChannel(luminance + (green - luminance) * RADAR_SATURATION, heightShade, RADAR_TINT >>> 8 & 0xFF);
      blue = gradeChannel(luminance + (blue - luminance) * RADAR_SATURATION, heightShade, RADAR_TINT & 0xFF);
      return 0xFF000000 | (int) red << 16 | (int) green << 8 | (int) blue;
   }

   private static double gradeChannel(double value, double heightShade, int tint) {
      double shaded = value * heightShade;
      double tinted = shaded * (1.0 - RADAR_TINT_STRENGTH) + tint * RADAR_TINT_STRENGTH;
      return Math.clamp(RADAR_LEVEL_FLOOR + tinted * RADAR_LEVEL_RANGE, 0.0, 255.0);
   }

   /** Höhe der Nutzlast zum gegebenen Tick auf der Bahn von der Abwurf- zur Zielhöhe. */
   private double descentY(PendingStrike strike, int tick) {
      double progress = Math.clamp((tick - strike.requestedAt()) / (double) WARNING_TICKS, 0.0, 1.0);
      double finalCentreY = strike.impactY() + NUKE_NOSE_OFFSET;
      return strike.launchY() + (finalCentreY - strike.launchY()) * progress;
   }

   /**
    * Sucht im diesen Tick durchflogenen Höhenband den ersten festen Block. Geprüft wird das ganze
    * Band statt nur die Endhöhe, damit die Bombe bei hoher Sinkrate kein Dach durchschlägt.
    */
   private Double firstContactY(ServerLevel level, PendingStrike strike, double fromY, double toY) {
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int column = (int) Math.floor(strike.x());
      int row = (int) Math.floor(strike.z());
      int top = (int) Math.floor(fromY);
      int bottom = (int) Math.floor(toY);
      for (int y = top; y >= bottom; y--) {
         pos.set(column, y, row);
         if (!level.getBlockState(pos).isAir()) {
            return y + 1.0;
         }
      }
      return null;
   }

   private void animateIncomingPayload(ServerLevel level, PendingStrike strike, int currentTick, double y) {
      int elapsed = currentTick - strike.requestedAt();
      Display.ItemDisplay nuke = strike.payload();
      if (nuke != null && !nuke.isRemoved()) {
         float yaw = elapsed * 0.045F;
         Hologram.setPose(nuke, new Vector3f(0.0F, (float) (y - strike.launchY()), 0.0F),
            new Quaternionf().rotationY(yaw), new Vector3f(NUKE_SCALE, NUKE_SCALE, NUKE_SCALE), 2);
      }
      if (elapsed < 0) {
         return;
      }
      if (elapsed % PAYLOAD_WHISTLE_TICKS == 0) {
         // Pfeifen wird beim Sinken höher – das klassische Bombensignal.
         float pitch = 0.55F + 1.15F * (float) Math.clamp(elapsed / (double) WARNING_TICKS, 0.0, 1.0);
         level.playSound(null, strike.x(), y, strike.z(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 0.9F, pitch);
      }
      if (elapsed % 2 != 0) {
         return;
      }
      double tailY = y + NUKE_TAIL_OFFSET;
      // Nur eine schmale Spur: das Modell soll sichtbar bleiben und nicht in der alten
      // Partikelwolke aus drei TNT-Ladungen verschwinden.
      level.sendParticles(ParticleTypes.LARGE_SMOKE, strike.x(), tailY, strike.z(), 6, 0.32, 0.18, 0.32, 0.018);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, strike.x(), tailY - 0.2, strike.z(), 3, 0.28, 0.12, 0.28, 0.02);
   }

   private void impact(MinecraftServer server, ArenaWorlds worlds, ServerLevel level, ServerPlayer attacker, PendingStrike strike,
                       double impactY, int currentTick) {
      Vec3 impact = new Vec3(strike.x(), impactY, strike.z());
      // Der Client zählt selbst herunter; bei Kontaktzündung endet der Anflug früher als geplant.
      raiseAlarm(server, worlds, worlds.getActive(), AirstrikeAlarmPayload.detonated(impact.x, impact.z, impact.y), false);
      // Der eigentliche Einschlag läuft über sieben Sekunden ab und wird deshalb nicht hier
      // gezeichnet, sondern Tick für Tick – siehe MushroomCloud.
      Arena arena = worlds.getActive();
      double headroom = arena != null && arena.getHasCeiling() ? arena.getCeilingY() - impact.y : OPEN_SKY_HEADROOM;
      MushroomCloud.INSTANCE.detonate(level, impact, headroom);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 1.0, impact.z, 6, 2.2, 1.2, 2.2, 0.04);

      ArenaDemolition.INSTANCE.detonate(level, worlds.getActive(), impact,
         CRATER_RADIUS, CRATER_DEPTH_OFFSET, RESTORE_DELAY_TICKS, currentTick);

      Deployables.INSTANCE.destroyInRadius(level, impact, KILL_RADIUS);

      for (ServerPlayer victim : List.copyOf(server.getPlayerList().getPlayers())) {
         if (worlds.arenaOf(victim) == worlds.getActive() && victim.position().distanceToSqr(impact) <= KILL_RADIUS * KILL_RADIUS) {
            DamageListener.INSTANCE.eliminate(attacker, victim, worlds.getActive(), KillFeed.Cause.AIRSTRIKE);
         }
      }
   }

   private ItemStack findAirstrike(ServerPlayer player) {
      if (player.getMainHandItem().is(ModItems.AIRSTRIKE)) {
         return player.getMainHandItem();
      }
      return player.getOffhandItem().is(ModItems.AIRSTRIKE) ? player.getOffhandItem() : null;
   }

   private void discardPayloads(PendingStrike strike) {
      Hologram.remove(strike.payload());
   }

   private double playableSurfaceY(ServerLevel level, Arena arena, double x, double z) {
      ArenaShape shape = shapeAt(arena, x, z);
      if (shape == null) {
         return arena.getLobby().y;
      }
      int upper = columnScanTop(arena, shape);
      int lower = Math.max(level.getMinY(), (int) Math.floor(shape.getMinY()) - 2);
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos((int) Math.floor(x), upper, (int) Math.floor(z));
      for (int y = upper; y >= lower; y--) {
         pos.setY(y);
         if (!level.getBlockState(pos).isAir()) {
            return y + 1.0;
         }
      }
      return Math.min(upper + 1.0, arena.getHasCeiling() ? arena.getCeilingY() - 2.0 : upper + 1.0);
   }

   /**
    * Oberkante der Spaltensuche. Bewusst an der Spielfläche und nicht an
    * {@code maxItemSpawnY} orientiert – sonst bleiben hohe Dächer für Radar und
    * Einschlagshöhe unsichtbar.
    */
   private int columnScanTop(Arena arena, ArenaShape shape) {
      int top = (int) Math.floor(shape.getMaxY()) + 6;
      return arena.getHasCeiling() ? Math.min(top, (int) Math.floor(arena.getCeilingY()) - 1) : top;
   }

   /** Wird pro Radarzelle und pro Kraterspalte aufgerufen – daher bewusst ohne Stream. */
   private ArenaShape shapeAt(Arena arena, double x, double z) {
      List<ArenaShape> regions = arena.getRegions();
      for (int index = 0; index < regions.size(); index++) {
         ArenaShape shape = regions.get(index);
         if (shape.containsColumn(x, z)) {
            return shape;
         }
      }
      return null;
   }

   /** Server-authoritative terrain and enemy snapshot for the radar client. */
   public record RadarPayload(String arenaId, int cells, double minX, double maxX, double minZ, double maxZ,
                              int revision, List<Integer> colors, List<RadarContact> contacts) implements CustomPacketPayload {
      public static final Type<RadarPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("airstrike_radar"));
      private static final StreamCodec<ByteBuf, List<Integer>> COLORS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());

      public static final StreamCodec<ByteBuf, RadarPayload> STREAM_CODEC = new StreamCodec<>() {
         @Override
         public RadarPayload decode(ByteBuf buffer) {
            String arenaId = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int cells = ByteBufCodecs.VAR_INT.decode(buffer);
            double minX = ByteBufCodecs.DOUBLE.decode(buffer);
            double maxX = ByteBufCodecs.DOUBLE.decode(buffer);
            double minZ = ByteBufCodecs.DOUBLE.decode(buffer);
            double maxZ = ByteBufCodecs.DOUBLE.decode(buffer);
            int revision = ByteBufCodecs.VAR_INT.decode(buffer);
            List<Integer> colors = COLORS.decode(buffer);
            int count = ByteBufCodecs.VAR_INT.decode(buffer);
            List<RadarContact> contacts = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
               contacts.add(new RadarContact(ByteBufCodecs.DOUBLE.decode(buffer), ByteBufCodecs.DOUBLE.decode(buffer),
                  ByteBufCodecs.STRING_UTF8.decode(buffer)));
            }
            return new RadarPayload(arenaId, cells, minX, maxX, minZ, maxZ, revision, colors, contacts);
         }

         @Override
         public void encode(ByteBuf buffer, RadarPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.arenaId);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.cells);
            ByteBufCodecs.DOUBLE.encode(buffer, payload.minX);
            ByteBufCodecs.DOUBLE.encode(buffer, payload.maxX);
            ByteBufCodecs.DOUBLE.encode(buffer, payload.minZ);
            ByteBufCodecs.DOUBLE.encode(buffer, payload.maxZ);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.revision);
            COLORS.encode(buffer, payload.colors);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.contacts.size());
            for (RadarContact contact : payload.contacts) {
               ByteBufCodecs.DOUBLE.encode(buffer, contact.x);
               ByteBufCodecs.DOUBLE.encode(buffer, contact.z);
               ByteBufCodecs.STRING_UTF8.encode(buffer, contact.name);
            }
         }
      };

      public static RadarPayload empty(int revision) {
         return empty("", revision);
      }

      public static RadarPayload empty(String arenaId, int revision) {
         return new RadarPayload(arenaId, 1, 0.0, 1.0, 0.0, 1.0, revision, List.of(RADAR_VOID_COLOR), List.of());
      }

      @Override
      public Type<RadarPayload> type() {
         return TYPE;
      }

      public record RadarContact(double x, double z, String name) {
      }
   }

   /** Starts the radar stream for the requesting player. */
   public static final class OpenRadarPayload implements CustomPacketPayload {
      public static final OpenRadarPayload EMPTY = new OpenRadarPayload();
      public static final Type<OpenRadarPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("open_airstrike_radar"));
      public static final StreamCodec<ByteBuf, OpenRadarPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);

      private OpenRadarPayload() {
      }

      @Override
      public Type<OpenRadarPayload> type() {
         return TYPE;
      }
   }

   /** Stops the radar stream for the requesting player. */
   public static final class CloseRadarPayload implements CustomPacketPayload {
      public static final CloseRadarPayload EMPTY = new CloseRadarPayload();
      public static final Type<CloseRadarPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("close_airstrike_radar"));
      public static final StreamCodec<ByteBuf, CloseRadarPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);

      private CloseRadarPayload() {
      }

      @Override
      public Type<CloseRadarPayload> type() {
         return TYPE;
      }
   }

   private record PendingStrike(UUID attacker, String arenaId, double x, double z, double impactY, double launchY,
                                int requestedAt, int impactAt, Display.ItemDisplay payload) {
   }
}

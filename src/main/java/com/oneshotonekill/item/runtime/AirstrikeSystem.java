package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.network.OsokPayloads.AirstrikeAlarmPayload;
import com.oneshotonekill.network.OsokPayloads.ExplosionShakePayload;
import com.oneshotonekill.nuke.MushroomCloud;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.ArenaShape;
import com.oneshotonekill.shared.Hologram;
import io.netty.buffer.ByteBuf;
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

import java.util.*;

/** Server-authoritative missile strikes with controlled arena damage and server-fed tactical radar. */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "NullableProblems", "resource"})
public final class AirstrikeSystem {
   public static final AirstrikeSystem INSTANCE = new AirstrikeSystem();

   /**
    * Flugzeit von der Auslösung bis zum Einschlag – für jeden Angriff dieselbe, egal wie hoch
    * oder tief das Ziel liegt. Die Bombe fliegt immer genau diese Zeit; nur die Strecke, die sie
    * dabei zurücklegt, hängt von der Höhe ab.
    */
   private static final int WARNING_TICKS = 44;
   /** Abstand der Pfeifgeräusche während des Anflugs. */
   private static final int PAYLOAD_WHISTLE_TICKS = 5;
   /** Größe der Nuke; die Maße je Größeneinheit stammen aus generate_airstrike_nuke_3d.py (1,755 bei 3,6). */
   private static final float NUKE_SCALE = 5.0F;
   private static final double NUKE_NOSE_OFFSET = 0.4875 * NUKE_SCALE;
   private static final double NUKE_TAIL_OFFSET = 0.5 * NUKE_SCALE;
   /** Abwurfhöhe über der Einschlagstelle bei offenem Himmel: hoch genug für einen sichtbaren Sturz. */
   private static final double OPEN_SKY_LAUNCH = 96.0;
   /** Abstand zwischen Heck der Bombe und Hallendecke beim Abwurf. */
   private static final double CEILING_CLEARANCE = 0.4;
   /** Anteil des Sturzes, der gleichmäßig verläuft; der Rest beschleunigt. Gilt für jeden Angriff gleich. */
   private static final double FALL_LINEAR_SHARE = 0.3;
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

   /**
    * Anteil des Weges, den die Bombe nach dem Anteil {@code time} der Flugzeit zurückgelegt hat.
    * <p>
    * Server und Client rechnen mit derselben Kurve, damit der Marker im HUD der echten Bombe folgt.
    * Sie beschleunigt: erst ein ruhiger Abwurf, dann der Sturz.
    */
   public static double fallShare(double time) {
      double t = Math.clamp(time, 0.0, 1.0);
      return FALL_LINEAR_SHARE * t + (1.0 - FALL_LINEAR_SHARE) * t * t;
   }

   private final List<PendingStrike> pendingStrikes = new ArrayList<>();
   /** Verzögerte Nachbeben, die dem Einschlag folgen. */
   private final List<Aftershock> aftershocks = new ArrayList<>();
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

      double surfaceY = playableSurfaceY(level, arena, targetX, targetZ);
      double launchY = arena.getHasCeiling()
         ? arena.getCeilingY() - NUKE_TAIL_OFFSET - CEILING_CLEARANCE
         : surfaceY + OPEN_SKY_LAUNCH;
      launchY = Math.max(surfaceY + 3.0, launchY);
      // Aufschlag auf das, was die Bombe von ihrer Abwurfhöhe aus wirklich zuerst trifft –
      // auch ein Dach über der Spielfläche. So steht der Aufschlagpunkt vor dem Abwurf fest und
      // der Einschlag kommt zu jeder Zeit genau zum selben Tick.
      Double roofY = surfaceBelow(level, targetX, targetZ, launchY, surfaceY);
      double impactY = roofY == null ? surfaceY : Math.max(surfaceY, roofY);
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

         if (currentTick < strike.impactAt()) {
            animateIncomingPayload(level, strike, currentTick, descentY(strike, currentTick));
            continue;
         }

         iterator.remove();
         ServerPlayer attacker = server.getPlayerList().getPlayer(strike.attacker());
         discardPayloads(strike);
         if (attacker != null) {
            impact(server, worlds, level, attacker, strike, settledImpactY(level, strike), currentTick);
         }
      }

      tickAftershocks(server, worlds, currentTick);
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
      aftershocks.clear();
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
            ArenaShape shape = arena.shapeAt(worldX + 0.5, worldZ + 0.5);
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

    /**
     * Höhe der Nutzlast zum gegebenen Tick auf der Bahn von der Abwurf- zur Zielhöhe.
     */
    private double descentY(PendingStrike strike, int tick) {
        double share = fallShare((tick - strike.requestedAt()) / (double) WARNING_TICKS);
        double finalCentreY = strike.impactY() + NUKE_NOSE_OFFSET;
        return strike.launchY() + (finalCentreY - strike.launchY()) * share;
    }

   /**
    * Oberkante des ersten festen Blocks in der Säule, von {@code fromY} abwärts bis {@code floorY}
    * gesucht, oder {@code null}, wenn dort nur Luft ist.
    */
   private Double surfaceBelow(ServerLevel level, double x, double z, double fromY, double floorY) {
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int column = (int) Math.floor(x);
      int row = (int) Math.floor(z);
      int bottom = (int) Math.floor(floorY);
      for (int y = (int) Math.floor(fromY); y >= bottom; y--) {
         pos.set(column, y, row);
         if (!level.getBlockState(pos).isAir()) {
            return y + 1.0;
         }
      }
      return null;
   }

   /**
    * Höhe, in der die Bombe tatsächlich einschlägt. Hat ein anderer Einschlag den Boden seit dem
    * Abwurf gesenkt, setzt sie auf dem neuen Grund auf statt in der Luft zu zünden; der Zeitpunkt
    * bleibt davon unberührt.
    */
   private double settledImpactY(ServerLevel level, PendingStrike strike) {
      Double ground = surfaceBelow(level, strike.x(), strike.z(), strike.impactY() + 1.0, strike.impactY() - 40.0);
      return ground == null ? strike.impactY() : ground;
   }


   private void animateIncomingPayload(ServerLevel level, PendingStrike strike, int currentTick, double y) {
      int elapsed = currentTick - strike.requestedAt();
      int remaining = strike.impactAt() - currentTick;
      float share = (float) Math.clamp(elapsed / (double) WARNING_TICKS, 0.0, 1.0);
      Display.ItemDisplay nuke = strike.payload();
      if (nuke != null && !nuke.isRemoved()) {
         // Je schneller der Sturz, desto stärker trudelt die Bombe um die Längsachse und kippelt leicht.
         float yaw = elapsed * (0.05F + 0.25F * share);
         float wobble = 0.05F * share * (float) Math.sin(elapsed * 0.9);
         Hologram.setPose(nuke, new Vector3f(0.0F, (float) (y - strike.launchY()), 0.0F),
                 new Quaternionf().rotationY(yaw).rotateZ(wobble), new Vector3f(NUKE_SCALE, NUKE_SCALE, NUKE_SCALE), 2);
      }
      if (elapsed < 0) {
         return;
      }

      double x = strike.x();
      double z = strike.z();
      double ground = strike.impactY();
      if (elapsed % PAYLOAD_WHISTLE_TICKS == 0) {
         // Pfeifen wird beim Sinken höher und lauter – das klassische Bombensignal.
         float pitch = 0.5F + 1.3F * share;
         level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.6F + 2.4F * share, pitch);
      }

      // Feuerschweif: wird mit der Geschwindigkeit dichter und heißer.
      double tailY = y + NUKE_TAIL_OFFSET;
      level.sendParticles(ParticleTypes.FLAME, x, tailY, z, 3 + Math.round(10 * share), 0.28, 0.25, 0.28, 0.04 + 0.06 * share);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, x, tailY + 1.2, z, 3 + Math.round(5 * share), 0.4, 0.6, 0.4, 0.02);
      // Zweiter Rauchballen weiter hinten: bei hohem Tempo bleibt so eine zusammenhängende Spur am Himmel.
      level.sendParticles(ParticleTypes.LARGE_SMOKE, x, tailY + 3.5 + 3.0 * share, z, 3, 0.35, 0.8, 0.35, 0.01);
      if (elapsed % 2 == 0) {
         level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, tailY - 0.2, z, 4, 0.35, 0.2, 0.35, 0.03);
         level.sendParticles(ParticleTypes.LAVA, x, tailY, z, 2, 0.45, 0.3, 0.45, 0.0);
      }

      if (remaining == 8) {
         level.playSound(null, x, ground + 6.0, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 5.0F, 0.5F);
      }
      if (remaining == 4) {
         // Die Luft reißt vor der Bombe auf.
         level.sendParticles(ParticleTypes.SONIC_BOOM, x, y - NUKE_NOSE_OFFSET, z, 1, 0.0, 0.0, 0.0, 0.0);
         level.playSound(null, x, y, z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.0F, 1.5F);
      }
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
      unleashImpact(server, worlds, level, impact, currentTick);

      ArenaDemolition.INSTANCE.detonate(level, worlds.getActive(), impact,
         CRATER_RADIUS, CRATER_DEPTH_OFFSET, RESTORE_DELAY_TICKS, currentTick, true);

      Deployables.INSTANCE.destroyInRadius(level, impact, KILL_RADIUS);

      for (ServerPlayer victim : List.copyOf(server.getPlayerList().getPlayers())) {
         if (worlds.arenaOf(victim) == worlds.getActive() && victim.position().distanceToSqr(impact) <= KILL_RADIUS * KILL_RADIUS) {
            DamageListener.INSTANCE.eliminate(attacker, victim, worlds.getActive(), KillFeed.Cause.AIRSTRIKE);
         }
      }
   }

   /**
    * Das Spektakel rund um den Einschlag: Lichtblitz, Feuerring, der die Druckwelle nach außen
    * trägt, ein Erdbeben für die ganze Arena und Nachbeben, die in den Sekunden danach folgen.
    * Der Atompilz selbst kommt aus {@link MushroomCloud}.
    */
   private void unleashImpact(MinecraftServer server, ArenaWorlds worlds, ServerLevel level, Vec3 impact, int currentTick) {
      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 8.0F, 0.5F);
      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.WITHER_BREAK_BLOCK, SoundSource.PLAYERS, 4.0F, 0.5F);
      level.sendParticles(ParticleTypes.SONIC_BOOM, impact.x, impact.y + 1.5, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.SONIC_BOOM, impact.x, impact.y + 6.0, impact.z, 1, 0.0, 0.0, 0.0, 0.0);

      // Count 0 heißt bei Partikelpaketen: Die Werte sind eine Richtung, die Geschwindigkeit skaliert sie.
      int spokes = 56;
      for (int i = 0; i < spokes; i++) {
         double angle = i * (Math.PI * 2.0 / spokes);
         double dx = Math.cos(angle);
         double dz = Math.sin(angle);
         level.sendParticles(ParticleTypes.FLAME, impact.x, impact.y + 0.6, impact.z, 0, dx, 0.10, dz, 1.1);
         level.sendParticles(ParticleTypes.LARGE_SMOKE, impact.x, impact.y + 0.8, impact.z, 0, dx, 0.14, dz, 0.7);
         if (i % 2 == 0) {
            level.sendParticles(ParticleTypes.LAVA, impact.x, impact.y + 1.0, impact.z, 0, dx, 0.5, dz, 0.5);
         }
      }

      shakeArena(server, worlds, impact, 150.0F, 3.2F, 80);
      aftershocks.add(new Aftershock(currentTick + 8, level, impact, 0));
      aftershocks.add(new Aftershock(currentTick + 24, level, impact, 1));
      aftershocks.add(new Aftershock(currentTick + 46, level, impact, 2));
   }

   /**
    * Schüttelt jedem in der Arena die Kamera; wie stark, rechnet der Client aus seiner Entfernung.
    */
   private void shakeArena(MinecraftServer server, ArenaWorlds worlds, Vec3 at, float reach, float intensity, int ticks) {
      Arena arena = worlds.getActive();
      ExplosionShakePayload shake = new ExplosionShakePayload(at.x, at.y, at.z, reach, intensity, ticks);
      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(listener) == arena) {
            ServerPlayNetworking.send(listener, shake);
         }
      }
   }

   private void tickAftershocks(MinecraftServer server, ArenaWorlds worlds, int currentTick) {
      Iterator<Aftershock> iterator = aftershocks.iterator();
      while (iterator.hasNext()) {
         Aftershock shock = iterator.next();
         if (currentTick < shock.at()) {
            continue;
         }
         iterator.remove();
         if (worlds == null) {
            continue;
         }
         Vec3 at = shock.position();
         switch (shock.stage()) {
            case 0 -> {
               // Die Druckwelle erreicht den Rand der Arena.
               shock.level().playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 6.0F, 0.35F);
               shakeArena(server, worlds, at, 130.0F, 2.2F, 50);
            }
            case 1 -> {
               shock.level().playSound(null, at.x, at.y + 10.0, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 8.0F, 0.45F);
               shakeArena(server, worlds, at, 110.0F, 1.5F, 40);
            }
            default -> {
               // Trümmer prasseln nieder, ganz fern grollt es nach.
               shock.level().playSound(null, at.x, at.y + 14.0, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 6.0F, 0.32F);
               shock.level().playSound(null, at.x, at.y, at.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 3.0F, 0.5F);
            }
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
      ArenaShape shape = arena.shapeAt(x, z);
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

   /** Server-authoritative terrain and enemy snapshot for the radar client. */
   public record RadarPayload(String arenaId, int cells, double minX, double maxX, double minZ, double maxZ,
                              int revision, List<Integer> colors, List<RadarContact> contacts) implements CustomPacketPayload {
      public static final Type<RadarPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("airstrike_radar"));
      private static final StreamCodec<ByteBuf, List<Integer>> COLORS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
      private static final StreamCodec<ByteBuf, List<RadarContact>> CONTACTS = RadarContact.STREAM_CODEC.apply(ByteBufCodecs.list());

      public static final StreamCodec<ByteBuf, RadarPayload> STREAM_CODEC = new StreamCodec<>() {
         @Override
         public RadarPayload decode(ByteBuf buffer) {
            return new RadarPayload(
               ByteBufCodecs.STRING_UTF8.decode(buffer),
               ByteBufCodecs.VAR_INT.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.VAR_INT.decode(buffer),
               COLORS.decode(buffer),
               CONTACTS.decode(buffer));
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
            CONTACTS.encode(buffer, payload.contacts);
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
         public static final StreamCodec<ByteBuf, RadarContact> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, RadarContact::x,
            ByteBufCodecs.DOUBLE, RadarContact::z,
            ByteBufCodecs.STRING_UTF8, RadarContact::name,
            RadarContact::new);
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

   private record Aftershock(int at, ServerLevel level, Vec3 position, int stage) {
   }

   private record PendingStrike(UUID attacker, String arenaId, double x, double z, double impactY, double launchY,
                                int requestedAt, int impactAt, Display.ItemDisplay payload) {
   }
}

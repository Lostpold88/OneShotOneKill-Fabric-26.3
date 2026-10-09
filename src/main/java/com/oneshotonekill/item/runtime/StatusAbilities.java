package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.network.OsokPayloads.AbilityStatusPayload;
import com.oneshotonekill.network.OsokPayloads.DeployableMarkersPayload;
import com.oneshotonekill.network.OsokPayloads.GlidingPlayersPayload;
import com.oneshotonekill.network.OsokPayloads.MagnetFieldsPayload;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.SpecialItemRules;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Zeitlich begrenzte Zustände, die an einem Spieler hängen: Radar-Leuchten, Reflektor-Schild,
 * Unsichtbarkeit, Pfeil-Magnetfeld und Gleitflug.
 * <p>
 * Alle Zustände laufen über einen gemeinsamen Tick, damit ein Match-Ende, ein Map-Wechsel oder
 * ein Verbindungsabbruch sie an genau einer Stelle wieder abräumen kann.
 */
@SuppressWarnings({"ConstantValue", "Convert2MethodRef", "resource", "UnusedReturnValue", "SameParameterValue", "unused"})
public final class StatusAbilities {
   public static final StatusAbilities INSTANCE = new StatusAbilities();

   private static final int RADAR_GLOW_TICKS = 600;
   private static final double RADAR_RANGE = 200.0;
   /**
    * Der Suchlauf: ein Ring, der über den Boden nach außen läuft.
    * <p>
    * Vorher stand ein Kranz aus weißen END_ROD-Partikeln um den Benutzer – ein Rahmen, der
    * nichts über die Richtung sagte und in jedem hellen Raum unterging.
    */
   private static final int SWEEP_TICKS = 26;
   private static final double SWEEP_RADIUS = 22.0;
   private static final double SWEEP_BAND = 0.5;
   private static final double SWEEP_RING_BAND_UNIT = 0.0978;
   private static final int SWEEP_NEAR = 0x64F0FF;
   private static final int SWEEP_FAR = 0x1B5A8C;
   private static final int RADAR_BLINK_TICKS = 10;
   private static final int VANISH_TICKS = 300;
   private static final int MAGNET_TICKS = 300;
   /** Muss dem sichtbaren Radius in MagnetShieldRenderer entsprechen. */
   private static final double MAGNET_RADIUS = 1.50;
   /** Groß genug, damit auch ein schneller Pfeil die Kugel zwischen zwei Ticks nicht überspringt. */
   private static final double MAGNET_SCAN_RADIUS = 4.5;
   private static final DustParticleOptions MAGNET_RED = new DustParticleOptions(0xFF3147, 0.85F);
   private static final DustParticleOptions MAGNET_BLUE = new DustParticleOptions(0x318CFF, 0.85F);
   private static final int GLIDE_TICKS = 160;
   private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
   private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);
   /*
    * Das Flugmodell: Dauerschub in Blickrichtung.
    *
    * Der Schub laeuft von allein, gelenkt wird mit der Maus – wer irgendwohin will, sieht
    * dorthin. Keine Taste haelt ihn aufrecht, keine bremst ihn.
    *
    * Drei Fassungen davor waren daneben, und der Reihe nach aus verschiedenen Gruenden. Die
    * erste addierte Schub und einen festen Auftrieb und flog wie eine Drohne. Die zweite fuehrte
    * eine Energiebilanz mit Abriss – technisch ein Segelflugzeug, aber Hoehe gab es nur im
    * Tausch gegen Tempo. Die dritte legte die Steuerung auf die Tastatur; das war freier Flug,
    * aber eben kein Schub mehr, der einen traegt.
    *
    * Was von den Zwischenschritten bleibt, ist das Brauchbare:
    *
    *   1. Die Flugrichtung dreht sich als echte Drehung zur Blickrichtung, bei hohem Tempo
    *      traeger als bei niedrigem. Daher das Gefuehl von Masse – und keine entarteten Faelle
    *      mehr, siehe {@link #rotateToward}.
    *   2. Das Tempo laeuft auf einen Zielwert zu, der allein an der Fluglage haengt: im Sturz
    *      hoeher, im Steigflug niedriger. Kein Abriss, keine Steigwinkelgrenze – steigen kostet
    *      Strecke, aber es geht immer.
    *   3. Waagerechter Blick heisst waagerechter Flug, auf den Block genau.
    *
    * Nach oben begrenzt allein die Arena, siehe {@link #glideCeiling}.
    */
   /** Groesste Richtungsaenderung je Tick im Bogenmass, bei Tempo null – knapp 17 Grad. */
   private static final double GLIDE_TURN_RATE = 0.30;
   /** Wie stark das Tempo diese Wendigkeit bremst. */
   private static final double GLIDE_TURN_DAMPING = 0.9;
   /** Reisegeschwindigkeit im waagerechten Flug, in Bloecken je Tick. */
   private static final double GLIDE_CRUISE = 1.45;
   /** Aufschlag im senkrechten Sturzflug. */
   private static final double GLIDE_DIVE_BONUS = 0.80;
   /** Abschlag im senkrechten Steigflug – steigen geht, kostet aber Strecke. */
   private static final double GLIDE_CLIMB_TAX = 0.35;
   /** Wie schnell das Tempo seinem Zielwert folgt. */
   private static final double GLIDE_ACCEL = 0.10;
   private static final double GLIDE_MAX_SPEED = 2.60;
   private static final double GLIDE_MIN_SPEED = 0.50;
   /** Sinkanteil, auf den die Nase unter der Decke der Arena gedrueckt wird. */
   private static final double GLIDE_CEILING_SINK = -0.05;
   /** Tempo unmittelbar nach einem Absprung. */
   private static final double GLIDE_LAUNCH_SPEED = 1.40;
   /** Mindestanteil nach oben beim Absprung, damit auch ein Start im Sturz zunaechst steigt. */
   private static final double GLIDE_LAUNCH_RISE = 0.45;

   /**
   /**
    * So lange nach dem Erloeschen des Schubs bleibt der Fall folgenlos.
    * <p>
    * Ein Flug endet fast immer in der Luft, und wer dort acht Sekunden lang ueber der Karte
    * stand, faellt danach weit. Das Geschirr eine Sekunde vor dem Aufschlag verglimmen zu lassen
    * und den Spieler dann am Boden zu zerschellen, waere die unfairste Art, eine Fahigkeit zu
    * beenden – zumal er den Zeitpunkt nicht in der Hand hat.
    * <p>
    * Zehn Sekunden reichen fuer jeden Sturz innerhalb einer Arena; der Zaehler ist nur die
    * Auffanglinie fuer den Fall, dass der Boden nie kommt.
    */
   private static final int SOFT_LANDING_TICKS = 200;
   /** Mindestabstand zwischen zwei Starts, in Ticks. */
   private static final int GLIDE_BOOST_COOLDOWN = 10;

   /*
    * Die Flügel und ihre Flammen zeichnet jeder Client selbst.
    * <p>
    * Als Display-Entities hinkten sie bei Fluggeschwindigkeit sichtbar hinterher: jede Entity
    * läuft über eigene Positionspakete und wird zwischen zwei Ticks interpoliert. Der Server
    * meldet nur noch, wer gerade fliegt – siehe {@code client/renderer/GliderWingRenderer}.
    */
   /**
    * So hoch darf der Flug ueber die Oberkante der Karte hinaus.
    * <p>
    * Frueher acht Bloecke – das reichte fuer einen Sprung ueber ein Dach und nicht fuer das,
    * was ein Jetpack koennen soll. Der Wert bleibt unter {@code ArenaShape.ARENA_HEADROOM},
    * denn oberhalb davon gilt man als ausserhalb der Arena, und {@code ArenaContainment} holt
    * einen dann zurueck. Wer hoeher hinaus will, hebt beide Werte gemeinsam.
    */
   private static final double GLIDE_HEADROOM = 16.0;

   private final List<Sweep> sweeps = new ArrayList<>();
   /** Radar-Benutzer -> nur für ihn sichtbare Ziele und Restdauer. */
   private final Map<UUID, RadarView> radarViews = new HashMap<>();
   private final Map<UUID, Integer> vanished = new HashMap<>();
   private final Map<UUID, Integer> magnets = new HashMap<>();
   private final Map<UUID, Glide> gliding = new HashMap<>();
   /** Spieler-UUID -> verbleibende Ticks ohne Fallschaden, nach dem Ende eines Fluges. */
   private final Map<UUID, Integer> softLanding = new HashMap<>();
   /** Reiner Spielzustand; die Darstellung erzeugt ausschließlich der Client des Besitzers. */
   private final Set<UUID> shields = new HashSet<>();

   private StatusAbilities() {
   }

   // -- Auslöser ------------------------------------------------------------

   public boolean startRadarPulse(ServerPlayer player) {
      MinecraftServer server = player.level().getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (server == null || worlds == null) {
         return false;
      }

      Arena arena = worlds.getActive();
      clearRadarView(player);
      List<UUID> markedPlayers = new ArrayList<>();
      for (ServerPlayer target : server.getPlayerList().getPlayers()) {
         if (target.getUUID().equals(player.getUUID()) || worlds.arenaOf(target) != arena) {
            continue;
         }
         if (target.position().distanceToSqr(player.position()) > RADAR_RANGE * RADAR_RANGE) {
            continue;
         }
         markedPlayers.add(target.getUUID());
      }

      radarViews.put(player.getUUID(), new RadarView(List.copyOf(markedPlayers), RADAR_GLOW_TICKS));
      sendRadarGlow(player, markedPlayers, true);

      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.9F, 1.6F);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.9F);
      if (player.level() instanceof ServerLevel level) {
         startSweep(level, player.position(), player.getUUID());
      }
      Feedback.actionBar(player, !markedPlayers.isEmpty()
         ? "§a◉ RADAR-PULS — " + markedPlayers.size() + " Kontakte markiert"
         : "§7◉ RADAR-PULS — keine Kontakte");
      return true;
   }

   /**
    * Hängt einen Suchlaufring auf, der über den Boden nach außen läuft und dabei ausbleicht.
    * <p>
    * Ein Körper statt Partikel: der Ring bleibt auch bei Tageslicht und aus zwanzig Metern
    * sichtbar, und man sieht ihm an, wie weit der Puls schon gelaufen ist.
    */
   private void startSweep(ServerLevel level, Vec3 centre, UUID owner) {
      ItemStack stack = new ItemStack(ModItems.BLAST_RING);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(SWEEP_NEAR));
      Display.ItemDisplay ring = Hologram.spawnOwnerVisibleEffect(
         level, centre.add(0.0, 0.4, 0.0), stack, 6.0F, owner);
      if (ring == null) {
         return;
      }
      Hologram.setPose(ring, new Vector3f(), new Quaternionf(),
         new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
      sweeps.add(new Sweep(ring));
   }

   private void tickSweeps() {
      Iterator<Sweep> iterator = sweeps.iterator();
      while (iterator.hasNext()) {
         Sweep sweep = iterator.next();
         if (sweep.age++ >= SWEEP_TICKS) {
            Hologram.remove(sweep.ring);
            iterator.remove();
            continue;
         }
         double share = sweep.age / (double) SWEEP_TICKS;
         double open = 1.0 - (1.0 - share) * (1.0 - share);
         float across = (float) Math.max(Hologram.HIDDEN_SCALE, 2.0 * SWEEP_RADIUS * open);
         float band = (float) Math.max(Hologram.HIDDEN_SCALE, SWEEP_BAND / SWEEP_RING_BAND_UNIT * (1.0 - share));
         Hologram.setPose(sweep.ring, new Vector3f(), new Quaternionf(),
            new Vector3f(across, band, across), 2);
         int colour = mixColour(SWEEP_NEAR, SWEEP_FAR, share);
         if (colour != sweep.tone) {
            sweep.tone = colour;
            ItemStack stack = new ItemStack(ModItems.BLAST_RING);
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
            Hologram.setItem(sweep.ring, stack);
         }
      }
   }

   private static int mixColour(int from, int to, double share) {
      return Hologram.mixColor(from, to, share) & 0xF8F8F8;
   }

   /** Ein laufender Suchlaufring. */
   private static final class Sweep {
      private final Display.ItemDisplay ring;
      private int tone = -1;
      private int age;

      private Sweep(Display.ItemDisplay ring) {
         this.ring = ring;
      }
   }

   public boolean raiseShield(ServerPlayer player) {
      if (!shields.add(player.getUUID())) {
         return false;
      }

      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.0F, 1.35F);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.65F, 1.75F);
      Feedback.actionBar(player, "§b🛡 REFLEKTOR-KUGEL AKTIV §7· nur für dich sichtbar");
      StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      return true;
   }

   /** @return true, wenn ein Schild den Treffer geschluckt hat. */
   public boolean consumeShield(ServerPlayer player) {
      return consumeShield(player, null, KillFeed.Cause.BOW);
   }

   /**
    * Verbraucht den Schild und meldet, wer woran gescheitert ist.
    * <p>
    * Angreifer und Ursache werden durchgereicht, statt sie zu erraten: die Spezial-Items töten
    * über die eigene Buchführung, wo keine Schadensquelle mehr existiert, aus der sich das
    * ablesen ließe.
    */
   public boolean consumeShield(ServerPlayer player, ServerPlayer attacker, KillFeed.Cause cause) {
      if (!shields.remove(player.getUUID())) {
         return false;
      }
      ServerLevel level = player.level();
      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1.2F, 0.8F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.8F, 1.8F);
      level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 1.0, player.getZ(), 3, 0.2, 0.4, 0.2, 0.0);
      level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(),
         36, 0.65, 0.85, 0.65, 0.16);
      Feedback.actionBar(player, "§b🛡 SCHILD HAT DEN TREFFER GESCHLUCKT");
      if (attacker != null && !attacker.equals(player)) {
         Feedback.actionBar(attacker, "§b🛡 " + player.getGameProfile().name() + " HAT ABGEWEHRT");
      }
      KillFeed.blocked(player, attacker, cause);
      com.oneshotonekill.match.KillSignals.INSTANCE.shieldBlocked(player);
      StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      return true;
   }

   public boolean hasShield(ServerPlayer player) {
      return shields.contains(player.getUUID());
   }

    /**
     * Läuft für diesen Spieler gerade ein Radar-Puls?
     */
    public boolean isRadarActive(ServerPlayer viewer) {
        RadarView view = radarViews.get(viewer.getUUID());
        return view != null && view.remainingTicks > 0;
    }

    /**
     * Ist {@code target} vom Radar-Puls von {@code viewer} erfasst und läuft der Puls noch?
     */
    public boolean isRadarMarked(ServerPlayer viewer, ServerPlayer target) {
        RadarView view = radarViews.get(viewer.getUUID());
        return view != null && view.remainingTicks > 0 && view.targets.contains(target.getUUID());
    }

    /**
     * Nimmt die Reflektor-Kugel ohne Abwehr zurück, etwa wenn sie im Waffenspiel zu lange ungenutzt steht.
     */
    public boolean dropShield(ServerPlayer player) {
        if (!shields.remove(player.getUUID())) {
            return false;
        }
        StatusAbilities.Broadcaster.INSTANCE.refresh(player);
        return true;
    }


    public boolean startVanish(ServerPlayer player) {
      vanished.put(player.getUUID(), VANISH_TICKS);
      player.setInvisible(true);
      sendEmptyEquipment(player);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.9F, 1.1F);
      Feedback.actionBar(player, "§7👻 UNSICHTBAR — 15 s");
      refreshMagnetFields(player);
      return true;
   }

   /** Beendet die Unsichtbarkeit sofort (Eliminierung, Match-Ende, Map-Wechsel). */
   public void reveal(ServerPlayer player) {
      if (vanished.remove(player.getUUID()) != null) {
         player.setInvisible(false);
         sendRealEquipment(player);
         refreshMagnetFields(player);
      }
   }

   private static void sendEmptyEquipment(ServerPlayer vanishedPlayer) {
      List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> emptySlots = new ArrayList<>();
      for (EquipmentSlot slot : EquipmentSlot.values()) {
         emptySlots.add(com.mojang.datafixers.util.Pair.of(slot, ItemStack.EMPTY));
      }
      ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(vanishedPlayer.getId(), emptySlots);
      for (ServerPlayer other : vanishedPlayer.level().players()) {
         if (!other.getUUID().equals(vanishedPlayer.getUUID())) {
            other.connection.send(packet);
         }
      }
   }

   private static void sendRealEquipment(ServerPlayer player) {
      List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> realSlots = new ArrayList<>();
      for (EquipmentSlot slot : EquipmentSlot.values()) {
         realSlots.add(com.mojang.datafixers.util.Pair.of(slot, player.getItemBySlot(slot)));
      }
      ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(player.getId(), realSlots);
      for (ServerPlayer other : player.level().players()) {
         if (!other.getUUID().equals(player.getUUID())) {
            other.connection.send(packet);
         }
      }
   }

   public boolean isVanished(ServerPlayer player) {
      return vanished.containsKey(player.getUUID());
   }

   public boolean startArrowMagnet(ServerPlayer player) {
      magnets.put(player.getUUID(), MAGNET_TICKS);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.3F);
      Feedback.actionBar(player, "§9🧲 MAGNETFELD AKTIV — 15 s");
      MinecraftServer server = player.level().getServer();
      if (server != null) {
         StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
      }
      return true;
   }

   /**
    * Zündet den Gleitflug.
    * <p>
    * Der Absprung ist eine eigene Bewegung, kein bloßes Aufaddieren: die alte Geschwindigkeit
    * wird verworfen, damit ein Sprung aus dem Lauf nicht anders abhebt als einer aus dem Stand.
    */
   public boolean startGlide(ServerPlayer player) {
      gliding.put(player.getUUID(), new Glide(GLIDE_TICKS));
      launch(player);
      Feedback.actionBar(player, "§b🦅 GLEITFLUG — 8 s §7· Doppelt springen startet nach einer Landung neu");
      MinecraftServer server = player.level().getServer();
      if (server != null) {
         StatusAbilities.Broadcaster.INSTANCE.refresh(player);
         StatusAbilities.Broadcaster.INSTANCE.refreshGliders(server);
      }
      return true;
   }

   /**
    * Zweiter und jeder weitere Start desselben Flugs – ausgelöst durch doppeltes Springen.
    * <p>
    * Gültig ist er auch in der Luft: Wer sich in einem Sprung noch einmal Schub holt, dreht
    * damit die Richtung, und das ist genau der Griff, den ein Flug mit Blickschub braucht. Die
    * kurze Sperre verhindert nur, dass eine klemmende Taste den Vorrat in einem Tick verpulvert.
    */
   public boolean boostGlide(ServerPlayer player) {
      Glide glide = gliding.get(player.getUUID());
      // Nur aus dem Landezustand heraus: In der Luft ist die Sprungtaste das Steigen, und ein
      // doppelter Druck darauf duerfte den Flug nicht jedes Mal neu anwerfen.
      if (glide == null || !glide.landed || glide.sinceLaunch < GLIDE_BOOST_COOLDOWN) {
         return false;
      }
      glide.sinceLaunch = 0;
      glide.landed = false;
      launch(player);
      Feedback.actionBar(player, "§b🦅 GLEITFLUG — " + (glide.ticksLeft / 20 + 1) + " s");
      StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      return true;
   }

   /**
    * Der Absprung selbst.
    * <p>
    * Er ist eine eigene Bewegung, kein blosses Aufaddieren: die alte Geschwindigkeit wird
    * verworfen, damit ein Start aus dem Lauf nicht anders abhebt als einer aus dem Stand.
    * <p>
    * Der waagerechte Anteil kommt dabei aus dem Gierwinkel und nicht aus dem Blickvektor. Wer
    * beim Abspringen senkrecht nach unten sieht, hat in seinem Blick ueberhaupt keine
    * waagerechte Richtung mehr; der Start ginge dann kerzengerade nach oben.
    */
   private void launch(ServerPlayer player) {
      Vec3 look = player.getLookAngle();
      double rise = Math.max(look.y, GLIDE_LAUNCH_RISE);
      double yaw = Math.toRadians(player.getYRot());
      Vec3 flat = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
      Vec3 heading = flat.scale(Math.sqrt(Math.max(0.0, 1.0 - rise * rise))).add(0.0, rise, 0.0);
      // Ein Start aus voller Fahrt darf nicht bremsen, deshalb das Maximum.
      double speed = Math.max(player.getDeltaMovement().length(), GLIDE_LAUNCH_SPEED);
      player.setDeltaMovement(heading.scale(speed));
      player.syncVelocity = true;
      player.fallDistance = 0.0;
      player.setNoGravity(true);

      if (player.level() instanceof ServerLevel level) {
         // Zwei kurze Anschlaege statt eines Dauerklangs: ELYTRA_FLYING rauschte hier vorher
         // sekundenlang weiter, auch wenn der Flug laengst in eine Wand geendet war.
         level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.7F);
         level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.9F, 0.6F);
         level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(),
            50, 0.5, 0.1, 0.5, 0.28);
         level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.2, player.getZ(),
            30, 0.35, 0.1, 0.35, 0.12);
      }
   }

   public boolean isGliding(ServerPlayer player) {
      return gliding.containsKey(player.getUUID());
   }

   /** Fliegt der Spieler gerade waagerecht im Geschirr? Am Boden gilt die normale Haltung. */
   public boolean isGlideFlying(ServerPlayer player) {
      return gliding.containsKey(player.getUUID()) && !player.onGround();
   }

   public int remainingVanishTicks(ServerPlayer player) {
      return vanished.getOrDefault(player.getUUID(), 0);
   }

   public int remainingMagnetTicks(ServerPlayer player) {
      return magnets.getOrDefault(player.getUUID(), 0);
   }

   /* Stabile Momentaufnahme für das sichtbare Feld auf allen verbundenen Clients. */
   /** Stabile Momentaufnahme der fliegenden Spieler für alle verbundenen Clients. */
   public List<UUID> activeGliders() {
      return gliding.keySet().stream().sorted().toList();
   }

   public List<UUID> activeMagnetPlayers() {
      return magnets.keySet().stream().filter(uuid -> !vanished.containsKey(uuid)).sorted().toList();
   }

   public int remainingGlideTicks(ServerPlayer player) {
      Glide glide = gliding.get(player.getUUID());
      return glide == null ? 0 : glide.ticksLeft;
   }

   // -- Tick ----------------------------------------------------------------

   public void tick(MinecraftServer server) {
      tickRadar(server);
      tickShields(server);
      tickVanish(server);
      tickMagnets(server);
      tickGlide(server);
      tickSoftLanding(server);
      tickSweeps();
   }

   /**
    * Schickt das Vanilla-Glühbit gezielt nur an den jeweiligen Radar-Benutzer. Ein normales
    * {@code setGlowingTag} am Ziel wäre Entity-Zustand und würde an alle Spieler verteilt.
    */
   private void tickRadar(MinecraftServer server) {
      Iterator<Map.Entry<UUID, RadarView>> iterator = radarViews.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, RadarView> entry = iterator.next();
         ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
         if (viewer == null) {
            iterator.remove();
            continue;
         }

         RadarView view = entry.getValue();
         int remaining = view.remainingTicks - 1;
         boolean glowing = remaining > 0
            && Math.floorMod(remaining, RADAR_BLINK_TICKS * 2) < RADAR_BLINK_TICKS;
         sendRadarGlow(viewer, view.targets, glowing);
         if (remaining <= 0) {
            iterator.remove();
         } else {
            view.remainingTicks = remaining;
         }
      }
   }

   private static void sendRadarGlow(ServerPlayer viewer, List<UUID> targets, boolean radarGlow) {
      MinecraftServer server = viewer.level().getServer();
      if (server == null) {
         return;
      }
      for (UUID targetId : targets) {
         ServerPlayer target = server.getPlayerList().getPlayer(targetId);
         if (target == null || target.level() != viewer.level()) {
            continue;
         }
         byte flags = sharedFlags(target, radarGlow || target.isCurrentlyGlowing());
         SynchedEntityData.DataValue<Byte> value = new SynchedEntityData.DataValue<>(
            0, EntityDataSerializers.BYTE, flags);
         viewer.connection.send(new ClientboundSetEntityDataPacket(target.getId(), List.of(value)));
      }
   }

   private static byte sharedFlags(ServerPlayer target, boolean glowing) {
      int flags = 0;
      if (target.isOnFire()) flags |= 1;
      if (target.isShiftKeyDown()) flags |= 1 << 1;
      if (target.isSprinting()) flags |= 1 << 3;
      if (target.isSwimming()) flags |= 1 << 4;
      if (target.isInvisible()) flags |= 1 << 5;
      if (glowing) flags |= 1 << 6;
      if (target.isFallFlying()) flags |= 1 << 7;
      return (byte) flags;
   }

   private void clearRadarView(ServerPlayer viewer) {
      RadarView old = radarViews.remove(viewer.getUUID());
      if (old != null) {
         sendRadarGlow(viewer, old.targets, false);
      }
   }

   /** Entfernt nur verwaiste Zustände; die Kugel selbst existiert auf dem Server nicht. */
   private void tickShields(MinecraftServer server) {
      shields.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
   }

   private void tickVanish(MinecraftServer server) {
      countDown(vanished, server, (player, remaining) -> {
         if (remaining <= 0) {
            player.setInvisible(false);
            sendRealEquipment(player);
            Feedback.actionBar(player, "§7👻 Tarnung aufgehoben");
            StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
         } else {
            sendEmptyEquipment(player);
         }
      });
   }

   private void tickMagnets(MinecraftServer server) {
      countDown(magnets, server, (player, remaining) -> {
         if (remaining <= 0) {
            Feedback.actionBar(player, "§9🧲 Magnetfeld erloschen");
            StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
            return;
         }
         deflectArrows(player);
      });
   }

   /**
    * Reflektiert fremde Pfeile exakt an der sichtbaren Kugeloberfläche.
    * <p>
    * Die Kollisionsprüfung betrachtet sowohl den seit dem letzten Tick zurückgelegten als auch
    * den nächsten Flugabschnitt. Dadurch kann ein schneller Pfeil die nur drei Blöcke breite
    * Blase nicht zwischen zwei Server-Ticks überspringen.
    */
   private void deflectArrows(ServerPlayer player) {
      ServerLevel level = player.level();
      Vec3 center = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
      AABB field = new AABB(center, center).inflate(MAGNET_SCAN_RADIUS);
      for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, field)) {
         if (arrow.getOwner() == player || arrow.isRemoved() || arrow.onGround()) {
            continue;
         }

         Vec3 velocity = arrow.getDeltaMovement();
         if (velocity.lengthSqr() < 1.0E-5) {
            continue;
         }

         Vec3 previous = new Vec3(arrow.xo, arrow.yo, arrow.zo);
         Vec3 current = arrow.position();
         Vec3 contact = segmentSphereContact(previous, current, center, MAGNET_RADIUS);
         if (contact == null) {
            contact = segmentSphereContact(current, current.add(velocity), center, MAGNET_RADIUS);
         }
         if (contact == null && current.distanceToSqr(center) < MAGNET_RADIUS * MAGNET_RADIUS) {
            contact = current;
         }
         if (contact == null) {
            continue;
         }

         Vec3 normal = contact.subtract(center);
         if (normal.lengthSqr() < 1.0E-5) {
            normal = velocity.reverse();
         }
         normal = normal.normalize();
         // Ein Pfeil, der sich bereits aus dem Feld herausbewegt, darf nicht erneut umkehren.
         if (velocity.dot(normal) >= 0.0) {
            continue;
         }

         double speed = Math.max(0.6, velocity.length());
         Vec3 reflected = velocity.subtract(normal.scale(2.0 * velocity.dot(normal)));
         Vec3 magneticCurve = new Vec3(-normal.z, 0.08, normal.x).scale(0.16);
         reflected = reflected.add(magneticCurve);
         if (reflected.dot(normal) < speed * 0.18) {
            reflected = reflected.add(normal.scale(speed * 0.32));
         }
         reflected = reflected.normalize().scale(speed * 0.94);

         // Vor die Oberfläche zurücksetzen, damit der nächste Entity-Tick nicht doch noch den
         // Spieler trifft und der Pfeil nicht innerhalb der Blase mehrfach reflektiert wird.
         Vec3 outside = center.add(normal.scale(MAGNET_RADIUS + 0.08));
         arrow.setPos(outside.x, outside.y, outside.z);
         arrow.setDeltaMovement(reflected);
         arrow.syncVelocity = true;
         // Der Pfeil gehört ab jetzt dem Magnet-Träger: Sonst trifft er den Schützen als dessen
         // eigener Pfeil (kein Kill) oder schreibt einem Dritten den Kill des Schützen gut.
         arrow.setOwner(player);

         // Nur der tatsächliche Kontaktpunkt blitzt kurz rot/blau auf. Keine dauernden Sterne
         // oder Partikelringe mehr um den Spieler.
         level.sendParticles(MAGNET_RED, contact.x, contact.y, contact.z,
            4, 0.08, 0.08, 0.08, 0.015);
         level.sendParticles(MAGNET_BLUE, contact.x, contact.y, contact.z,
            4, 0.08, 0.08, 0.08, 0.015);
         level.playSound(null, contact.x, contact.y, contact.z,
            SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.32F, 1.85F);
      }
   }

   /** Erster Schnittpunkt eines Liniensegments mit einer Kugel, oder {@code null}. */
   private static Vec3 segmentSphereContact(Vec3 start, Vec3 end, Vec3 center, double radius) {
      Vec3 direction = end.subtract(start);
      double a = direction.lengthSqr();
      if (a < 1.0E-8) {
         return null;
      }
      Vec3 offset = start.subtract(center);
      double b = 2.0 * offset.dot(direction);
      double c = offset.lengthSqr() - radius * radius;
      double discriminant = b * b - 4.0 * a * c;
      if (discriminant < 0.0) {
         return null;
      }
      double root = Math.sqrt(discriminant);
      double first = (-b - root) / (2.0 * a);
      double second = (-b + root) / (2.0 * a);
      double t = first >= 0.0 && first <= 1.0 ? first : second;
      return t >= 0.0 && t <= 1.0 ? start.add(direction.scale(t)) : null;
   }

   /**
    * Ein Tick Flug – oder ein Tick Warten am Boden.
    * <p>
    * Die Flugzeit läuft immer ab – egal ob der Spieler fliegt oder am Boden steht.
    * Wer landet, behält die Steuerung am Boden und kann mit einem doppelten Sprung
    * vor Ablauf der Zeit jederzeit wieder abheben.
    */
   private void tickGlide(MinecraftServer server) {
      Iterator<Map.Entry<UUID, Glide>> iterator = gliding.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Glide> entry = iterator.next();
         ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
         if (player == null) {
            iterator.remove();
            continue;
         }

         Glide glide = entry.getValue();
         glide.sinceLaunch++;

         // Gesamtlaufzeit läuft kontinuierlich ab – egal ob fliegend oder gelandet
         player.fallDistance = 0.0;
         if (--glide.ticksLeft <= 0) {
            iterator.remove();
            endGlide(player);
            continue;
         }

         if (glide.sinceLaunch > 5 && player.onGround() && !glide.landed) {
            glide.landed = true;
            player.setNoGravity(false);
            Feedback.actionBar(player, "§b🦅 GELANDET — " + (glide.ticksLeft / 20 + 1)
               + " s übrig §7· doppelt springen und weiterfliegen");
            StatusAbilities.Broadcaster.INSTANCE.refresh(player);
         }

         if (glide.landed) {
            continue;
         }

         Vec3 motion = fly(player, player.getDeltaMovement());
         player.setDeltaMovement(motion);
         player.syncVelocity = true;

         if (player.level() instanceof ServerLevel level && glide.ticksLeft % 2 == 0) {
            // Die Kondensfahne bleibt hinter dem Spieler stehen, statt ihn zu umwehen. Sie
            // hängt an der Flugrichtung, nicht am Blick: Im Kurvenflug steht sie damit
            // sichtbar schräg, und man sieht seiner eigenen Bahn an, wohin sie führt.
            Vec3 tail = player.position().add(motion.normalize().scale(-0.7)).add(0.0, 0.55, 0.0);
            int density = motion.length() > 1.1 ? 4 : 2;
            level.sendParticles(ParticleTypes.CLOUD, tail.x, tail.y, tail.z, density, 0.12, 0.08, 0.12, 0.01);
            level.sendParticles(ParticleTypes.SMALL_FLAME, tail.x, tail.y, tail.z, density + 1, 0.1, 0.06, 0.1, 0.02);
         }
      }
   }

   /**
    * Ein Tick Flugmechanik: aus Blickrichtung und bisheriger Bewegung wird die neue.
    * <p>
    * Die Aufteilung in Richtung und Tempo ist Absicht. Ein einzelner Vektor, auf den Kraefte
    * addiert werden, vermischt beides: Eine Kurve bremst dann automatisch, ein Sturzflug
    * beschleunigt nur zufaellig, und jede Aenderung wirkt an der falschen Stelle. Getrennt
    * laesst sich beides sagen, was es soll – die Richtung folgt dem Blick, das Tempo der
    * Fluglage.
    */
   private Vec3 fly(ServerPlayer player, Vec3 motion) {
      double speed = motion.length();
      Vec3 look = player.getLookAngle().normalize();
      Vec3 heading = speed < 1.0E-4 ? look : motion.scale(1.0 / speed);

      // 1. Einlenken. Je schneller, desto traeger – das ist die gefuehlte Masse des Geschirrs.
      heading = rotateToward(heading, look, GLIDE_TURN_RATE / (1.0 + speed * GLIDE_TURN_DAMPING));

      // 2. Tempo. Der Zielwert haengt nur an der Fluglage, und er wird immer erreicht: Der Schub
      // kommt aus den Duesen und nicht aus der Hoehe, also gibt es nichts, was ihn im Steigflug
      // aufbrauchen koennte.
      double target = GLIDE_CRUISE
         + GLIDE_DIVE_BONUS * Math.max(0.0, -heading.y)
         - GLIDE_CLIMB_TAX * Math.max(0.0, heading.y);
      speed += Math.clamp(target - speed, -GLIDE_ACCEL, GLIDE_ACCEL);
      speed = Math.clamp(speed, GLIDE_MIN_SPEED, GLIDE_MAX_SPEED);

      // 3. Die Decke der Arena ist keine Wand, an der man klebt: Dort geht die Nase herunter.
      if (player.getY() >= glideCeiling(player) && heading.y > 0.0) {
         heading = withClimb(heading, GLIDE_CEILING_SINK, player.getYRot());
      }

      return heading.scale(speed);
   }

   /**
    * Dieselbe Richtung, aber mit gedeckeltem Steiganteil – die waagerechte Richtung bleibt.
    * <p>
    * Zeigt die Flugrichtung genau senkrecht, gibt es keine waagerechte Richtung mehr, die sich
    * erhalten liesse. Dann tritt der Gierwinkel an ihre Stelle: Die Nase kippt dorthin, wohin
    * der Spieler ohnehin sieht.
    */
   private static Vec3 withClimb(Vec3 heading, double climb, float yaw) {
      Vec3 flat = new Vec3(heading.x, 0.0, heading.z);
      if (flat.lengthSqr() < 1.0E-8) {
         double radians = Math.toRadians(yaw);
         flat = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
      }
      return flat.normalize().scale(Math.sqrt(Math.max(0.0, 1.0 - climb * climb))).add(0.0, climb, 0.0);
   }

   /**
    * Dreht {@code from} um hoechstens {@code maxRadians} in Richtung {@code to}.
    * <p>
    * <p>Eine gewichtete Summe der beiden Richtungen taete es fast – aber eben nur fast, und die
    * Ausnahmen sind genau die Faelle, die beim Fliegen staendig vorkommen. Zeigen beide Vektoren
    * auf dieselbe Achse, bleibt die Summe auf dieser Achse liegen: Wer senkrecht nach oben
    * fliegt und nach unten sieht, kaeme nie herum, und wer geradewegs steigt, bliebe steigen,
    * solange nicht ein Rundungsfehler die Symmetrie bricht. Genau das war vorher zu sehen.</p>
    * <p>
    * <p>Eine echte Drehung um die gemeinsame Senkrechte hat das Problem nicht. Fehlt diese
    * Senkrechte – bei genau entgegengesetzten Richtungen –, tut es irgendeine andere; die
    * Drehrichtung ist dort ohnehin beliebig. Nebenbei ergibt das eine gleichmaessige Drehrate in
    * Grad je Tick statt einer exponentiellen Annaeherung, und das fuehlt sich beim Steuern
    * berechenbarer an.</p>
    */
   private static Vec3 rotateToward(Vec3 from, Vec3 to, double maxRadians) {
      double angle = Math.acos(Math.clamp(from.dot(to), -1.0, 1.0));
      if (angle <= 1.0E-5) {
         return from;
      }
      if (angle <= maxRadians) {
         return to;
      }

      Vec3 axis = from.cross(to);
      if (axis.lengthSqr() < 1.0E-10) {
         // Genau entgegengesetzt: eine beliebige Senkrechte zur Flugrichtung.
         axis = from.cross(Math.abs(from.y) < 0.9 ? UP : EAST);
      }
      axis = axis.normalize();
      // Rodrigues, verkuerzt: die Achse steht senkrecht auf der Flugrichtung, damit faellt der
      // dritte Summand der allgemeinen Formel weg.
      return from.scale(Math.cos(maxRadians)).add(axis.cross(from).scale(Math.sin(maxRadians))).normalize();
   }

   /**
    * Haelt den Fallschaden nach einem beendeten Flug zurueck, bis der Spieler aufsetzt.
    * <p>
    * Die Fallhoehe wird jeden Tick auf null gesetzt, statt den Schaden abzufangen. Das ist der
    * ruhigere Weg: Vanilla rechnet den Schaden beim Aufsetzen aus der aufgelaufenen Fallhoehe,
    * und die kommt so ueber einen einzigen Tick nie hinaus – es entsteht also gar kein Schaden,
    * der abgefangen werden muesste, und keine andere Quelle von Fallschaden wird angetastet.
    */
   private void tickSoftLanding(MinecraftServer server) {
      Iterator<Map.Entry<UUID, Integer>> iterator = softLanding.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Integer> entry = iterator.next();
         ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
         if (player == null || entry.getValue() <= 0) {
            iterator.remove();
            continue;
         }

         player.fallDistance = 0.0;
         entry.setValue(entry.getValue() - 1);

         if (player.onGround()) {
            iterator.remove();
            if (player.level() instanceof ServerLevel level) {
               level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(),
                  18, 0.35, 0.05, 0.35, 0.04);
               level.playSound(null, player.getX(), player.getY(), player.getZ(),
                  SoundEvents.WOOL_STEP, SoundSource.PLAYERS, 0.8F, 0.8F);
            }
         }
      }
   }

   /** Der Schub erlischt: Fallschaden ist schon aus, der Rest ist Kulisse. */
   private void endGlide(ServerPlayer player) {
      player.fallDistance = 0.0;
      player.setNoGravity(false);
      if (!player.onGround()) {
         softLanding.put(player.getUUID(), SOFT_LANDING_TICKS);
      }
      if (player.level() instanceof ServerLevel level) {
         level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.4, player.getZ(),
            24, 0.4, 0.3, 0.4, 0.06);
         level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.7F, 1.4F);
         MinecraftServer server = level.getServer();
         if (server != null) {
            StatusAbilities.Broadcaster.INSTANCE.refresh(player);
            StatusAbilities.Broadcaster.INSTANCE.refreshGliders(server);
         }
      }
      Feedback.actionBar(player, "§b🦅 Schub erloschen");
   }

      /** Die Flughöhe bleibt unter Arena-Oberkante und Decke – außerhalb der Arena gilt kein Kampf. */
   private double glideCeiling(ServerPlayer player) {
      Arena arena = SpecialItemRules.activeArena(player);
      if (arena == null) {
         return player.getY();
      }
      double top = arena.getRegions().stream().mapToDouble(shape -> shape.getMaxY()).max().orElse(player.getY());
      double ceiling = arena.getHasCeiling() ? arena.getCeilingY() - 2.0 : Double.MAX_VALUE;
      return Math.min(top + GLIDE_HEADROOM, ceiling);
   }

   // -- Aufräumen -----------------------------------------------------------

   /* Beendet alle Zustände eines Spielers – bei Eliminierung, Respawn oder Verbindungsabbruch. */
   /**
    * Was ein Tod beendet – und das ist wenig.
    * <p>
    * Wer ein Spezial-Item einsetzt und dabei stirbt, soll es nicht verlieren: Schild, Magnetfeld
    * und Radar-Markierung laufen weiter. Nur der Flug endet, denn der Spieler wird beim Respawn
    * versetzt, und ein Schub, der ihn danach weiterzieht, wäre nicht mehr sein eigener.
    */
   public void clearOnDeath(ServerPlayer player) {
      removeGlider(player);
      softLanding.remove(player.getUUID());
   }

   /** Der volle Abbau – bei Verbindungsabbruch, Matchende und Arena-Reset. */
   public void clearFor(ServerPlayer player) {
      clearRadarView(player);
      if (vanished.remove(player.getUUID()) != null) {
         player.setInvisible(false);
      }
      if (magnets.remove(player.getUUID()) != null) {
         MinecraftServer server = player.level().getServer();
         if (server != null) {
            StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
         }
      }
      removeGlider(player);
      softLanding.remove(player.getUUID());
      shields.remove(player.getUUID());
   }

   private void removeGlider(ServerPlayer player) {
      if (gliding.remove(player.getUUID()) != null) {
         player.setNoGravity(false);
         MinecraftServer server = player.level().getServer();
         if (server != null) {
            StatusAbilities.Broadcaster.INSTANCE.refresh(player);
            StatusAbilities.Broadcaster.INSTANCE.refreshGliders(server);
         }
      }
   }

   public void reset(MinecraftServer server) {
      if (server != null) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearRadarView(player);
            player.setInvisible(false);
            player.setNoGravity(false);
         }
      }
      radarViews.clear();
      vanished.clear();
      magnets.clear();
      gliding.clear();
      softLanding.clear();
      shields.clear();
      if (server != null) {
         StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
      }
   }

   private static void refreshMagnetFields(ServerPlayer player) {
      MinecraftServer server = player.level().getServer();
      if (server != null) {
         StatusAbilities.Broadcaster.INSTANCE.refreshMagnetFields(server);
      }
   }

   // -- Hilfen --------------------------------------------------------------

   @FunctionalInterface
   private interface TickAction {
      void run(ServerPlayer player, int remaining);
   }

   /* Zählt eine Zustandskarte herunter und räumt abgelaufene sowie offline gegangene Einträge ab. */
   /**
    * Ein laufender Gleitflug.
    * <p>
    * {@code ticksLeft} ist die Gesamtlaufzeit und läuft kontinuierlich ab (auch bei Landung).
    * {@code sinceLaunch} misst den Abstand zum letzten Absprung – daran hängen sowohl der
    * Startschub als auch die Sperre gegen doppelte Auslösung.
    */
   private static final class Glide {
      private int ticksLeft;
      private int sinceLaunch;
      /** Aufgesetzt und wartend: Flugmechanik pausiert, aber Zeit läuft weiter. */
      private boolean landed;

      private Glide(int ticksLeft) {
         this.ticksLeft = ticksLeft;
      }
   }

   private static final class RadarView {
      private final List<UUID> targets;
      private int remainingTicks;

      private RadarView(List<UUID> targets, int remainingTicks) {
         this.targets = targets;
         this.remainingTicks = remainingTicks;
      }
   }

   private void countDown(Map<UUID, Integer> states, MinecraftServer server, TickAction action) {
      Iterator<Map.Entry<UUID, Integer>> iterator = states.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Integer> entry = iterator.next();
         ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
         if (player == null) {
            iterator.remove();
            continue;
         }

         int remaining = entry.getValue() - 1;
         if (remaining <= 0) {
            iterator.remove();
            action.run(player, 0);
         } else {
            entry.setValue(remaining);
            action.run(player, remaining);
         }
      }
   }


   public static final class Broadcaster {
      public static final Broadcaster INSTANCE = new Broadcaster();
   
      /** Grobes Raster: Restzeiten werden nur alle halbe Sekunde nachgeführt. */
      private static final int RESYNC_TICKS = 10;
   
      private final Map<UUID, AbilityStatusPayload> lastSent = new HashMap<>();
      private final Map<UUID, List<UUID>> lastMagnetFields = new HashMap<>();
      private final Map<UUID, List<UUID>> lastGliders = new HashMap<>();
      private final Map<UUID, DeployableMarkersPayload> lastMarkers = new HashMap<>();
   
      private Broadcaster() {
      }
   
      public void tick(MinecraftServer server) {
         if (server.getTickCount() % RESYNC_TICKS != 0) {
            return;
         }
   
         List<UUID> magnetPlayers = StatusAbilities.INSTANCE.activeMagnetPlayers();
         List<UUID> glidingPlayers = StatusAbilities.INSTANCE.activeGliders();
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AbilityStatusPayload status = collect(player);
            AbilityStatusPayload previous = lastSent.get(player.getUUID());
            if (!status.equals(previous)) {
               lastSent.put(player.getUUID(), status);
               ServerPlayNetworking.send(player, status);
            }
            if (!magnetPlayers.equals(lastMagnetFields.get(player.getUUID()))) {
               lastMagnetFields.put(player.getUUID(), magnetPlayers);
               ServerPlayNetworking.send(player, new MagnetFieldsPayload(magnetPlayers));
            }
            if (!glidingPlayers.equals(lastGliders.get(player.getUUID()))) {
               lastGliders.put(player.getUUID(), glidingPlayers);
               ServerPlayNetworking.send(player, new GlidingPlayersPayload(glidingPlayers));
            }
            sendMarkers(player);
         }
         lastSent.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
         lastMagnetFields.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
         lastGliders.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
         lastMarkers.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
      }
   
      private AbilityStatusPayload collect(ServerPlayer player) {
         StatusAbilities status = StatusAbilities.INSTANCE;
         return new AbilityStatusPayload(
            status.hasShield(player),
            status.remainingVanishTicks(player),
            status.remainingMagnetTicks(player),
            status.remainingGlideTicks(player),
            Deployables.INSTANCE.remainingFreezeTicks(player),
            ArmedShots.INSTANCE.armedLabel(player),
            Deployables.INSTANCE.chargeCount(player),
            Deployables.INSTANCE.trapCount(player),
            Deployables.INSTANCE.turretCount(player));
      }
   
      /** Meldet Zustandswechsel, die Eingaben beeinflussen, ohne auf das grobe Raster zu warten. */
      public void refresh(ServerPlayer player) {
         AbilityStatusPayload status = collect(player);
         lastSent.put(player.getUUID(), status);
         ServerPlayNetworking.send(player, status);
         sendMarkers(player);
      }

      /**
       * Schickt die Peilung der eigenen abgestellten Geräte, wenn sie sich geändert hat.
       * <p>
       * Die Liste ist kurz, ändert sich aber ständig ein wenig – ein Turm dreht sich, eine Falle
       * wird ausgelöst. Verglichen wird deshalb das fertige Paket: Solange Positionen und
       * Zustände gleich bleiben, geht nichts über die Leitung.
       */
      private void sendMarkers(ServerPlayer player) {
         DeployableMarkersPayload markers = new DeployableMarkersPayload(Deployables.INSTANCE.markersFor(player));
         if (markers.equals(lastMarkers.get(player.getUUID()))) {
            return;
         }
         lastMarkers.put(player.getUUID(), markers);
         ServerPlayNetworking.send(player, markers);
      }
   
      /** Verteilt das sichtbare Magnetfeld sofort an alle Clients. */
      public void refreshMagnetFields(MinecraftServer server) {
         List<UUID> magnetPlayers = StatusAbilities.INSTANCE.activeMagnetPlayers();
         MagnetFieldsPayload payload = new MagnetFieldsPayload(magnetPlayers);
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            lastMagnetFields.put(player.getUUID(), magnetPlayers);
            ServerPlayNetworking.send(player, payload);
         }
      }

      /** Verteilt die aktiven Gleiter sofort an alle Clients. */
      public void refreshGliders(MinecraftServer server) {
         List<UUID> glidingPlayers = StatusAbilities.INSTANCE.activeGliders();
         GlidingPlayersPayload payload = new GlidingPlayersPayload(glidingPlayers);
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            lastGliders.put(player.getUUID(), glidingPlayers);
            ServerPlayNetworking.send(player, payload);
         }
      }
   
      public void reset() {
         lastSent.clear();
         lastMagnetFields.clear();
         lastGliders.clear();
      }
   }
}

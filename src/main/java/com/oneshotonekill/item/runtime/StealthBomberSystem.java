package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.match.ScoreboardManager;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.registry.ModItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Setzt einen Bomber auf einen Gegner an, der ihm folgt und dabei Bomben abwirft.
 * <p>
 * Bomber und Bomben sind keine Mobs, sondern reine Serverobjekte mit einer
 * {@link Display.ItemDisplay} als Körper: sie können niemanden rammen, sind nicht abschießbar
 * und brauchen keine Wegfindung. Die Abwurfhöhe respektiert die Decke der Karte.
 * <p>
 * Die Bomben reißen echte Krater, die über {@link ArenaDemolition} wieder zuwachsen – dieselbe
 * Buchführung wie beim Luftangriff. Sie sind aber deutlich kleiner als dessen Krater, denn ein
 * Bomber wirft ein Dutzend davon: mit Luftangriffsradius bliebe von der Karte nichts übrig.
 */
@SuppressWarnings({"ConstantValue", "resource"})
public final class StealthBomberSystem {
   public static final StealthBomberSystem INSTANCE = new StealthBomberSystem();

   private static final int DURATION_TICKS = 200;
   private static final int DROP_INTERVAL_TICKS = 10;
   private static final int MAX_KILLS_PER_RUN = 3;
   /** Von so weit draußen fliegt der Bomber ein, bevor er über dem Ziel steht. */
   private static final double APPROACH_DISTANCE = 42.0;
   /** Erst ab dieser waagerechten Nähe zum Ziel öffnet sich der Schacht. */
   private static final double DROP_RANGE = 7.0;

   private static final int TITLE_FADE_IN = 6;
   private static final int TITLE_STAY = 44;
   private static final int TITLE_FADE_OUT = 14;
   private static final double FLIGHT_HEIGHT = 14.0;
   /**
    * Anteil der Reststrecke, den der Bomber je Tick aufholt – und die Obergrenze dafür.
    * <p>
    * Nur mit dem Anteil allein ginge beides nicht zusammen: hoch genug, um einem laufenden
    * Spieler wirklich zu folgen, wäre der Anflug aus der Ferne nach einer halben Sekunde
    * vorbei. Die Deckelung macht daraus zwei Verhaltensweisen aus einer Regel – weiter weg als
    * {@code MAX_SPEED / FOLLOW_SPEED} Blöcke fliegt er mit voller Geschwindigkeit geradeaus,
    * näher dran zieht er weich nach.
    * <p>
    * {@value #MAX_SPEED} Blöcke je Tick sind rund 22 Blöcke in der Sekunde und damit etwa das
    * Vierfache eines sprintenden Spielers; abschütteln lässt er sich also nicht.
    */
   private static final double FOLLOW_SPEED = 0.22;
   private static final double MAX_SPEED = 1.1;
   /**
    * Nachbrenner, wenn das Ziel plötzlich woanders steht.
    * <p>
    * Der Sofort-Respawn setzt einen Getroffenen quer über die Karte, und zwar bewusst weit vom
    * Sterbeort weg. Mit der normalen Reisegeschwindigkeit bräuchte der Bomber dafür mehrere
    * Sekunden und käme praktisch nie wieder zum Werfen. Erkannt wird der Sprung daran, dass
    * sich das Ziel in einem einzigen Tick weiter bewegt hat, als es laufend je könnte.
    */
   private static final double RESPAWN_JUMP = 12.0;
   private static final double BOOST_SPEED = 3.2;
   private static final int BOOST_TICKS = 80;
   private static final float BOMBER_SCALE = 2.2F;
   /**
    * Der Bomber kreist über seinem Ziel, statt darüber zu stehen.
    * <p>
    * Zwei Gründe: ein stehendes Flugzeug sieht falsch aus und hätte gar keine Flugrichtung,
    * nach der es sich ausrichten könnte. Und die Ladungen fallen so über einen Ring verteilt
    * statt alle auf denselben Punkt – wer sich bewegt, kann ihnen dadurch entgehen.
    * <p>
    * Der Radius bleibt kleiner als der Wirkungsradius einer Ladung; sonst könnte ein Ziel
    * mitten im Ring stehen und würde nie getroffen.
    */
   private static final double ORBIT_RADIUS = 4.8;
   private static final double ORBIT_SPEED = 0.10;

   /**
    * Fallverhalten der Bombe. Die Ausklinkgeschwindigkeit ist bewusst nicht null: eine Bombe,
    * die aus dem Stand zu fallen beginnt, wirkt die erste halbe Sekunde wie aufgehängt.
    * Aus der Flughöhe schlägt sie damit nach gut einer halben Sekunde ein.
    */
   private static final double BOMB_RELEASE_SPEED = 0.35;
   private static final double BOMB_GRAVITY = 0.14;
   private static final double BOMB_TERMINAL_SPEED = 2.6;
   private static final float BOMB_SCALE = 1.1F;
   /** Nach so vielen Ticks ohne Einschlag verschwindet eine Bombe von selbst. */
   private static final int BOMB_MAX_FLIGHT_TICKS = 120;
   /**
    * Anteil der Zielbewegung, den die Bombe vorhält.
    * <p>
    * Bei 1 träfe sie einen gleichmäßig laufenden Spieler jedes Mal – das nähme ihm jede Chance.
    * Knapp darunter landet sie dicht vor ihm, wer die Richtung wechselt, kommt davon.
    */
   private static final double BOMB_LEAD = 0.40;
   private static final double BOMB_SPREAD = 2.0;
   /** Waagerechte Höchstgeschwindigkeit der Bombe; darüber sähe der Wurf nach Zielsuchrakete aus. */
   private static final double BOMB_MAX_DRIFT = 0.55;
   /** Der Zielring am Boden zieht sich zusammen, während die Bombe fällt. */
   private static final int MARKER_POINTS = 14;
   private static final int MARKER_INTERVAL_TICKS = 2;
   private static final int MARKER_COLOUR = 0xFF4A2A;
   private static final float MARKER_SCALE = 1.4F;

   /** Wirkungsradius einer Ladung – klein, weil viele davon fallen. */
   private static final double BOMB_RADIUS = 3.4;
   private static final int CRATER_RADIUS = 3;
   private static final double CRATER_DEPTH_OFFSET = 1.0;
   private static final int CRATER_RESTORE_DELAY_TICKS = 20 * 6;

   private static final float SHAKE_RANGE = 38.0F;
   private static final float SHAKE_INTENSITY = 2.4F;
   private static final int SHAKE_TICKS = 16;
   /** Warmweißer Aufblitzer im Moment der Detonation. */
   private static final ColorParticleOption BLAST_FLASH =
      ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.86F, 0.62F);
   /** Stützpunkte des Druckwellenrings am Boden. */
   private static final int SHOCKWAVE_POINTS = 22;
   private static final double SHOCKWAVE_RADIUS = 3.4;

   /**
    * Der Bomber tötet ausdrücklich nicht mit einem Treffer.
    * <p>
    * Er wirft im festen Takt und zielt dabei nicht – ein Sofort-Kill pro Ladung machte jeden
    * Aufenthalt unter ihm zum Todesurteil. Erst drei Treffer innerhalb des Zeitfensters
    * eliminieren, wie beim Geschützturm.
    */
   private static final int BOMB_HITS_TO_KILL = 3;
   private static final int BOMB_HIT_MEMORY_TICKS = 160;

   private final List<Bomber> bombers = new ArrayList<>();
   private final List<FallingBomb> bombs = new ArrayList<>();
   private final Map<UUID, BombAccount> bombHits = new HashMap<>();

   private StealthBomberSystem() {
   }

   /**
    * Stellt die wählbaren Gegner zusammen und schickt sie an den Client, der daraus das
    * Auswahlmenü baut. Verbraucht wird noch nichts – erst die Wahl kostet das Item.
    */
   public boolean openTargetMenu(ServerPlayer attacker) {
      MinecraftServer server = attacker.level().getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (server == null || worlds == null) {
         return false;
      }

      List<BomberTargetsPayload.Target> targets = new ArrayList<>();
      for (ServerPlayer candidate : candidates(server, worlds, attacker)) {
         targets.add(new BomberTargetsPayload.Target(
            candidate.getUUID(),
            candidate.getGameProfile().name(),
            candidate.getX(), candidate.getY(), candidate.getZ(),
            ScoreboardManager.INSTANCE.getStreak(candidate.getUUID())));
      }

      // Der Anfordernde steht selbst mit auf der Liste – bleibt sie leer, läuft kein Match
      // oder er steht gar nicht in der Arena.
      if (targets.isEmpty()) {
         Feedback.actionBar(attacker, Component.translatable("actionbar.oneshotonekill.bomber_only_in_match"));
         return false;
      }

      ServerPlayNetworking.send(attacker, new BomberTargetsPayload(targets));
      return true;
   }

   /** Startet den Bomber auf den im Menü gewählten Gegner. */
   public void launch(ServerPlayer attacker, UUID targetId) {
      if (Deployables.INSTANCE.isFrozen(attacker)) {
         return;
      }
      MinecraftServer server = attacker.level().getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (server == null || worlds == null || !hasBomberItem(attacker)) {
         return;
      }

      // Die Wahl kam vom Client – sie wird hier vollständig nachgeprüft, statt ihr zu glauben.
      ServerPlayer target = null;
      for (ServerPlayer candidate : candidates(server, worlds, attacker)) {
         if (candidate.getUUID().equals(targetId)) {
            target = candidate;
            break;
         }
      }
      if (target == null) {
         Feedback.actionBar(attacker, "§7🐉 Ziel nicht mehr erreichbar");
         return;
      }

      ServerLevel level = attacker.level();
      Arena arena = worlds.getActive();

      // Der Bomber setzt weit draußen an und fliegt ein, statt über dem Kopf zu erscheinen.
      // Er kommt aus der Richtung des Anfordernden – der sieht ihn damit über sich hinweg
      // auf sein Ziel zuziehen.
      Vec3 inbound = target.position().subtract(attacker.position());
      inbound = inbound.horizontalDistanceSqr() < 1.0
         ? new Vec3(1.0, 0.0, 0.0)
         : new Vec3(inbound.x, 0.0, inbound.z).normalize();
      Vec3 start = target.position()
         .subtract(inbound.scale(APPROACH_DISTANCE))
         .add(0.0, flightHeight(arena, target), 0.0);

      Bomber bomber = new Bomber(attacker.getUUID(), target.getUUID(), DURATION_TICKS, start);
      bomber.heading = (float) Math.atan2(inbound.x, inbound.z);
      bomber.display = Hologram.spawn(level, start, new ItemStack(ModItems.STEALTH_BOMBER), BOMBER_SCALE, 0);
      bombers.add(bomber);

      consumeItem(attacker);
      announceLaunch(server, worlds, arena, attacker, target, start);
      broadcastCamera(server, worlds, arena, bomber, target, false, false, 0.0, 0.0, 0.0, false);
   }

   /**
    * Der Auftakt: Titel, Sirene und ein fernes Grollen aus der Anflugrichtung.
    * <p>
    * Bewusst dreistufig – der Anfordernde bekommt eine Freigabe, das Ziel eine Warnung, alle
    * anderen nur das Geräusch. Wer nicht gemeint ist, soll den Bomber hören, bevor er ihn sieht.
    */
   private void announceLaunch(MinecraftServer server, ArenaWorlds worlds, Arena arena,
                               ServerPlayer attacker, ServerPlayer target, Vec3 start) {
      boolean onSelf = target.equals(attacker);
      String targetName = target.getGameProfile().name();

      if (onSelf) {
         OsokEffects.INSTANCE.sendTitle(attacker,
            Component.translatable("title.oneshotonekill.bomber.self_title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
            Component.translatable("title.oneshotonekill.bomber.self_subtitle").withStyle(ChatFormatting.GRAY),
            TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
      } else {
         OsokEffects.INSTANCE.sendTitle(attacker,
            Component.translatable("title.oneshotonekill.bomber.launched_title").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
            Component.translatable("title.oneshotonekill.bomber.launched_subtitle", targetName).withStyle(ChatFormatting.LIGHT_PURPLE),
            TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
         OsokEffects.INSTANCE.sendTitle(target,
            Component.translatable("title.oneshotonekill.bomber.inbound_title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
            Component.translatable("title.oneshotonekill.bomber.inbound_subtitle").withStyle(ChatFormatting.GRAY),
            TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
      }

      // Freigabeton für den Anfordernden, Alarm für das Ziel – beide privat, damit sie sich
      // nicht mit der Entfernung abschwächen.
      OsokEffects.INSTANCE.sendPrivateSound(attacker, SoundEvents.BEACON_ACTIVATE, 0.9F, 1.6F);
      OsokEffects.INSTANCE.sendPrivateSound(attacker, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8F, 1.9F);
      OsokEffects.INSTANCE.sendPrivateSound(target, SoundEvents.WITHER_SPAWN, 0.55F, 1.8F);
      if (!onSelf) {
         OsokEffects.INSTANCE.sendPrivateSound(target, SoundEvents.BELL_BLOCK, 1.0F, 0.6F);
      }

      // Das Grollen kommt aus der Ferne, von dort, wo der Bomber steht.
      ServerLevel level = attacker.level();
      level.playSound(null, start.x, start.y, start.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3.4F, 0.45F);
      level.playSound(null, start.x, start.y, start.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2.6F, 0.55F);

      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(listener) != arena || listener.equals(attacker) || listener.equals(target)) {
            continue;
         }
         Feedback.actionBar(listener, "§8🐉 Bomber im Anflug auf §7" + targetName);
      }
   }

   /**
    * Wer sich anvisieren lässt – der Anfordernde ausdrücklich eingeschlossen.
    * <p>
    * Sich selbst zu bombardieren ist keine Panne, sondern gewollt: nur so lässt sich der Bomber
    * allein auf dem Server ausprobieren, und im Gefecht ist ein Angriff auf die eigene Position
    * eine legitime, wenn auch teure Notbremse.
    */
   private List<ServerPlayer> candidates(MinecraftServer server, ArenaWorlds worlds, ServerPlayer attacker) {
      Arena arena = worlds.getActive();
      List<ServerPlayer> found = new ArrayList<>();
      if (MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING || worlds.arenaOf(attacker) != arena) {
         return found;
      }
      for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(candidate) == arena && candidate.isAlive()) {
            found.add(candidate);
         }
      }
      return found;
   }

   public void tick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (level == null || arena == null) {
         reset();
         return;
      }

      tickBombers(server, worlds, level, arena);
      tickBombs(server, level, arena);
   }

   private void tickBombers(MinecraftServer server, ArenaWorlds worlds, ServerLevel level, Arena arena) {
      Iterator<Bomber> iterator = bombers.iterator();
      while (iterator.hasNext()) {
         Bomber bomber = iterator.next();
         ServerPlayer target = server.getPlayerList().getPlayer(bomber.target);
         bomber.ticksLeft--;
         if (bomber.ticksLeft <= 0 || bomber.kills >= MAX_KILLS_PER_RUN || target == null || worlds.arenaOf(target) != arena) {
            iterator.remove();
            Hologram.remove(bomber.display);
            clearCamera(server, worlds, arena);
            continue;
         }

         noteTargetJump(level, bomber, target);

         bomber.orbit += ORBIT_SPEED;
         Vec3 desired = target.position().add(
            Math.cos(bomber.orbit) * ORBIT_RADIUS,
            flightHeight(arena, target),
            Math.sin(bomber.orbit) * ORBIT_RADIUS);
         Vec3 previous = bomber.position;
         Vec3 step = desired.subtract(previous).scale(FOLLOW_SPEED);
         double topSpeed = bomber.boostTicks > 0 ? BOOST_SPEED : MAX_SPEED;
         if (step.lengthSqr() > topSpeed * topSpeed) {
            step = step.normalize().scale(topSpeed);
         }
         bomber.position = previous.add(step);
         if (bomber.boostTicks > 0) {
            bomber.boostTicks--;
         }
         renderBomber(level, bomber, previous);

         // Der Schacht öffnet erst über dem Ziel – während des Anflugs fällt nichts.
         boolean overTarget = bomber.position.subtract(target.position()).horizontalDistanceSqr()
            <= DROP_RANGE * DROP_RANGE;
         boolean dropped = false;
         if (overTarget && bomber.ticksLeft % DROP_INTERVAL_TICKS == 0) {
            releaseBomb(level, bomber, target);
            dropped = true;
         }
         broadcastCamera(server, worlds, arena, bomber, target, dropped, false, 0.0, 0.0, 0.0, target.isDeadOrDying() || target.isSpectator());
      }
   }

   /**
    * Erkennt, dass das Ziel versetzt wurde, und schaltet den Nachbrenner zu.
    * <p>
    * Beim ersten Tick eines Bombers gibt es noch keinen Vergleichswert – dort wird nur gemerkt,
    * nicht ausgelöst, sonst zündete der Anflug seinen eigenen Nachbrenner.
    */
   private void noteTargetJump(ServerLevel level, Bomber bomber, ServerPlayer target) {
      Vec3 now = target.position();
      if (bomber.lastTargetPosition != null
         && now.distanceToSqr(bomber.lastTargetPosition) > RESPAWN_JUMP * RESPAWN_JUMP) {
         bomber.boostTicks = BOOST_TICKS;
         level.playSound(null, bomber.position.x, bomber.position.y, bomber.position.z,
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.8F, 0.7F);
         Feedback.actionBar(target, "§c🐉 BOMBER SETZT NACH");
      }
      bomber.lastTargetPosition = now;
   }

   private double flightHeight(Arena arena, ServerPlayer target) {
      if (!arena.getHasCeiling()) {
         return FLIGHT_HEIGHT;
      }
      // Auf überdachten Karten bleibt der Bomber einen Block unter der Decke.
      return Math.clamp(arena.getCeilingY() - 1.0 - target.getY(), 3.0, FLIGHT_HEIGHT);
   }

   /**
    * Dreht den Rumpf in die Flugrichtung.
    * <p>
    * Der Gierwinkel ist {@code atan2(x, z)} der Bewegung und nicht dessen Gegenteil, obwohl das
    * Modell mit der Nase nach -Z gebaut ist. Grund ist eine Drehung, die man dem Modell nicht
    * ansieht: {@code DisplayRenderer.ItemDisplayRenderer#submitInner} legt vor dem Zeichnen ein
    * {@code Axis.YP.rotation(PI)} auf den Stapel. Die Nase zeigt im Ergebnis also nach +Z, und
    * ohne diese Kehrtwende flog der Bomber rückwärts.
    */
   private void renderBomber(ServerLevel level, Bomber bomber, Vec3 previous) {
      Vec3 at = bomber.position;
      Vec3 travel = at.subtract(previous);

      if (bomber.display != null) {
         Hologram.move(bomber.display, at);
         if (travel.horizontalDistanceSqr() > 1.0E-6) {
            bomber.heading = (float) Math.atan2(travel.x, travel.z);
         }
         Hologram.setTransform(bomber.display, BOMBER_SCALE, bomber.heading);
      }

      // Kondensstreifen hinter den beiden Düsen; unter Nachbrenner glühen sie zusätzlich.
      Vec3 back = travel.horizontalDistanceSqr() > 1.0E-6 ? travel.normalize().scale(-1.4) : new Vec3(0.0, 0.0, 1.4);
      Vec3 side = new Vec3(-back.z, 0.0, back.x).normalize().scale(0.7);
      for (int wing = -1; wing <= 1; wing += 2) {
         Vec3 nozzle = at.add(back).add(side.scale(wing));
         level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, nozzle.x, nozzle.y, nozzle.z, 1, 0.02, 0.0, 0.02, 0.0);
         if (bomber.boostTicks > 0) {
            level.sendParticles(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, 2, 0.05, 0.05, 0.05, 0.02);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, nozzle.x, nozzle.y, nozzle.z, 1, 0.08, 0.02, 0.08, 0.01);
         }
      }
      if (bomber.ticksLeft % 24 == 0) {
         level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 0.7F, 0.8F);
      }
   }

   /**
    * Klinkt eine Bombe aus und richtet sie auf den Punkt, an dem das Ziel gleich stehen wird.
    * <p>
    * Senkrecht fallen zu lassen wäre einfacher, träfe aber niemanden: der Bomber kreist, hinkt
    * dem Ziel nach, und in der halben Sekunde Fallzeit ist ein laufender Spieler längst
    * woanders. Aus der Fallzeit und der Zielgeschwindigkeit ergibt sich der Vorhaltepunkt; die
    * waagerechte Geschwindigkeit ist gedeckelt, damit aus der Bombe keine Zielsuchrakete wird.
    */
   private void releaseBomb(ServerLevel level, Bomber bomber, ServerPlayer target) {
      Vec3 from = bomber.position.add(0.0, -0.9, 0.0);
      double fallTime = estimateFallTime(from.y - target.getY());
      double spreadAngle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
      double spreadDist = ThreadLocalRandom.current().nextDouble() * BOMB_SPREAD;
      Vec3 spread = new Vec3(Math.cos(spreadAngle) * spreadDist, 0.0, Math.sin(spreadAngle) * spreadDist);
      Vec3 aim = target.position().add(target.getDeltaMovement().scale(fallTime * BOMB_LEAD)).add(spread);

      Vec3 drift = new Vec3(aim.x - from.x, 0.0, aim.z - from.z).scale(1.0 / Math.max(1.0, fallTime));
      if (drift.lengthSqr() > BOMB_MAX_DRIFT * BOMB_MAX_DRIFT) {
         drift = drift.normalize().scale(BOMB_MAX_DRIFT);
      }

      FallingBomb bomb = new FallingBomb(bomber.owner, from, drift.add(0.0, -BOMB_RELEASE_SPEED, 0.0));
      bomb.markerY = target.getY();
      bomb.display = Hologram.spawn(level, from, new ItemStack(ModItems.BOMBER_BOMB), BOMB_SCALE, 0);
      bombs.add(bomb);

      level.playSound(null, from.x, from.y, from.z, SoundEvents.PISTON_CONTRACT, SoundSource.HOSTILE, 0.8F, 0.5F);
      level.sendParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 4, 0.15, 0.05, 0.15, 0.01);
   }

   /**
    * Wie lange die Bombe bis zum Ziel braucht – die Umkehrung des Fallwegs.
    * <p>
    * Aus {@code h = v0*t + g*t²/2} folgt die positive Lösung der quadratischen Gleichung. Die
    * Endgeschwindigkeit bleibt außen vor: auf den üblichen zwölf bis vierzehn Blöcken wird sie
    * gar nicht erreicht.
    */
   private static double estimateFallTime(double height) {
      if (height <= 0.0) {
         return 1.0;
      }
      double v0 = BOMB_RELEASE_SPEED;
      return (-v0 + Math.sqrt(v0 * v0 + 2.0 * BOMB_GRAVITY * height)) / BOMB_GRAVITY;
   }

   private void tickBombs(MinecraftServer server, ServerLevel level, Arena arena) {
      Iterator<FallingBomb> iterator = bombs.iterator();
      while (iterator.hasNext()) {
         FallingBomb bomb = iterator.next();
         bomb.ticksAlive++;
         bomb.velocity = new Vec3(bomb.velocity.x,
            Math.max(-BOMB_TERMINAL_SPEED, bomb.velocity.y - BOMB_GRAVITY),
            bomb.velocity.z);

         Vec3 from = bomb.position;
         Vec3 to = from.add(bomb.velocity);
         // Ein Strahl von der alten zur neuen Lage statt einer Spaltensuche: die Bombe fliegt
         // jetzt schräg, und so wird auch eine Wand getroffen, nicht nur der Boden darunter.
         // Die Bombe ist keine Entity, also gibt es auch keinen Kollisionskontext von einer.
         // Der Weg führt über CollisionContext.empty() und nicht über die Entity-Überladung
         // mit null: die reicht das Argument an CollisionContext.of weiter, und das verlangt
         // ausdrücklich ein Nicht-Null.
         HitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));

         if (hit.getType() != HitResult.Type.MISS) {
            iterator.remove();
            Hologram.remove(bomb.display);
            detonate(server, level, arena, bomb, hit.getLocation());
            continue;
         }
         if (bomb.ticksAlive > BOMB_MAX_FLIGHT_TICKS || to.y < level.getMinY()) {
            iterator.remove();
            Hologram.remove(bomb.display);
            continue;
         }

         bomb.position = to;
         renderBomb(level, bomb);
      }
   }

   /**
    * Richtet die Bombe entlang ihrer Flugbahn aus und zeichnet, was sie ankündigt.
    * <p>
    * Die Nase folgt der Geschwindigkeit, statt einer festen Kippkurve: seit die Bombe schräg
    * fliegt, wäre jede vorgegebene Neigung falsch. Aus dem Modell (Nase nach -Z) und der
    * 180-Grad-Drehung des Renderers ergibt sich der Gierwinkel als {@code atan2(x, z)} und die
    * Neigung als Arkussinus der senkrechten Komponente.
    * <p>
    * Dazu drei Ankündigungen: eine Rauchfahne, ein Pfeifen, das mit dem Fall höher wird, und
    * ein Ring am Boden, der sich um den Einschlagpunkt zusammenzieht. Der Ring ist kein
    * Zierrat – ohne ihn wäre der Bomber reines Glück, mit ihm kann man weglaufen.
    */
   private void renderBomb(ServerLevel level, FallingBomb bomb) {
      Vec3 heading = bomb.velocity.lengthSqr() < 1.0E-6 ? new Vec3(0.0, -1.0, 0.0) : bomb.velocity.normalize();
      if (bomb.display != null) {
         Hologram.move(bomb.display, bomb.position);
         Hologram.setTransform(bomb.display, BOMB_SCALE,
            (float) Math.atan2(heading.x, heading.z),
            (float) Math.asin(Mth.clamp(heading.y, -1.0, 1.0)));
      }

      Vec3 tail = bomb.position.subtract(heading.scale(0.5));
      level.sendParticles(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 2, 0.04, 0.04, 0.04, 0.005);
      if (bomb.ticksAlive % 2 == 0) {
         level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, tail.x, tail.y, tail.z, 1, 0.02, 0.0, 0.02, 0.0);
      }

      // Das Pfeifen steigt mit der Fallgeschwindigkeit – das klassische Bombensignal.
      if (bomb.ticksAlive % 4 == 0) {
         float pitch = 0.7F + 1.1F * (float) Math.min(1.0, -bomb.velocity.y / BOMB_TERMINAL_SPEED);
         level.playSound(null, bomb.position.x, bomb.position.y, bomb.position.z,
            SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 0.5F, pitch);
      }

      // Jeden Tick wäre der Ring pure Netzlast: die Partikel leben rund eine Sekunde, und bei
      // bis zu drei Bomben gleichzeitig summierte sich das auf ein Vielfaches des Nötigen.
      if (bomb.ticksAlive % MARKER_INTERVAL_TICKS == 0) {
         drawImpactMarker(level, bomb);
      }
   }

   /** Der Warnring: weit und blass beim Ausklinken, eng und dicht kurz vor dem Einschlag. */
   private void drawImpactMarker(ServerLevel level, FallingBomb bomb) {
      double height = Math.max(0.0, bomb.position.y - bomb.markerY);
      double closeness = 1.0 - Math.min(1.0, height / FLIGHT_HEIGHT);
      double radius = BOMB_RADIUS * (1.0 - 0.62 * closeness);
      // Der Einschlag liegt dort, wo die Bombe bei gleichbleibender Bahn ankommt.
      double remaining = estimateFallTime(height);
      double centreX = bomb.position.x + bomb.velocity.x * remaining;
      double centreZ = bomb.position.z + bomb.velocity.z * remaining;

      DustParticleOptions dust = new DustParticleOptions(MARKER_COLOUR, MARKER_SCALE);
      for (int point = 0; point < MARKER_POINTS; point++) {
         double angle = point * (Math.PI * 2.0) / MARKER_POINTS;
         level.sendParticles(dust,
            centreX + Math.cos(angle) * radius, bomb.markerY + 0.1, centreZ + Math.sin(angle) * radius,
            1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   /**
    * Wirkung einer einzelnen Ladung: Krater, Wucht und Rückstoß, aber kein Sofort-Kill.
    * <p>
    * Getroffene sammeln Treffer auf ein Konto; erst der dritte innerhalb von acht Sekunden
    * eliminiert. Der Getroffene sieht seinen Stand, damit die Gefahr lesbar bleibt.
    */
   private void detonate(MinecraftServer server, ServerLevel level, Arena arena, FallingBomb bomb, Vec3 centre) {
      spawnBlast(level, centre);

      ArenaDemolition.INSTANCE.detonate(level, arena, centre,
         CRATER_RADIUS, CRATER_DEPTH_OFFSET, CRATER_RESTORE_DELAY_TICKS, server.getTickCount());
      shakeScreens(server, arena, centre);

      Deployables.INSTANCE.destroyInRadius(level, centre, BOMB_RADIUS);

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Bomber activeBomber = !bombers.isEmpty() ? bombers.getFirst() : null;
      if (activeBomber != null && worlds != null) {
         ServerPlayer tgt = server.getPlayerList().getPlayer(activeBomber.target);
         if (tgt != null) {
            broadcastCamera(server, worlds, arena, activeBomber, tgt, false, true, centre.x, centre.y, centre.z, tgt.isDeadOrDying() || tgt.isSpectator());
         }
      }

      ServerPlayer attacker = server.getPlayerList().getPlayer(bomb.owner);
      if (attacker == null) {
         return;
      }

      int now = server.getTickCount();
      bombHits.entrySet().removeIf(entry -> now - entry.getValue().lastHitTick > BOMB_HIT_MEMORY_TICKS);

      for (ServerPlayer victim : List.copyOf(server.getPlayerList().getPlayers())) {
         if (worlds == null || worlds.arenaOf(victim) != arena
            || victim.position().distanceToSqr(centre) > BOMB_RADIUS * BOMB_RADIUS) {
            continue;
         }

         Vec3 push = victim.position().subtract(centre);
         Vec3 impulse = (push.lengthSqr() < 0.01 ? new Vec3(0.0, 1.0, 0.0) : push.normalize()).scale(0.7);
         victim.setDeltaMovement(victim.getDeltaMovement().add(impulse.x, 0.45, impulse.z));
         victim.hurtMarked = true;

         BombAccount account = bombHits.computeIfAbsent(victim.getUUID(), ignored -> new BombAccount());
         account.hits++;
         account.lastHitTick = now;
         if (account.hits >= BOMB_HITS_TO_KILL) {
            bombHits.remove(victim.getUUID());
            DamageListener.INSTANCE.eliminate(attacker, victim, arena, KillFeed.Cause.STEALTH_BOMBER);
            if (activeBomber != null) {
               activeBomber.kills++;
               if (activeBomber.kills >= MAX_KILLS_PER_RUN) {
                  activeBomber.ticksLeft = 0;
                  Feedback.actionBar(attacker, "§6🐉 Bomber hat sein Kill-Limit (" + MAX_KILLS_PER_RUN + "/" + MAX_KILLS_PER_RUN + ") erreicht und dreht ab");
               }
               if (victim.getUUID().equals(activeBomber.target) && worlds != null) {
                  broadcastCamera(server, worlds, arena, activeBomber, victim, false, true, centre.x, centre.y, centre.z, true);
               }
            }
         } else {
            Feedback.actionBar(victim, "§c🐉 BOMBENTREFFER " + account.hits + "/" + BOMB_HITS_TO_KILL);
         }
      }
   }

   /**
    * Der sichtbare und hörbare Einschlag.
    * <p>
    * Drei Schichten, weil eine einzelne Partikelwolke aus der Nähe nur als Nebel ankommt: ein
    * kurzer Blitz, der den Bildschirm überstrahlt, der Feuerball darüber, und eine Druckwelle,
    * die flach über den Boden nach außen läuft. Erst die Welle gibt dem Einschlag eine Größe –
    * ohne sie sieht eine kleine Explosion genauso aus wie eine große.
    */
   private void spawnBlast(ServerLevel level, Vec3 centre) {
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.6F, 0.82F);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.1F, 1.5F);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.7F, 1.7F);

      // FLASH ist der Partikel, mit dem Vanilla Feuerwerk aufblitzen lässt – aus der Nähe
      // deckt er kurz das halbe Bild ab. Seit 26.2 trägt er eine eigene Farbe.
      level.sendParticles(BLAST_FLASH, centre.x, centre.y + 0.8, centre.z, 2, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y + 0.6, centre.z, 2, 0.9, 0.4, 0.9, 0.0);
      level.sendParticles(ParticleTypes.EXPLOSION, centre.x, centre.y + 0.8, centre.z, 14, 1.8, 0.9, 1.8, 0.05);
      level.sendParticles(ParticleTypes.FLAME, centre.x, centre.y + 0.4, centre.z, 110, 1.9, 0.9, 1.9, 0.09);
      level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, centre.x, centre.y + 0.3, centre.z, 45, 1.4, 0.5, 1.4, 0.07);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, centre.x, centre.y + 1.2, centre.z, 80, 2.0, 1.4, 2.0, 0.06);
      // Rauchsäule nach oben – von weiter weg das, was den Einschlag überhaupt sichtbar macht.
      level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, centre.x, centre.y + 1.6, centre.z, 18, 0.5, 0.4, 0.5, 0.12);

      for (int point = 0; point < SHOCKWAVE_POINTS; point++) {
         double angle = point * (Math.PI * 2.0) / SHOCKWAVE_POINTS;
         double dirX = Math.cos(angle);
         double dirZ = Math.sin(angle);
         double x = centre.x + dirX * SHOCKWAVE_RADIUS;
         double z = centre.z + dirZ * SHOCKWAVE_RADIUS;
         // Stückzahl null heißt: die Richtung gilt als Geschwindigkeit – die Welle läuft
         // dadurch nach außen, statt an Ort und Stelle zu stehen.
         level.sendParticles(ParticleTypes.POOF, x, centre.y + 0.25, z, 0, dirX, 0.08, dirZ, 0.55);
         level.sendParticles(ParticleTypes.LARGE_SMOKE, x, centre.y + 0.15, z, 0, dirX, 0.05, dirZ, 0.35);
         // Ein kurzer Feuerkranz auf dem Boden – er zeichnet den Wirkungsradius nach.
         if (point % 2 == 0) {
            level.sendParticles(ParticleTypes.FLAME, x, centre.y + 0.1, z, 2, 0.2, 0.05, 0.2, 0.02);
         }
      }

      spawnDebris(level, centre);
   }

   /**
    * Erdklumpen aus dem tatsächlich getroffenen Block.
    * <p>
    * Ein fester Partikeltyp sähe auf jeder Karte gleich aus; der echte Blockzustand bindet den
    * Einschlag an den Boden, auf dem er passiert – Sand stiebt anders als Stein.
    */
   private void spawnDebris(ServerLevel level, Vec3 centre) {
      BlockPos ground = BlockPos.containing(centre.x, centre.y - 0.5, centre.z);
      BlockState state = level.getBlockState(ground);
      if (state.isAir()) {
         return;
      }
      level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
         centre.x, centre.y + 0.3, centre.z, 45, 0.9, 0.5, 0.9, 0.5);
   }

   /** Jeder in der Arena spürt den Einschlag; wie stark, rechnet der Client aus seiner Entfernung. */
   private void shakeScreens(MinecraftServer server, Arena arena, Vec3 centre) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         return;
      }
      ExplosionShakePayload shake = new ExplosionShakePayload(centre.x, centre.y, centre.z,
         SHAKE_RANGE, SHAKE_INTENSITY, SHAKE_TICKS);
      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(listener) == arena) {
            ServerPlayNetworking.send(listener, shake);
         }
      }
   }

   private boolean hasBomberItem(ServerPlayer attacker) {
      for (int slot = 0; slot < attacker.getInventory().getContainerSize(); slot++) {
         if (attacker.getInventory().getItem(slot).is(ModItems.STEALTH_BOMBER)) {
            return true;
         }
      }
      return false;
   }

   private void consumeItem(ServerPlayer attacker) {
      for (int slot = 0; slot < attacker.getInventory().getContainerSize(); slot++) {
         ItemStack stack = attacker.getInventory().getItem(slot);
         if (stack.is(ModItems.STEALTH_BOMBER)) {
            stack.shrink(1);
            attacker.containerMenu.broadcastChanges();
            return;
         }
      }
   }

   public void reset() {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds != null) {
         MinecraftServer server = worlds.getActiveLevel() != null ? worlds.getActiveLevel().getServer() : null;
         if (server != null) {
            clearCamera(server, worlds, worlds.getActive());
         }
      }
      bombers.forEach(bomber -> Hologram.remove(bomber.display));
      bombers.clear();
      bombs.forEach(bomb -> Hologram.remove(bomb.display));
      bombs.clear();
      bombHits.clear();
   }

   private void broadcastCamera(
      MinecraftServer server, ArenaWorlds worlds, Arena arena, Bomber bomber, ServerPlayer target,
      boolean bombDropped, boolean impactGlitch, double blastX, double blastY, double blastZ,
      boolean targetEliminated
   ) {
      if (server == null || worlds == null || arena == null || target == null || bomber == null) {
         return;
      }
      BomberCameraPayload payload = new BomberCameraPayload(
         true,
         target.getUUID(),
         target.getGameProfile().name(),
         bomber.position.x,
         bomber.position.y,
         bomber.position.z,
         target.getX(),
         target.getY(),
         target.getZ(),
         bomber.heading,
         bomber.ticksLeft,
         DURATION_TICKS,
         bombDropped,
         impactGlitch,
         blastX,
         blastY,
         blastZ,
         targetEliminated
      );
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(player) == arena) {
            ServerPlayNetworking.send(player, payload);
         }
      }
   }

   private void clearCamera(MinecraftServer server, ArenaWorlds worlds, Arena arena) {
      if (server == null || worlds == null || arena == null) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(player) == arena) {
            ServerPlayNetworking.send(player, BomberCameraPayload.IDLE);
         }
      }
   }

   /** Eliminierte Spieler verlieren ihr Trefferkonto, sonst zählt der Respawn weiter mit. */
   public void clearFor(ServerPlayer player) {
      bombHits.remove(player.getUUID());
   }

   private static final class Bomber {
      private final UUID owner;
      private final UUID target;
      private int ticksLeft;
      private Vec3 position;
      private Display.ItemDisplay display;
      private float heading;
      private double orbit;
      /** Letzte bekannte Zielposition – daran wird ein Respawn erkannt. */
      private Vec3 lastTargetPosition;
      private int boostTicks;
      private int kills;

      private Bomber(UUID owner, UUID target, int ticksLeft, Vec3 position) {
         this.owner = owner;
         this.target = target;
         this.ticksLeft = ticksLeft;
         this.position = position;
      }
   }

   private static final class FallingBomb {
      private final UUID owner;
      private Vec3 position;
      private Vec3 velocity;
      /** Höhe des Bodens unter dem Ziel – darauf liegt der Warnring. */
      private double markerY;
      private Display.ItemDisplay display;
      private int ticksAlive;

      private FallingBomb(UUID owner, Vec3 position, Vec3 velocity) {
         this.owner = owner;
         this.position = position;
         this.velocity = velocity;
      }
   }

   private static final class BombAccount {
      private int hits;
      private int lastHitTick;
   }
}

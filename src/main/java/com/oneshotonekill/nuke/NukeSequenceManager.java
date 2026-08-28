package com.oneshotonekill.nuke;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;


import com.oneshotonekill.shared.Blast;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.ArenaShape;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.nuke.MushroomCloud;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.registry.ModDamageTypes;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.registry.ModSounds;
import com.oneshotonekill.match.ScoreboardManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Das Matchende als Inszenierung: zehn Sekunden Countdown, ein Einschlag, zwölf Sekunden Nachlauf.
 *
 * <h2>Warum eine eigene Zustandsmaschine</h2>
 *
 * <p>Ein Matchende ist ein Moment, in dem sehr viele Systeme gleichzeitig etwas anderes tun
 * müssen als sonst: Der Match-Timer darf nicht weiterlaufen, Schaden darf nicht mehr zählen,
 * Item-Boxen dürfen nicht mehr erscheinen, und am Ende muss die Karte zurückgesetzt werden. Das
 * über Rückrufe zu verteilen hieße, dieselbe Bedingung an einem Dutzend Stellen zu wiederholen.
 * Stattdessen gibt es hier einen Tickzähler, und alle anderen Systeme fragen
 * {@link #isRunning()} oder {@link #isLocked()}.</p>
 *
 * <p>Der Ablauf steht in {@link NukePhase} und ist eine reine Funktion dieses Zählers – siehe
 * dort, warum. Der Client bekommt denselben Zähler über {@code NukeStatePayload} und leitet
 * daraus Sirene, Countdown, Blitz, Nebel und Abschlusstafel ab. Es gibt damit keinen Zustand,
 * der zwischen Server und Client auseinanderlaufen könnte.</p>
 *
 * <h2>Der Einschlag</h2>
 *
 * <p>Für den Pilz wird {@link MushroomCloud} wiederverwendet – dasselbe Modell, das der
 * Luftangriff wirft. Das ist keine Sparmaßnahme: Ein Spieler soll die Nuke wiedererkennen, und
 * ein zweiter Pilz mit eigener Geometrie sähe nur anders aus, nicht besser. Größer wird sie
 * über den Kopfraum, den sie bekommt, und über eine zweite Welle Wellenpartikel ringsum.</p>
 */
public final class NukeSequenceManager {
   public static final NukeSequenceManager INSTANCE = new NukeSequenceManager();

   /** Kein laufender Ablauf. */
   private static final int IDLE = -1;

   /** Radius der Schockwelle aus Partikeln, in Blöcken. */
   private static final double SHOCKWAVE_RADIUS = 46.0;
   private static final int SHOCKWAVE_RINGS = 5;
   /** Kopfraum des Pilzes über dem Einschlag, wenn die Karte kein Dach hat. */
   private static final double OPEN_SKY_HEADROOM = 96.0;
   /** So weit trägt das Wackeln des Einschlags – die ganze Karte soll es spüren. */
   private static final float SHAKE_REACH = 220.0F;
   /**
    * Schaden des Einschlags.
    *
    * Groß genug für jede denkbare Rüstung und jeden Absorptionswert, aber nicht
    * {@code Float.MAX_VALUE}: Der ginge durch die Abzugsrechnungen von Rüstung und Effekten und
    * käme als {@code NaN} wieder heraus, und ein Spieler mit NaN Lebenspunkten stirbt nicht,
    * sondern verschwindet aus jeder Vergleichslogik.
    */
   private static final float LETHAL_DAMAGE = 10_000.0F;

   // --- Anflug und Abwurf ---
   /**
    * Tick, an dem der Bomber auftaucht.
    *
    * Zusammen mit {@link #BOMBER_SPEED} legt er fest, wie weit draußen er erscheint – hier
    * vierundsechzig Blöcke. Weiter wäre schöner und zugleich falsch: Ein Anzeigekörper in einem
    * nicht geladenen Chunk wird niemandem geschickt, und der Bomber tauchte dann erst mitten
    * über der Karte aus dem Nichts auf.
    */
   private static final int BOMBER_ENTER_TICK = 141;
   /** Tick des Abwurfs – von hier an faellt die Bombe bis zum Einschlag. */
   private static final int BOMB_RELEASE_TICK = 181;
   /** Geschwindigkeit des Bombers in Blöcken je Tick. */
   private static final double BOMBER_SPEED = 1.6;
   /**
    * Flughöhe über dem Einschlagspunkt.
    *
    * Tief genug, dass man ihn erkennt statt ihn zu suchen. Auf einer überdachten Karte wird der
    * Wert zusätzlich unter die Decke gezogen – siehe {@link #bomberAltitude}: Ein Bomber, der
    * über dem Dach fliegt, ist von unten schlicht nicht da.
    */
   private static final double BOMBER_ALTITUDE = 30.0;
   /** Abstand, den er dabei mindestens unter der Decke bleibt. */
   private static final double CEILING_CLEARANCE = 3.0;
   private static final float BOMBER_SCALE = 7.0F;
   private static final float BOMB_SCALE = 4.0F;
   /** Sichtweite der beiden Anzeigekörper – sie sollen von überall auf der Karte zu sehen sein. */
   private static final float FLIGHT_VIEW_RANGE = 12.0F;

   // --- Die Druckwelle ---
   /**
    * Wie viele Spalten die Welle je Tick abträgt.
    *
    * Der Wert bestimmt beides: wie schnell sie über die Karte läuft und wie viel Arbeit der
    * Server dabei je Tick leistet. Eine Arena hat je nach Karte fünfzehn- bis zwanzigtausend
    * Spalten; bei achthundert je Tick ist die ganze Karte nach gut einer Sekunde weg, und der
    * Einschlag liegt genau in dem Moment, in dem der Bildschirm ohnehin weiß ist.
    */
   private static final int WAVE_COLUMNS_PER_TICK = 600;
   /** Erster Radius der Welle – der eigentliche Feuerball. */
   private static final double WAVE_START_RADIUS = 14.0;
   /** Kleinste Schrittweite, damit die Welle auch ganz außen nicht stehen bleibt. */
   private static final double WAVE_MIN_STEP = 1.5;
   /**
    * Wie lange die Karte zerstört bleibt, falls niemand das Match stoppt.
    *
    * Praktisch nie erreicht: Zurückgebaut wird beim Stoppen, und zwar auf einen Schlag – siehe
    * {@code MatchManager#stopMatch}. Der Wert ist nur die Auffanglinie für den Fall, dass ein
    * Match tagelang offen steht.
    */
   private static final int CRATER_RESTORE_DELAY = 20 * 60 * 60;

   /** Wer durch den Einschlag gestorben ist – nur diese kommen am Ende aus dem Zuschauermodus. */
   private final List<UUID> killed = new ArrayList<>();
   private final java.util.Set<UUID> wasVaporised = new java.util.HashSet<>();

   /** Wie weit die Druckwelle schon gelaufen ist; negativ, solange sie ruht. */
   private double waveRadius = -1.0;
   private double waveLimit;

   private Display.ItemDisplay bomber;
   private Display.ItemDisplay bomb;

   private int tick = IDLE;
   private @Nullable UUID winner;
   private String reason = "";
   private Vec3 centre = Vec3.ZERO;
   private int matchSeconds;

   private NukeSequenceManager() {
   }

   // -- Anstoß ---------------------------------------------------------------

   /**
    * Startet die Sequenz. Ein zweiter Aufruf während einer laufenden wird verworfen.
    *
    * @param winner    Der Sieger, oder {@code null} bei Unentschieden.
    * @param winReason Warum das Match endet – Zeitablauf, Kill-Ziel, Abbruch.
    */
   public void triggerSequence(ServerLevel level, @Nullable ServerPlayer winner, @Nullable Component winReason) {
      if (isRunning()) {
         return;
      }
      MinecraftServer server = level.getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();

      this.tick = 0;
      this.winner = winner == null ? null : winner.getUUID();
      this.reason = winReason == null ? "" : winReason.getString();
      this.centre = impactPoint(arena, level);
      this.matchSeconds = MatchManager.INSTANCE.getElapsedTicks() / 20;
      this.killed.clear();

      freeze(server);
      broadcastState(server);

      OneShotOneKill.INSTANCE.getLOGGER().info("Nuke-Sequenz ausgelöst über {} ({})",
         arena == null ? "?" : arena.getId(), this.reason);
   }

   /**
    * Phase 0: alles anhalten, was noch laufen könnte.
    *
    * <p>Die Sperren gegen Schaden und Interaktion liegen nicht hier, sondern in
    * {@code NukeLockEvents} – sie sind Ereignisfilter und keine einmalige Aktion. Hier steht
    * nur, was sich einmal setzen lässt: Unverwundbarkeit, stehende Boxen, geleerter Boden.</p>
    */
   private void freeze(MinecraftServer server) {
      SpecialItemManager.INSTANCE.clearGroundItems();
      OneShotOneKill.clearAbilities(server);

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         player.setInvulnerable(true);
         player.setDeltaMovement(Vec3.ZERO);
         player.hurtMarked = true;
         player.getFoodData().setFoodLevel(20);
         // Die Ansage: genau hier, im ersten Tick der Sequenz. Der ganze Ablauf haengt daran –
         // bei 12,031 Sekunden schlaegt es in der Aufnahme ein, und NukePhase.DETONATION liegt
         // auf Tick 241, also bei 12,050 Sekunden.
         //
         // Serverseitig und an den Spieler geheftet, nicht clientseitig. Vorher startete der
         // Client sie selbst, sobald er einen Tickstand von hoechstens drei sah – und wer
         // dieses schmale Fenster verpasste, weil ein Paket spaeter ankam oder sein Takt
         // gerade anders lag, hoerte gar nichts. Ein Paket, das den Ton mitbringt, kann man
         // nicht verpassen.
         //
         // playOwnSound heftet ihn an die Entity: Die Ansage laeuft zwoelf Sekunden, und in
         // der Zeit bewegt sich jeder. An eine Weltposition gebunden wuerde sie beim
         // Weglaufen leiser – ausgerechnet die Ansage, die allen gleich gilt.
         OsokEffects.INSTANCE.playOwnSound(player, ModSounds.NUKE_INCOMING, 1.0F, 1.0F);
      }
      announce(server, Component.literal("☢ TAKTISCHE NUKE SCHARF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
   }

   // -- Takt -----------------------------------------------------------------

   public void tick(MinecraftServer server) {
      if (!isRunning()) {
         return;
      }
      NukePhase phase = NukePhase.at(tick);
      if (phase == null) {
         finish(server);
         return;
      }
      ServerLevel level = activeLevel();
      if (level == null) {
         // Ohne Arena gibt es nichts zu sprengen; die Sequenz bricht sauber ab.
         finish(server);
         return;
      }

      switch (phase) {
         case FREEZE -> { /* bereits in triggerSequence erledigt */ }
         case COUNTDOWN -> tickCountdown(server, level);
         case DETONATION -> detonate(server, level);
         case AFTERMATH -> tickAftermath(level);
         case VICTORY -> {
            if (phase.progress(tick) == 0) {
               sendVictory(server);
            }
         }
         case CLEANUP -> {
            cleanup(server);
            return;
         }
      }

      tick++;
      // Der Client zählt zwischen den Abgleichen selbst weiter; einmal je Sekunde genügt, um
      // ihn wieder einzufangen, falls ein Paket verloren ging oder jemand neu verbunden ist.
      // An jedem Abschnittswechsel wird zusätzlich sofort abgeglichen: Blitz und Abschlusstafel
      // sollen auf dem Bild sitzen und nicht bis zum nächsten Sekundentakt warten.
      if (tick % 20 == 0 || NukePhase.at(tick) != phase) {
         broadcastState(server);
      }
   }

   /**
    * Der Countdown: jede Sekunde eine Zahl, ein Ton und – zum Schluss – ein Zittern.
    *
    * Das Wackeln setzt erst bei drei Sekunden ein und wächst dann steil. Ein Countdown, der von
    * Anfang an wackelt, hat keine Steigerung mehr; so wird aus dem letzten Drittel ein eigener
    * Abschnitt, den man auch mit geschlossenen Augen erkennt.
    */
   private void tickCountdown(MinecraftServer server, ServerLevel level) {
      int seconds = NukePhase.secondsToImpact(tick);
      boolean fullSecond = (NukePhase.DETONATION.from() - tick) % 20 == 0;

      // Nur noch die letzten drei Sekunden bekommen einen Ton. Die Ansage traegt den Rest, und
      // ein Piepen im Sekundentakt darueber machte aus einer Inszenierung eine Eieruhr.
      if (fullSecond && seconds <= 3) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.NOTE_BLOCK_BELL.value(),
               0.7F, 1.6F + (4 - seconds) * 0.2F);
         }
      }

      // Das Zittern setzt frueher ein und waechst ueber die letzten acht Sekunden auf. Ein
      // Beben, das erst kurz vorher einsetzt, ist eine Ankuendigung; eines, das die ganze Zeit
      // langsam staerker wird, ist eine Drohung.
      if (seconds <= 8 && tick % 2 == 0) {
         float rising = (9 - seconds) / 8.0F;
         shake(server, level, 0.15F + rising * rising * 1.4F, 6);
      }

      // Staub steigt ueber der ganzen Karte auf – erst vereinzelt, zum Schluss flaechendeckend.
      if (seconds <= 9) {
         dustField(level, (10 - seconds) / 9.0F);
      }

      // Kurz vorher reisst der Himmel auf.
      if (seconds <= 3 && tick % 5 == 0) {
         level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.9F, 0.7F),
            centre.x, centre.y + 34.0, centre.z, 3, 30.0, 10.0, 30.0, 0.0);
      }
      // Und in der letzten Sekunde schlaegt der Puls durch: ein Ring, der auf den Einschlag
      // zulaeuft statt von ihm weg.
      if (seconds <= 1) {
         implosionRing(level, (NukePhase.DETONATION.from() - tick) / 20.0);
      }

      tickFlight(server, level);
   }

   /**
    * Aufsteigender Staub ueber der Kampfzone.
    *
    * Verteilt ueber den Grundriss statt um den Einschlag herum: Der Countdown soll ueberall
    * spuerbar sein und nicht nur dort, wo es gleich einschlaegt. Die Stellen werden gewuerfelt
    * und nicht gerastert – ein Gitter aus Staubsaeulen sieht nach Zaun aus.
    */
   private void dustField(ServerLevel level, double intensity) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null) {
         return;
      }
      int columns = (int) (4 + intensity * 16);
      for (int index = 0; index < columns; index++) {
         ArenaShape shape = arena.getRegions().get(level.getRandom().nextInt(arena.getRegions().size()));
         double x = shape.getMinX() + level.getRandom().nextDouble() * (shape.getMaxX() - shape.getMinX());
         double z = shape.getMinZ() + level.getRandom().nextDouble() * (shape.getMaxZ() - shape.getMinZ());
         if (!shape.containsColumn(x, z)) {
            continue;
         }
         double y = shape.getMinY();
         level.sendParticles(ParticleTypes.ASH, x, y + 0.5, z, 3, 0.6, 0.4, 0.6, 0.0);
         if (intensity > 0.5) {
            level.sendParticles(ParticleTypes.SMOKE, x, y + 0.3, z, 2, 0.5, 0.2, 0.5, 0.012);
         }
      }
   }

   /**
    * Der Ring, der in der letzten Sekunde auf den Einschlagspunkt zulaeuft.
    *
    * Nach innen und nicht nach aussen: Die Druckwelle kommt erst nach dem Einschlag. Was man
    * vorher sieht, ist die Luft, die zusammengezogen wird – und ein Ring, der sich schliesst,
    * sagt „gleich", waehrend einer, der sich oeffnet, „vorbei" sagt.
    */
   private void implosionRing(ServerLevel level, double secondsLeft) {
      double radius = Math.max(1.0, 34.0 * secondsLeft);
      int points = 48;
      for (int index = 0; index < points; index++) {
         double angle = index * 2.0 * Math.PI / points;
         double x = centre.x + Math.cos(angle) * radius;
         double z = centre.z + Math.sin(angle) * radius;
         level.sendParticles(ParticleTypes.END_ROD, x, centre.y + 1.4, z, 1, 0.0, 0.4, 0.0, 0.0);
      }
   }

   /**
    * Der Anflug: ein Bomber quert die Karte und wirft über der Mitte ab.
    *
    * <p>Beides sind {@code Display.ItemDisplay} und keine echten Entities – dieselbe Bauweise
    * wie beim Luftangriff. Eine Entity brächte Kollision, Schwerkraft und Netzwerkverkehr mit,
    * und gebraucht wird nichts davon: Der Weg steht von vornherein fest, weil der Einschlag auf
    * den Tick genau sitzen muss.</p>
    *
    * <p>Die Bombe fällt quadratisch statt gleichmäßig. Eine Bombe, die mit gleichbleibendem
    * Tempo herunterkommt, sieht aus, als hinge sie an einem Seil; erst die Beschleunigung macht
    * aus dem Abwurf einen Sturz. Der Endpunkt bleibt dabei derselbe – bei einem Fortschritt von
    * eins steht sie genau im Einschlagspunkt.</p>
    */
   private void tickFlight(MinecraftServer server, ServerLevel level) {
      if (tick == BOMBER_ENTER_TICK) {
         Vec3 start = bomberPosition(BOMBER_ENTER_TICK);
         bomber = Hologram.spawnNaturallyLit(level, start,
            new ItemStack(ModItems.NUKE_BOMBER), FLIGHT_VIEW_RANGE);
         poseFlyer(bomber, new Vec3(1.0, 0.0, 0.0), BOMBER_SCALE);
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.ELYTRA_FLYING, 1.0F, 0.5F);
         }
      }

      if (bomber != null) {
         Vec3 at = bomberPosition(tick);
         Hologram.move(bomber, at);
         if (tick % 3 == 0) {
            // Kondensstreifen hinter den Triebwerken.
            level.sendParticles(ParticleTypes.CLOUD, at.x - 6.0, at.y - 1.0, at.z, 3, 1.2, 0.3, 1.6, 0.0);
         }
      }

      if (tick == BOMB_RELEASE_TICK) {
         Vec3 release = bomberPosition(BOMB_RELEASE_TICK);
         bomb = Hologram.spawnNaturallyLit(level, release,
            new ItemStack(ModItems.NUKE_BOMB), FLIGHT_VIEW_RANGE);
         poseFlyer(bomb, new Vec3(0.0, -1.0, 0.0), BOMB_SCALE);
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.2F, 0.5F);
         }
      }

      if (bomb != null) {
         float share = Math.clamp((tick - BOMB_RELEASE_TICK)
            / (float) (NukePhase.DETONATION.from() - BOMB_RELEASE_TICK), 0.0F, 1.0F);
         double altitude = bomberAltitude();
         Vec3 at = new Vec3(centre.x, centre.y + altitude * (1.0 - share * share), centre.z);
         Hologram.move(bomb, at);
         level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 1.0, at.z, 2, 0.2, 0.2, 0.2, 0.01);
      }
   }

   /** Die Bahn des Bombers: eine Gerade nach Osten, genau über dem Einschlagspunkt. */
   private Vec3 bomberPosition(int atTick) {
      double travelled = (atTick - BOMB_RELEASE_TICK) * BOMBER_SPEED;
      return new Vec3(centre.x + travelled, centre.y + bomberAltitude(), centre.z);
   }

   /**
    * Die Flughöhe über dem Einschlag – unter der Decke, wo es eine gibt.
    *
    * Auf der Standard-Karte liegt die Decke bei 69 und der Boden gut zehn Blöcke darunter; die
    * dreißig Blöcke aus {@link #BOMBER_ALTITUDE} führten den Bomber glatt darüber hinweg, und
    * von unten wäre er unsichtbar geblieben. Bleibt er darunter, fliegt er stattdessen dicht
    * über den Dächern durch – was ohnehin das bessere Bild ist.
    */
   private double bomberAltitude() {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null || !arena.getHasCeiling()) {
         return BOMBER_ALTITUDE;
      }
      double underCeiling = arena.getCeilingY() - CEILING_CLEARANCE - centre.y;
      return Math.max(8.0, Math.min(BOMBER_ALTITUDE, underCeiling));
   }

   /**
    * Richtet einen Flugkörper auf seine Bahn aus.
    *
    * <p>Die Nase der Modelle liegt bei -Y. Gedreht wird auf die <em>gespiegelte</em>
    * Zielrichtung, weil {@code DisplayRenderer.ItemDisplayRenderer#submitInner} vor dem Zeichnen
    * ein {@code Axis.YP.rotation(PI)} auf den Stapel legt und damit X und Z umkehrt. Wer hier
    * die ungespiegelte Richtung einsetzt, bekommt einen Bomber, der rückwärts fliegt – derselbe
    * Fallstrick wie seinerzeit bei der Railgun.</p>
    */
   private static void poseFlyer(Display.ItemDisplay display, Vec3 direction, float scale) {
      if (display == null) {
         return;
      }
      Quaternionf rotation = new Quaternionf().rotationTo(
         0.0F, -1.0F, 0.0F,
         (float) -direction.x, (float) direction.y, (float) -direction.z);
      Hologram.setPose(display, new Vector3f(), rotation, new Vector3f(scale, scale, scale), 0);
   }

   /**
    * Phase 2: der Einschlag.
    *
    * <p>Reihenfolge mit Absicht: erst das Bild und der Ton, dann die Toten. Der Blitz muss auf
    * dem Bildschirm stehen, bevor der Spieler in den Zuschauermodus wechselt – andersherum
    * sähe er den Wechsel und danach erst die Explosion, und der Moment wäre verschenkt.</p>
    */
   private void detonate(MinecraftServer server, ServerLevel level) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      // Der Pilz waechst vom Boden aus, deshalb zaehlt der Platz ueber dem Einschlag – nicht
      // ueber der Kartenoberkante. Auf einer ueberdachten Karte wird er dadurch gestaucht
      // statt durch die Decke zu wachsen.
      double headroom = arena != null && arena.getHasCeiling()
         ? Math.max(8.0, arena.getCeilingY() - centre.y)
         : OPEN_SKY_HEADROOM;

      // Die Bombe hat ihr Ziel erreicht; der Bomber fliegt weiter und verschwindet mit ihr.
      Hologram.remove(bomb);
      Hologram.remove(bomber);
      bomb = null;
      bomber = null;

      MushroomCloud.INSTANCE.detonate(level, centre, headroom);
      shockwave(level);
      shake(server, level, 4.2F, 140);

      // Die Druckwelle setzt sich in Gang; sie frisst sich über die nächsten Ticks nach außen.
      ArenaWorlds waveWorlds = OneShotOneKill.INSTANCE.getArenas();
      Arena waveArena = waveWorlds == null ? null : waveWorlds.getActive();
      if (waveArena != null) {
         waveRadius = 0.0;
         waveLimit = arenaReach(waveArena);
         advanceWave(level, waveArena, WAVE_START_RADIUS);
      }

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.GENERIC_EXPLODE.value(), 4.0F, 0.35F);
         OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.WARDEN_SONIC_BOOM, 3.0F, 0.4F);
         OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0F, 0.5F);
      }

      // Erst jetzt sterben: Der Blitz sitzt schon im selben Tick im Paketstrom davor.
      for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
         vaporise(level, player);
      }
   }

   /**
    * Ein Spieler verdampft.
    *
    * <p>Der Schaden geht durch {@code hurtServer} und damit über den regulären Weg – mit
    * {@code oneshotonekill:nuke_blast} als Quelle, die Rüstung, Effekte und Totems durchdringt
    * (siehe {@link ModDamageTypes}). Die Unverwundbarkeit aus Phase 0 muss dafür kurz weichen:
    * Sie hält das Gefecht an, nicht den Einschlag.</p>
    *
    * <p>Was danach kommt, steht nicht hier, sondern in {@code NukeLockEvents#onDeath} – dort
    * wird der Tod abgefangen und in den Zuschauermodus überführt. Diese Trennung ist keine
    * Umständlichkeit: Sie fängt auch den Spieler ab, der in derselben Sekunde durch etwas
    * anderes stirbt, und hält den Ablauf an der Stelle, an der Vanilla ihn ohnehin meldet.</p>
    */
   private void vaporise(ServerLevel level, ServerPlayer player) {
      if (player.isSpectator()) {
         return;
      }
      if (!player.isAlive()) {
         becomeSpectator(player);
         return;
      }
      killed.add(player.getUUID());
      player.setInvulnerable(false);
      player.hurtServer(level, ModDamageTypes.nukeBlast(level), LETHAL_DAMAGE);
      // Falls nichts den Tod meldet – etwa weil eine andere Mod ihn abfängt –, bleibt der
      // Spieler trotzdem nicht am Leben stehen.
      becomeSpectator(player);
   }

   /**
    * Der Übergang in den Zuschauermodus, an Ort und Stelle.
    *
    * Die Lebenspunkte werden dabei wieder aufgefüllt. Ein Spieler, dessen Tod abgebrochen wurde,
    * stünde sonst mit null Lebenspunkten da und stürbe im nächsten Tick erneut – in einer
    * Schleife, die erst mit dem Ende der Sequenz aufhörte.
    */
   public void becomeSpectator(ServerPlayer player) {
      player.setHealth(player.getMaxHealth());
      player.setInvulnerable(true);
      player.setRemainingFireTicks(0);
      if (player.gameMode() != GameType.SPECTATOR) {
         player.setGameMode(GameType.SPECTATOR);
      }
   }

   /**
    * Phase 3: der Nachlauf.
    *
    * Der Pilz zeichnet sich selbst weiter – {@code MushroomCloud} hängt am Takt des
    * Luftangriffssystems. Hier kommt nur noch der Staub dazu, der über der Karte hängen bleibt.
    */
   private void tickAftermath(ServerLevel level) {
      tickWave(level);
      if (tick % 4 != 0) {
         return;
      }
      double spread = SHOCKWAVE_RADIUS * 0.6;
      level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
         centre.x, centre.y + 6.0, centre.z, 14, spread, 8.0, spread, 0.01);
      level.sendParticles(ParticleTypes.ASH,
         centre.x, centre.y + 10.0, centre.z, 40, spread, 14.0, spread, 0.0);
   }

   /**
    * Ein Tick Druckwelle: der nächste Ring der Karte verschwindet.
    *
    * <p>Die Schrittweite wird aus einem festen Arbeitsbudget gerechnet und nicht aus einer
    * Geschwindigkeit. Ein Ring wird nach außen hin immer länger – bei gleichbleibender
    * Schrittweite wüchse die Arbeit je Tick quadratisch, und der Server bliebe genau dann
    * stehen, wenn die Welle am eindrucksvollsten ist. So läuft sie außen etwas langsamer aus,
    * was ohnehin plausibler aussieht als eine Wand, die mit gleichem Tempo weiterrennt.</p>
    */
   private void tickWave(ServerLevel level) {
      if (waveRadius < 0.0 || waveRadius >= waveLimit) {
         return;
      }
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null) {
         waveRadius = -1.0;
         return;
      }
      double step = Math.max(WAVE_MIN_STEP, WAVE_COLUMNS_PER_TICK / (2.0 * Math.PI * Math.max(8.0, waveRadius)));
      advanceWave(level, arena, step);
   }

   /** Trägt den Ring zwischen dem bisherigen und dem neuen Radius ab und zeichnet seine Front. */
   private void advanceWave(ServerLevel level, Arena arena, double step) {
      double inner = waveRadius;
      double outer = Math.min(waveLimit, waveRadius + step);
      ArenaDemolition.INSTANCE.obliterate(level, arena, centre, inner, outer,
         CRATER_RESTORE_DELAY, level.getServer().getTickCount());
      waveRadius = outer;

      // Die Front der Welle: ein Ring aus Staub und Feuer, der mit ihr nach außen läuft.
      int points = (int) Math.clamp(outer * 1.6, 12.0, 96.0);
      for (int step2 = 0; step2 < points; step2++) {
         double angle = step2 * 2.0 * Math.PI / points;
         double x = centre.x + Math.cos(angle) * outer;
         double z = centre.z + Math.sin(angle) * outer;
         level.sendParticles(ParticleTypes.LARGE_SMOKE, x, centre.y + 2.0, z, 2, 0.6, 2.4, 0.6, 0.04);
         if (step2 % 3 == 0) {
            level.sendParticles(ParticleTypes.FLAME, x, centre.y + 1.0, z, 2, 0.5, 0.8, 0.5, 0.05);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, centre.y + 4.0, z, 1, 0.8, 2.0, 0.8, 0.02);
         }
      }
   }

   /** Wie weit die Welle laufen muss, um weit über die Arenagrenzen hinaus alles zu pulverisieren. */
   private static double arenaReach(Arena arena) {
      double reach = 0.0;
      for (ArenaShape shape : arena.getRegions()) {
         reach = Math.max(reach, Math.hypot(shape.getMaxX() - shape.getMinX(), shape.getMaxZ() - shape.getMinZ()));
      }
      return Math.max(56.0, reach * 0.95 + 28.0);
   }

   /** Die Schockwelle: Ringe, die nach außen laufen, statt einer Kugel aus Rauch. */
   private void shockwave(ServerLevel level) {
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y + 2.0, centre.z, 12, 6.0, 3.0, 6.0, 0.0);
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.98F, 0.9F),
         centre.x, centre.y + 4.0, centre.z, 8, 0.0, 0.0, 0.0, 0.0);

      for (int ring = 1; ring <= SHOCKWAVE_RINGS; ring++) {
         double radius = SHOCKWAVE_RADIUS * ring / SHOCKWAVE_RINGS;
         int points = (int) (radius * 2.2);
         for (int step = 0; step < points; step++) {
            double angle = step * 2.0 * Math.PI / points;
            double x = centre.x + Math.cos(angle) * radius;
            double z = centre.z + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.CLOUD, x, centre.y + 1.2, z, 2, 0.4, 0.6, 0.4, 0.02);
            if (ring % 2 == 0) {
               level.sendParticles(ParticleTypes.LARGE_SMOKE, x, centre.y + 2.4, z, 1, 0.6, 1.2, 0.6, 0.01);
            }
         }
      }
   }

   // -- Abschluss ------------------------------------------------------------

   /**
    * Phase 4: die Zahlen des Matches, fertig aufbereitet an alle.
    *
    * <p>Gesammelt wird über alle verbundenen Spieler, nicht über die Überlebenden – zum
    * Zeitpunkt dieses Aufrufs sind ohnehin alle Zuschauer. Die Rangliste ist nach Kills
    * geordnet, bei Gleichstand entscheidet die geringere Zahl der Tode; ohne dieses zweite
    * Merkmal stünde die Reihenfolge zweier gleichauf liegender Spieler dem Zufall der
    * Spielerliste anheim und wechselte zwischen zwei Matches grundlos.</p>
    */
   private void sendVictory(MinecraftServer server) {
      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      ServerPlayer winnerPlayer = winner == null ? null : server.getPlayerList().getPlayer(winner);
      ScoreboardManager scores = ScoreboardManager.INSTANCE;

      List<ServerPlayer> ranked = new ArrayList<>(players);
      ranked.sort(Comparator
         .comparingInt((ServerPlayer player) -> scores.getKills(player.getUUID())).reversed()
         .thenComparingInt(player -> scores.getDeaths(player.getUUID()))
         .thenComparing(ServerPlayer::getScoreboardName));

      List<NukeVictoryPayload.Row> rows = new ArrayList<>();
      for (ServerPlayer player : ranked.subList(0, Math.min(ranked.size(), NukeVictoryPayload.MAX_ROWS))) {
         rows.add(new NukeVictoryPayload.Row(player.getScoreboardName(),
            scores.getKills(player.getUUID()),
            scores.getDeaths(player.getUUID()),
            scores.getHighestStreak(player.getUUID())));
      }

      ServerPlayer mvp = players.stream()
         .max(Comparator.comparingInt(player -> scores.getHighestStreak(player.getUUID())))
         .orElse(null);
      int mvpStreak = mvp == null ? 0 : scores.getHighestStreak(mvp.getUUID());

      ServerPlayer punchingBag = players.stream()
         .max(Comparator.comparingInt(player -> scores.getDeaths(player.getUUID())))
         .orElse(null);
      int mostDeaths = punchingBag == null ? 0 : scores.getDeaths(punchingBag.getUUID());

      int totalKills = players.stream().mapToInt(player -> scores.getKills(player.getUUID())).sum();
      int totalDeaths = players.stream().mapToInt(player -> scores.getDeaths(player.getUUID())).sum();

      NukeVictoryPayload payload = new NukeVictoryPayload(
         winnerPlayer == null ? "" : winnerPlayer.getScoreboardName(),
         reason,
         matchSeconds,
         totalKills,
         totalDeaths,
         players.size(),
         mvp == null || mvpStreak <= 0 ? "" : mvp.getScoreboardName(),
         mvpStreak,
         punchingBag == null || mostDeaths <= 0 ? "" : punchingBag.getScoreboardName(),
         mostDeaths,
         List.copyOf(rows));
      players.forEach(player -> ServerPlayNetworking.send(player, payload));
   }

   /**
    * Phase 5: die Sequenz endet – das Match auch, aber sonst bleibt alles, wie es ist.
    *
    * <p>Ausdrücklich <em>kein</em> Rückflug in die Lobby und <em>kein</em> Zuwachsen der
    * Krater. Wer die Nuke gesehen hat, soll danach über der verwüsteten Karte schweben dürfen,
    * solange er will; erst der Griff zum Stopp im Hauptmenü holt alle zurück und baut die Karte
    * wieder auf – siehe {@code MatchManager#stopMatch}.</p>
    *
    * <p>Der Zuschauermodus bleibt deshalb ebenfalls stehen. {@link #finish} setzt ihn nur
    * zurück, wenn es von dort gerufen wird.</p>
    */
   private void cleanup(MinecraftServer server) {
      endSequence(server, false, false);
      // Kein leerer Zustand, sondern einer hinter dem letzten Abschnitt: Nebel, Bildschirmfilm
      // und Abschlusstafel hängen daran und bleiben damit stehen. Aufgeräumt wird erst, wenn
      // das Match gestoppt wird – dann geht der leere Zustand über finish() hinaus.
      NukeStatePayload fallout = new NukeStatePayload(NukePhase.TOTAL_TICKS, centre.x, centre.y, centre.z);
      server.getPlayerList().getPlayers().forEach(player -> ServerPlayNetworking.send(player, fallout));
      MatchManager.INSTANCE.markDecided();
   }

   /**
    * Alle Spuren der Sequenz entfernen – auch bei Abbruch.
    *
    * Wird zusätzlich beim Serverstart und beim Match-Stopp aufgerufen. Ein Spieler, der wegen
    * eines Absturzes im Zuschauermodus und unverwundbar zurückbliebe, könnte sonst nicht mehr
    * mitspielen und wüsste nicht einmal, warum.
    */
   public void finish(MinecraftServer server) {
      endSequence(server, true, true);
   }

   /**
    * @param releaseSpectators ob die Verdampften wieder spielen dürfen. Am Ende der Sequenz
    *                          nicht – dann sehen sie weiter zu, bis das Match gestoppt wird.
    * @param clearClients      ob die Clients ihren Zustand wegwerfen sollen. Ebenfalls nicht am
    *                          Ende der Sequenz: Dort bleibt der Fallout stehen.
    */
   private void endSequence(MinecraftServer server, boolean releaseSpectators, boolean clearClients) {
      tick = IDLE;
      winner = null;
      reason = "";
      wasVaporised.addAll(killed);
      killed.clear();
      waveRadius = -1.0;
      Hologram.remove(bomb);
      Hologram.remove(bomber);
      bomb = null;
      bomber = null;
      MushroomCloud.INSTANCE.reset();

      if (server == null) {
         return;
      }
      if (clearClients) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, NukeStatePayload.IDLE);
         }
      }
      if (releaseSpectators) {
         releaseSpectators(server);
      }
   }

   /**
    * Holt alle aus dem Zuschauermodus, die der Einschlag hineingebracht hat.
    *
    * Nur diese: Wer vorher schon zusah, soll das weiter tun dürfen. Aufgerufen wird das beim
    * Stoppen des Matches und beim Serverstart – nicht am Ende der Sequenz.
    */
   public void releaseSpectators(MinecraftServer server) {
      if (server == null) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         player.setInvulnerable(false);
         if (wasVaporised.contains(player.getUUID()) && player.gameMode() == GameType.SPECTATOR) {
            player.setGameMode(GameType.SURVIVAL);
         }
      }
      wasVaporised.clear();
   }

   // -- Zustand für andere Systeme -------------------------------------------

   public boolean isRunning() {
      return tick != IDLE;
   }

   /**
    * Ob gerade jede Spielhandlung gesperrt ist.
    *
    * Vom Anstoß bis zum Einschlag: In dieser Zeit stehen die Punkte fest, und ein Treffer, der
    * noch zählte, entschiede das Match nach seinem Ende. Danach sind ohnehin alle Zuschauer.
    */
   public boolean isLocked() {
      return isRunning();
   }

   public NukePhase phase() {
      return NukePhase.at(tick);
   }

   public int currentTick() {
      return tick;
   }

   // -- Werkzeug -------------------------------------------------------------

   private void broadcastState(MinecraftServer server) {
      NukeStatePayload payload = new NukeStatePayload(tick, centre.x, centre.y, centre.z);
      server.getPlayerList().getPlayers().forEach(player -> ServerPlayNetworking.send(player, payload));
   }

   private void announce(MinecraftServer server, Component message) {
      server.getPlayerList().broadcastSystemMessage(
         Component.literal("[OSOK] ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD).append(message), false);
   }

   private void shake(MinecraftServer server, ServerLevel level, float intensity, int durationTicks) {
      ExplosionShakePayload payload = new ExplosionShakePayload(
         centre.x, centre.y, centre.z, SHAKE_REACH, intensity, durationTicks);
      server.getPlayerList().getPlayers().forEach(player -> ServerPlayNetworking.send(player, payload));
   }

   private @Nullable ServerLevel activeLevel() {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      return worlds == null ? null : worlds.getActiveLevel();
   }

   /**
    * Der Einschlagspunkt: die Mitte der Karte, auf dem Boden.
    *
    * <p>Auf dem Boden und nicht auf Höhe der Oberkante – daran hängt alles Weitere. Der Pilz
    * wächst von seinem Fuß nach oben, der Krater misst seine Tiefe von hier, und die Bombe muss
    * irgendwo aufschlagen. Ein Einschlag in der Luft ergäbe einen Pilz, der über der Karte
    * schwebt, und einen Krater, der nichts trifft.</p>
    *
    * <p>Gesucht wird von der Oberkante der Kampfzone abwärts nach dem ersten festen Block.
    * Findet sich keiner – etwa über einem Loch in der Karte –, bleibt die Unterkante.</p>
    */
   private static Vec3 impactPoint(@Nullable Arena arena, ServerLevel level) {
      Vec3 column = arenaCentre(arena, level);
      if (arena == null) {
         return column;
      }
      int top = (int) Math.ceil(column.y);
      int floor = (int) Math.floor(arena.getRegions().stream()
         .mapToDouble(ArenaShape::getMinY).min().orElse(column.y));
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int y = top; y >= floor; y--) {
         cursor.set((int) Math.floor(column.x), y, (int) Math.floor(column.z));
         if (!level.getBlockState(cursor).isAir()) {
            return new Vec3(column.x, y + 1.0, column.z);
         }
      }
      return new Vec3(column.x, floor, column.z);
   }

   /** Die Mitte der Kampfzone, auf Höhe ihrer Oberkante. */
   private static Vec3 arenaCentre(@Nullable Arena arena, ServerLevel level) {
      if (arena == null || arena.getRegions().isEmpty()) {
         return Vec3.atCenterOf(level.getRespawnData().pos());
      }
      double minX = arena.getRegions().stream().mapToDouble(ArenaShape::getMinX).min().orElseThrow();
      double maxX = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxX).max().orElseThrow();
      double minZ = arena.getRegions().stream().mapToDouble(ArenaShape::getMinZ).min().orElseThrow();
      double maxZ = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxZ).max().orElseThrow();
      double top = arena.getRegions().stream().mapToDouble(ArenaShape::getMaxY).max().orElseThrow();
      return new Vec3((minX + maxX) / 2.0, top, (minZ + maxZ) / 2.0);
   }


   
   /**
    * Die Abschnitte der Nuke-Sequenz, als Fenster auf der Tickachse.
    *
    * <p>Der Ablauf ist bewusst eine reine Funktion des Ticks und kein Zustand, der von Ereignis zu
    * Ereignis weitergereicht wird. Das hat zwei Gründe. Erstens läuft die Sequenz auf Server und
    * Client parallel ab, und ein Client, der mittendrin verbindet, bekommt genau eine Zahl
    * geschickt und weiß damit alles – bei einer Zustandsmaschine mit Übergängen müsste er die
    * verpassten Übergänge nachholen. Zweitens ist ein Ablauf, den man an einer einzigen Tabelle
    * ablesen kann, änderbar: Wer den Countdown verlängern will, ändert hier eine Zahl.</p>
    *
    * <p>Die Grenzen sind halboffen – {@code from} gehört dazu, {@code to} nicht mehr. Damit ist
    * jeder Tick genau einem Abschnitt zugeordnet, auch der Zündtick selbst.</p>
    */
   public enum NukePhase {
      /** Der Anstoß: Sperren setzen, Tonspur starten. Ein einziger Tick. */
      FREEZE(0, 1),
      /** Zwölf Sekunden Countdown – so lang, wie die Tonspur bis zum Einschlag braucht. */
      COUNTDOWN(1, 241),
      /** Der Einschlag. Ebenfalls ein einziger Tick – alles Weitere ist Nachlauf. */
      DETONATION(241, 242),
      /** Blitz blendet ab, die Druckwelle frisst sich über die Karte, Fallout zieht auf. */
      AFTERMATH(242, 361),
      /** Die Abschlusstafel mit Sieger, Werten und Spieldauer. */
      VICTORY(361, 481),
      /** Zustände löschen. Bewegt wird niemand – siehe {@code NukeSequenceManager#cleanup}. */
      CLEANUP(481, 482);
   
      /**
       * Der Zeitpunkt des Einschlags, auf die Tonspur gelegt.
       *
       * <p>{@code endgame/TacticalNukeIncoming.ogg} ist das Original aus Call of Duty, und darin
       * schlägt es bei 12,031 Sekunden ein. Ein Tick dauert 50 Millisekunden, der Einschlag fällt
       * also zwischen zwei Ticks: 240 wären 12,000 s (31 ms zu früh), 241 sind 12,050 s (19 ms zu
       * spät). Genauer geht es mit tickgebundener Ablaufsteuerung nicht, und 241 ist die nähere
       * der beiden Zahlen.</p>
       *
       * <p>Die Tonspur startet in {@link #FREEZE}, also bei Tick 0. Wer an der Länge des
       * Countdowns dreht, verschiebt damit den Einschlag gegen die Musik – diese Zahl und die
       * Tonspur gehören zusammen.</p>
       */
      public static final int IMPACT_MILLIS = 12_031;
   
      /** Der letzte Tick der Sequenz; danach ist sie beendet. */
      public static final int TOTAL_TICKS = 482;
      /** Länge des Countdowns in Sekunden – die Zahl, die auf dem Bildschirm steht. */
      public static final int COUNTDOWN_SECONDS = (DETONATION.from - COUNTDOWN.from + 19) / 20;
   
      private final int from;
      private final int to;
   
      NukePhase(int from, int to) {
         this.from = from;
         this.to = to;
      }
   
      public int from() {
         return from;
      }
   
      public int to() {
         return to;
      }
   
      /** Wie viele Ticks dieser Abschnitt bereits läuft. */
      public int progress(int tick) {
         return tick - from;
      }
   
      /** Der Fortschritt zwischen null und eins – für alles, was ein- oder ausblendet. */
      public float share(int tick) {
         int span = to - from;
         return span <= 1 ? 1.0F : Math.clamp((tick - from) / (float) (span - 1), 0.0F, 1.0F);
      }
   
      /**
       * Der Abschnitt, in dem dieser Tick liegt.
       *
       * @return {@code null}, wenn der Tick vor dem Beginn oder nach dem Ende der Sequenz liegt.
       */
      public static NukePhase at(int tick) {
         if (tick < 0) {
            return null;
         }
         for (NukePhase phase : values()) {
            if (tick >= phase.from && tick < phase.to) {
               return phase;
            }
         }
         return null;
      }
   
      /** Die verbleibenden ganzen Sekunden bis zum Einschlag; null, sobald er da ist. */
      public static int secondsToImpact(int tick) {
         return Math.max(0, (DETONATION.from - tick + 19) / 20);
      }
   }


   public static final class LockEvents {
      private LockEvents() {
      }

      /**
       * Hängt alle Sperren ein.
       *
       * <p>Für das Setzen von Blöcken gibt es in Fabric API kein eigenes Ereignis. Es braucht
       * auch keines: Ein Block kommt nur über einen Rechtsklick auf einen Block oder in die
       * Luft in die Welt, und beide Wege sind hier schon gesperrt.</p>
       */
      public static void register() {
         // Kein Schaden mehr – außer dem des Einschlags. Die Unverwundbarkeit, die
         // NukeSequenceManager zusätzlich setzt, fängt schon das meiste ab. Dieser Rückruf ist
         // die zweite Linie: Er greift auch dort, wo etwas die Unverwundbarkeit umgeht, und er
         // hält den Schaden ab, bevor Rüstung und Effekte gerechnet werden.
         ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
            !NukeSequenceManager.INSTANCE.isLocked()
               || source.is(com.oneshotonekill.registry.ModDamageTypes.NUKE_BLAST));

         ServerLivingEntityEvents.ALLOW_DEATH.register(
            (entity, damageSource, damageAmount) -> allowDeath(entity));

         AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> locked());
         AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> locked());
         UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> locked());
         UseItemCallback.EVENT.register((player, level, hand) -> locked());

         PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
            !NukeSequenceManager.INSTANCE.isLocked());

         // Kein Mob erscheint mehr. Draußen halten und nicht entfernen:
         // PersistentEntitySectionManager#addEntity legt die Entity auch dann ab, wenn man sie
         // verwirft – sie stünde dann gezeichnet, aber ohne Takt in der Welt. Denselben Fehler
         // gab es in WorldRulesManager schon einmal.
         ServerEntityEvents.ALLOW_LOAD.register((entity, level, spawnReason, isLoadedFromDisk) ->
            !NukeSequenceManager.INSTANCE.isLocked() || !(entity instanceof Mob));
      }

      private static InteractionResult locked() {
         return NukeSequenceManager.INSTANCE.isLocked() ? InteractionResult.FAIL : InteractionResult.PASS;
      }

      /**
       * Verhindert das Aufspannen des Bogens während der Sequenz.
       *
       * Fabric API kennt kein Gegenstück zu {@code ArrowNockEvent}; aufgerufen wird das aus
       * {@link com.oneshotonekill.event.InteractionGates} heraus.
       */
      public static boolean blocksBow() {
         return NukeSequenceManager.INSTANCE.isLocked();
      }

      /**
       * Der Tod durch den Einschlag endet im Zuschauermodus – an Ort und Stelle.
       *
       * <p>Warum der Tod verhindert wird, obwohl hier ausdrücklich gestorben werden soll:
       * In den Arenen steht die Spielregel {@code IMMEDIATE_RESPAWN} auf wahr (siehe
       * {@code WorldRulesManager}). Ein zu Ende gelaufener Tod würde den Spieler deshalb sofort
       * an seinem Respawn-Punkt neu erzeugen – als neue Entity, außerhalb der Arena, mit Blick auf
       * die Lobby statt auf den Pilz. Genau das soll nicht passieren.</p>
       *
       * <p>Stattdessen wird der Tod hier angehalten und von Hand zu Ende gebracht: Lebenspunkte
       * zurück, damit der Spieler nicht im nächsten Tick erneut stirbt, und dann in den
       * Zuschauermodus. Er bleibt dabei, wo er war – schwebend über dem Krater, aus dem gerade
       * seine Runde verschwunden ist.</p>
       *
       * <p>{@code CombatEvents} hält sich bei laufender Sequenz heraus; sonst käme dort der
       * übliche Sofort-Respawn samt Ausrüstung dazwischen.</p>
       */
      private static boolean allowDeath(net.minecraft.world.entity.LivingEntity entity) {
         if (!NukeSequenceManager.INSTANCE.isRunning() || !(entity instanceof ServerPlayer player)) {
            return true;
         }
         NukeSequenceManager.INSTANCE.becomeSpectator(player);
         return false;
      }
   }
}

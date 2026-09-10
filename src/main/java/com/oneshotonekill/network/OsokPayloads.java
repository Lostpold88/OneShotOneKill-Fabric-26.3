package com.oneshotonekill.network;

import com.oneshotonekill.movement.ClimbingNetworking;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.StealthBomberSystem;
import com.oneshotonekill.shared.SpecialItemRules;
import com.oneshotonekill.match.MatchManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Meldet alle Pakete bei Fabric an.
 * <p>
 * Die Paketklassen selbst sind reines Vanilla ({@code CustomPacketPayload} mit
 * {@code StreamCodec}) und stehen unverändert weiter unten in dieser Datei. Fabric trennt
 * dagegen dreierlei, was NeoForge in einem Aufruf zusammenfasst:
 * <p>
 * <ol>
 *   <li>Der <em>Typ</em> samt Codec wird je Richtung in {@link PayloadTypeRegistry} eingetragen.
 *       Das muss auf Client und Server gleich laufen, deshalb steht es hier im gemeinsamen
 *       Source-Set und wird vom {@code ModInitializer} aufgerufen.</li>
 *   <li>Die <em>Empfänger</em> für Client → Server hängen ebenfalls hier.</li>
 *   <li>Die Empfänger für Server → Client stehen in
 *       {@code com.oneshotonekill.client.network.OsokClientHandlers}. Sie fassen Bildschirme und
 *       Klangregler an, die es auf einem dedizierten Server nicht gibt.</li>
 * </ol>
 * <p>
 * Eine Protokollversion wie unter NeoForge gibt es nicht. Fabric lehnt eine Verbindung nicht
 * wegen fehlender Kanäle ab; die Pakete dieser Mod sind aber auf beiden Seiten Pflicht, weil
 * Client und Server dieselbe Mod-Fassung laden müssen.
 */
@SuppressWarnings({"NullableProblems", "unused"})
public final class OsokPayloads {
   private OsokPayloads() {
   }

   /** Server → Client: reine Anzeigedaten. */
   private static void registerClientboundTypes() {
      PayloadTypeRegistry<RegistryFriendlyByteBuf> registry = PayloadTypeRegistry.clientboundPlay();
      registry.register(ArenaMenuStatePayload.TYPE, ArenaMenuStatePayload.STREAM_CODEC);
      registry.register(MatchCountdownPayload.TYPE, MatchCountdownPayload.STREAM_CODEC);
      registry.register(MinigunHudPayload.TYPE, MinigunHudPayload.STREAM_CODEC);
      registry.register(AirstrikeSystem.RadarPayload.TYPE, AirstrikeSystem.RadarPayload.STREAM_CODEC);
      registry.register(AirstrikeAlarmPayload.TYPE, AirstrikeAlarmPayload.STREAM_CODEC);
      registry.register(AbilityStatusPayload.TYPE, AbilityStatusPayload.STREAM_CODEC);
      registry.register(TimeDistortionPayload.TYPE, TimeDistortionPayload.STREAM_CODEC);
      registry.register(DeployableMarkersPayload.TYPE, DeployableMarkersPayload.STREAM_CODEC);
      registry.register(GlidingPlayersPayload.TYPE, GlidingPlayersPayload.STREAM_CODEC);
      registry.register(GrapplePullPayload.TYPE, GrapplePullPayload.STREAM_CODEC);
      registry.register(MagnetFieldsPayload.TYPE, MagnetFieldsPayload.STREAM_CODEC);
      registry.register(BomberTargetsPayload.TYPE, BomberTargetsPayload.STREAM_CODEC);
      registry.register(BomberCameraPayload.TYPE, BomberCameraPayload.STREAM_CODEC);
      registry.register(NukeStatePayload.TYPE, NukeStatePayload.STREAM_CODEC);
      registry.register(NukeVictoryPayload.TYPE, NukeVictoryPayload.STREAM_CODEC);
      registry.register(ExplosionShakePayload.TYPE, ExplosionShakePayload.STREAM_CODEC);
      registry.register(MatchNotificationPayload.TYPE, MatchNotificationPayload.STREAM_CODEC);
      registry.register(GunGameStatusPayload.TYPE, GunGameStatusPayload.STREAM_CODEC);
   }

   /** Client → Server: Anweisungen aus den Menüs und von den Items. */
   private static void registerServerboundTypes() {
      PayloadTypeRegistry<RegistryFriendlyByteBuf> registry = PayloadTypeRegistry.serverboundPlay();
      registry.register(RequestArenaMenuPayload.TYPE, RequestArenaMenuPayload.STREAM_CODEC);
      registry.register(SetGameModePayload.TYPE, SetGameModePayload.STREAM_CODEC);
      registry.register(SelectArenaPayload.TYPE, SelectArenaPayload.STREAM_CODEC);
      registry.register(ResetArenaPayload.TYPE, ResetArenaPayload.STREAM_CODEC);
      registry.register(StartMatchPayload.TYPE, StartMatchPayload.STREAM_CODEC);
      registry.register(PauseMatchPayload.TYPE, PauseMatchPayload.STREAM_CODEC);
      registry.register(StopMatchPayload.TYPE, StopMatchPayload.STREAM_CODEC);
      registry.register(GlideBoostPayload.TYPE, GlideBoostPayload.STREAM_CODEC);
      registry.register(RequestRespawnPayload.TYPE, RequestRespawnPayload.STREAM_CODEC);
      registry.register(ClearArrowsPayload.TYPE, ClearArrowsPayload.STREAM_CODEC);
      registry.register(AdjustSpecialItemWeightPayload.TYPE, AdjustSpecialItemWeightPayload.STREAM_CODEC);
      registry.register(SetItemModePayload.TYPE, SetItemModePayload.STREAM_CODEC);
      registry.register(SetMatchTargetPayload.TYPE, SetMatchTargetPayload.STREAM_CODEC);
      registry.register(ResetSpecialItemWeightsPayload.TYPE, ResetSpecialItemWeightsPayload.STREAM_CODEC);
      registry.register(GiveSpecialItemPayload.TYPE, GiveSpecialItemPayload.STREAM_CODEC);
      registry.register(RequestAirstrikePayload.TYPE, RequestAirstrikePayload.STREAM_CODEC);
      registry.register(AirstrikeSystem.OpenRadarPayload.TYPE, AirstrikeSystem.OpenRadarPayload.STREAM_CODEC);
      registry.register(AirstrikeSystem.CloseRadarPayload.TYPE, AirstrikeSystem.CloseRadarPayload.STREAM_CODEC);
      registry.register(SelectBomberTargetPayload.TYPE, SelectBomberTargetPayload.STREAM_CODEC);
      registry.register(DetonateC4Payload.TYPE, DetonateC4Payload.STREAM_CODEC);
   }

   /**
    * Trägt alle Pakettypen ein und hängt die Empfänger der Richtung Client → Server ein.
    * <p>
    * Aufgerufen aus dem {@code ModInitializer}, also auf beiden Seiten. Der Empfänger läuft
    * bereits auf dem Server-Thread: Fabrics {@code AbstractChanneledNetworkAddon} stellt ein
    * Paket, das auf dem Netzwerk-Thread ankäme, mit {@code RunningOnDifferentThreadException}
    * zurück, und Vanilla führt es danach im Takt aus.
    */
   public static void register() {
      ClimbingNetworking.register();
      registerClientboundTypes();
      registerServerboundTypes();

      ServerPlayNetworking.registerGlobalReceiver(RequestArenaMenuPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.requestMenu(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(SetGameModePayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.setGameMode(context.player(), payload.getGameMode()));
      ServerPlayNetworking.registerGlobalReceiver(SelectArenaPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.selectArena(context.player(), payload.getArenaId()));
      ServerPlayNetworking.registerGlobalReceiver(ResetArenaPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.resetArena(context.player(), payload.getArenaId()));
      ServerPlayNetworking.registerGlobalReceiver(StartMatchPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.startMatch(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(PauseMatchPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.pauseMatch(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(StopMatchPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.stopMatch(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(GlideBoostPayload.TYPE,
         (payload, context) -> StatusAbilities.INSTANCE.boostGlide(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(RequestRespawnPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.requestRespawn(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(ClearArrowsPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.clearAllArrows(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(AdjustSpecialItemWeightPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.adjustWeight(context.player(), payload));
      ServerPlayNetworking.registerGlobalReceiver(SetItemModePayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.setItemMode(context.player(), payload.getMode()));
      ServerPlayNetworking.registerGlobalReceiver(SetMatchTargetPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.setMatchTarget(context.player(), payload.getMode(), payload.getValue()));
      ServerPlayNetworking.registerGlobalReceiver(ResetSpecialItemWeightsPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.resetWeights(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(GiveSpecialItemPayload.TYPE,
         (payload, context) -> MatchManager.INSTANCE.giveSpecialItem(context.player(), payload.getItemId()));
      ServerPlayNetworking.registerGlobalReceiver(RequestAirstrikePayload.TYPE,
         (payload, context) -> AirstrikeSystem.INSTANCE.request(context.player(), payload.getTargetX(), payload.getTargetZ()));
      ServerPlayNetworking.registerGlobalReceiver(AirstrikeSystem.OpenRadarPayload.TYPE,
         (payload, context) -> AirstrikeSystem.INSTANCE.openRadar(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(AirstrikeSystem.CloseRadarPayload.TYPE,
         (payload, context) -> AirstrikeSystem.INSTANCE.closeRadar(context.player()));
      ServerPlayNetworking.registerGlobalReceiver(SelectBomberTargetPayload.TYPE,
         (payload, context) -> StealthBomberSystem.INSTANCE.launch(context.player(), payload.target()));
      ServerPlayNetworking.registerGlobalReceiver(DetonateC4Payload.TYPE, (payload, context) -> {
         if (SpecialItemRules.canUseOrExplain(context.player())) {
            Deployables.INSTANCE.detonateAll(context.player());
         }
      });
   }

   // --- AbilityStatusPayload.java ---
   /**
    * Die laufenden Spezial-Item-Wirkungen eines Spielers für sein HUD.
    * <p>
    * Der Client kann keine davon selbst ermitteln – Schild, scharfer Schuss und Ladungszahl sind
    * reine Serverzustände. Gesendet wird nur bei Änderung, nicht in jedem Tick.
    */
   public record AbilityStatusPayload(boolean shield, int vanishTicks, int magnetTicks, int glideTicks, int frozenTicks,
                                      String armedShot, int charges, int traps, int turrets) implements CustomPacketPayload {
       public static final Type<AbilityStatusPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("ability_status"));
       public static final AbilityStatusPayload EMPTY = new AbilityStatusPayload(false, 0, 0, 0, 0, "", 0, 0, 0);
   
       public static final StreamCodec<ByteBuf, AbilityStatusPayload> STREAM_CODEC = StreamCodec.composite(
           ByteBufCodecs.BOOL, AbilityStatusPayload::shield,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::vanishTicks,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::magnetTicks,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::glideTicks,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::frozenTicks,
           ByteBufCodecs.STRING_UTF8, AbilityStatusPayload::armedShot,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::charges,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::traps,
           ByteBufCodecs.VAR_INT, AbilityStatusPayload::turrets,
           AbilityStatusPayload::new);
   
       public boolean isEmpty() {
           return !shield && vanishTicks <= 0 && magnetTicks <= 0 && glideTicks <= 0 && frozenTicks <= 0
               && armedShot.isEmpty() && charges <= 0 && traps <= 0 && turrets <= 0;
       }
   
       @Override public Type<AbilityStatusPayload> type() { return TYPE; }
   }

   // --- DeployableMarkersPayload.java ---
   /**
    * Die eigenen abgestellten Geräte eines Spielers für seine HUD-Peilung.
    * <p>
    * Der Client sieht zwar die Display-Entities, kann aus ihnen aber weder den Besitzer ablesen
    * noch eine getarnte Frost-Falle finden – die ist für Gegner absichtlich unsichtbar. Wer was
    * aufgestellt hat, weiß nur der Server, deshalb kommt die Liste von dort. Sie enthält
    * ausschließlich die Geräte des Empfängers.
    */
   public record DeployableMarkersPayload(List<Marker> markers) implements CustomPacketPayload {
      public static final Type<DeployableMarkersPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("deployable_markers"));
      public static final DeployableMarkersPayload EMPTY = new DeployableMarkersPayload(List.of());

      public static final StreamCodec<ByteBuf, DeployableMarkersPayload> STREAM_CODEC =
         Marker.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(DeployableMarkersPayload::new, DeployableMarkersPayload::markers);

      @Override
      public Type<DeployableMarkersPayload> type() {
         return TYPE;
      }

      /** Was dort steht – die Peilung zeichnet je Art ein eigenes Zeichen. */
      public enum Kind {
         C4,
         TURRET,
         FROST_TRAP;

         private static final Kind[] BY_ID = values();

         /** Unbekannte Kennungen fallen auf C4 zurück, statt die Paketverarbeitung abzubrechen. */
         public static Kind byId(int id) {
            return id >= 0 && id < BY_ID.length ? BY_ID[id] : C4;
         }
      }

      /**
       * Ein abgestelltes Gerät.
       * <p>
       * {@code alerted} meldet den erhöhten Zustand: eine scharfe C4 blinkt schneller, sobald der
       * Zünder in der Hand liegt, und ein Turm, der ein Ziel hat, feuert gerade.
       */
      public record Marker(Kind kind, double x, double y, double z, boolean alerted) {
         public static final StreamCodec<ByteBuf, Marker> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(Kind::byId, Kind::ordinal), Marker::kind,
            ByteBufCodecs.DOUBLE, Marker::x,
            ByteBufCodecs.DOUBLE, Marker::y,
            ByteBufCodecs.DOUBLE, Marker::z,
            ByteBufCodecs.BOOL, Marker::alerted,
            Marker::new);
      }
   }

   // --- AdjustSpecialItemWeightPayload.java ---
   public record AdjustSpecialItemWeightPayload(String itemId, int adjustment) implements CustomPacketPayload {
       public static final Type<AdjustSpecialItemWeightPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("adjust_special_item_weight"));
       public static final StreamCodec<ByteBuf, AdjustSpecialItemWeightPayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, AdjustSpecialItemWeightPayload::itemId, ByteBufCodecs.VAR_INT, AdjustSpecialItemWeightPayload::adjustment, AdjustSpecialItemWeightPayload::new);
       public String getItemId() { return itemId; } public int getAdjustment() { return adjustment; }
       @Override public Type<AdjustSpecialItemWeightPayload> type() { return TYPE; }
   }

   // --- AirstrikeAlarmPayload.java ---
   /**
    * Meldet einen anfliegenden Luftangriff an alle Spieler der Arena. Abwurf- und Zielhöhe reisen mit,
    * damit das HUD die Flugbahn selbst interpolieren kann statt sie tickweise zu übertragen.
    * {@code warningTicks == 0} bedeutet: die Bombe ist eingeschlagen.
    */
   public record AirstrikeAlarmPayload(double targetX, double targetZ, double launchY, double impactY, int warningTicks)
           implements CustomPacketPayload {
       public static final Type<AirstrikeAlarmPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("airstrike_alarm"));
       public static final StreamCodec<ByteBuf, AirstrikeAlarmPayload> STREAM_CODEC = StreamCodec.composite(
           ByteBufCodecs.DOUBLE, AirstrikeAlarmPayload::targetX,
           ByteBufCodecs.DOUBLE, AirstrikeAlarmPayload::targetZ,
           ByteBufCodecs.DOUBLE, AirstrikeAlarmPayload::launchY,
           ByteBufCodecs.DOUBLE, AirstrikeAlarmPayload::impactY,
           ByteBufCodecs.VAR_INT, AirstrikeAlarmPayload::warningTicks,
           AirstrikeAlarmPayload::new);
   
       /** Signalisiert den Einschlag an derselben Stelle. */
       public static AirstrikeAlarmPayload detonated(double targetX, double targetZ, double impactY) {
           return new AirstrikeAlarmPayload(targetX, targetZ, impactY, impactY, 0);
       }
   
       public double getTargetX() { return targetX; }
       public double getTargetZ() { return targetZ; }
       public double getLaunchY() { return launchY; }
       public double getImpactY() { return impactY; }
       public int getWarningTicks() { return warningTicks; }
   
       @Override public Type<AirstrikeAlarmPayload> type() { return TYPE; }
   }

   // --- ArenaMenuStatePayload.java ---
   public record ArenaMenuStatePayload(
       boolean open,
       String activeArenaId,
       String playerArenaId,
       List<String> openArenaIds,
       String resettingArenaId,
       String matchState,
       boolean isOutsideArena,
       String itemMode,
       List<Integer> itemWeights,
       String matchTargetMode,
       int matchTargetValue,
       int remainingTimeTicks,
       String gameMode
   ) implements CustomPacketPayload {
       public static final Type<ArenaMenuStatePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("arena_menu_state"));
       private static final StreamCodec<ByteBuf, List<String>> ID_LIST = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());
       private static final StreamCodec<ByteBuf, List<Integer>> WEIGHT_LIST = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
   
       public static final StreamCodec<ByteBuf, ArenaMenuStatePayload> STREAM_CODEC = new StreamCodec<>() {
          @Override
          public ArenaMenuStatePayload decode(ByteBuf buffer) {
             boolean open = ByteBufCodecs.BOOL.decode(buffer);
             String activeArenaId = ByteBufCodecs.STRING_UTF8.decode(buffer);
             String playerArenaId = ByteBufCodecs.STRING_UTF8.decode(buffer);
             List<String> openArenaIds = ID_LIST.decode(buffer);
             String resettingArenaId = ByteBufCodecs.STRING_UTF8.decode(buffer);
             String matchState = ByteBufCodecs.STRING_UTF8.decode(buffer);
             boolean isOutsideArena = ByteBufCodecs.BOOL.decode(buffer);
             String itemMode = ByteBufCodecs.STRING_UTF8.decode(buffer);
             List<Integer> itemWeights = WEIGHT_LIST.decode(buffer);
             String matchTargetMode = ByteBufCodecs.STRING_UTF8.decode(buffer);
             int matchTargetValue = ByteBufCodecs.VAR_INT.decode(buffer);
             int remainingTimeTicks = ByteBufCodecs.VAR_INT.decode(buffer);
             String gameMode = ByteBufCodecs.STRING_UTF8.decode(buffer);
             return new ArenaMenuStatePayload(open, activeArenaId, playerArenaId, openArenaIds, resettingArenaId,
                matchState, isOutsideArena, itemMode, itemWeights, matchTargetMode, matchTargetValue, remainingTimeTicks, gameMode);
          }

          @Override
          public void encode(ByteBuf buffer, ArenaMenuStatePayload payload) {
             ByteBufCodecs.BOOL.encode(buffer, payload.open);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.activeArenaId);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.playerArenaId);
             ID_LIST.encode(buffer, payload.openArenaIds);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.resettingArenaId);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.matchState);
             ByteBufCodecs.BOOL.encode(buffer, payload.isOutsideArena);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.itemMode);
             WEIGHT_LIST.encode(buffer, payload.itemWeights);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.matchTargetMode);
             ByteBufCodecs.VAR_INT.encode(buffer, payload.matchTargetValue);
             ByteBufCodecs.VAR_INT.encode(buffer, payload.remainingTimeTicks);
             ByteBufCodecs.STRING_UTF8.encode(buffer, payload.gameMode);
          }
       };
   
       public boolean getOpen() { return open; }
       public String getActiveArenaId() { return activeArenaId; }
       public String getPlayerArenaId() { return playerArenaId; }
       public List<String> getOpenArenaIds() { return openArenaIds; }
       public String getResettingArenaId() { return resettingArenaId; }
       public String getMatchState() { return matchState; }
       public boolean getIsOutsideArena() { return isOutsideArena; }
       public String getItemMode() { return itemMode; }
       public List<Integer> getItemWeights() { return itemWeights; }
       public String getMatchTargetMode() { return matchTargetMode; }
       public int getMatchTargetValue() { return matchTargetValue; }
       public int getRemainingTimeTicks() { return remainingTimeTicks; }
       public String getGameMode() { return gameMode; }
   
       @Override
       public Type<ArenaMenuStatePayload> type() { return TYPE; }
   }

   // --- BomberTargetsPayload.java ---
   /**
    * Die Gegner, auf die ein Bomber angesetzt werden kann – vom Server zusammengestellt.
    * <p>
    * Der Client könnte die Liste im Prinzip aus seiner eigenen Welt ablesen, aber nur für Spieler
    * innerhalb seiner Verfolgungsreichweite. Wer weit genug weg steht, fehlte dann im Menü, obwohl
    * er in derselben Arena kämpft. Die Auswahl kommt deshalb vom Server, der alle kennt.
    */
   public record BomberTargetsPayload(List<Target> targets) implements CustomPacketPayload {
      public static final Type<BomberTargetsPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("bomber_targets"));

      public static final StreamCodec<ByteBuf, BomberTargetsPayload> STREAM_CODEC =
         Target.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(BomberTargetsPayload::new, BomberTargetsPayload::targets);

      @Override
      public Type<BomberTargetsPayload> type() {
         return TYPE;
      }

      /** Ein wählbarer Gegner samt allem, was das Menü über ihn anzeigt. */
      public record Target(UUID id, String name, double x, double y, double z, int killstreak) {
         public static final StreamCodec<ByteBuf, Target> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Target::id,
            ByteBufCodecs.STRING_UTF8, Target::name,
            ByteBufCodecs.DOUBLE, Target::x,
            ByteBufCodecs.DOUBLE, Target::y,
            ByteBufCodecs.DOUBLE, Target::z,
            ByteBufCodecs.VAR_INT, Target::killstreak,
            Target::new);
      }
   }

   // --- BomberCameraPayload.java ---
   /**
    * Live-Telemetrie und Kameradaten des Tarnkappenbombers für das PiP-Aufklärungs-HUD.
    */
   public record BomberCameraPayload(
      boolean active,
      UUID targetId,
      String targetName,
      double bomberX,
      double bomberY,
      double bomberZ,
      double targetX,
      double targetY,
      double targetZ,
      float heading,
      int remainingTicks,
      int totalTicks,
      boolean bombDropped,
      boolean impactGlitch,
      double blastX,
      double blastY,
      double blastZ,
      boolean targetEliminated
   ) implements CustomPacketPayload {
      public static final Type<BomberCameraPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("bomber_camera"));
      public static final UUID NIL_UUID = new UUID(0L, 0L);
      public static final BomberCameraPayload IDLE = new BomberCameraPayload(false, NIL_UUID, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0, false);

      public static final StreamCodec<ByteBuf, BomberCameraPayload> STREAM_CODEC = new StreamCodec<>() {
         @Override
         public BomberCameraPayload decode(ByteBuf buffer) {
            boolean active = ByteBufCodecs.BOOL.decode(buffer);
            if (!active) {
               return IDLE;
            }
            return new BomberCameraPayload(
               true,
               UUIDUtil.STREAM_CODEC.decode(buffer),
               ByteBufCodecs.STRING_UTF8.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.FLOAT.decode(buffer),
               ByteBufCodecs.VAR_INT.decode(buffer),
               ByteBufCodecs.VAR_INT.decode(buffer),
               ByteBufCodecs.BOOL.decode(buffer),
               ByteBufCodecs.BOOL.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.BOOL.decode(buffer)
            );
         }

         @Override
         public void encode(ByteBuf buffer, BomberCameraPayload payload) {
            ByteBufCodecs.BOOL.encode(buffer, payload.active);
            if (payload.active) {
               UUIDUtil.STREAM_CODEC.encode(buffer, payload.targetId != null ? payload.targetId : NIL_UUID);
               ByteBufCodecs.STRING_UTF8.encode(buffer, payload.targetName != null ? payload.targetName : "");
               ByteBufCodecs.DOUBLE.encode(buffer, payload.bomberX);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.bomberY);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.bomberZ);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.targetX);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.targetY);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.targetZ);
               ByteBufCodecs.FLOAT.encode(buffer, payload.heading);
               ByteBufCodecs.VAR_INT.encode(buffer, payload.remainingTicks);
               ByteBufCodecs.VAR_INT.encode(buffer, payload.totalTicks);
               ByteBufCodecs.BOOL.encode(buffer, payload.bombDropped);
               ByteBufCodecs.BOOL.encode(buffer, payload.impactGlitch);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.blastX);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.blastY);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.blastZ);
               ByteBufCodecs.BOOL.encode(buffer, payload.targetEliminated);
            }
         }
      };

      @Override
      public Type<BomberCameraPayload> type() {
         return TYPE;
      }
   }

   // --- TimeDistortionPayload.java ---
   /**
    * Exakter Echtzeitzustand des Zeitverzerrers für den großen Client-Effekt.
    * <p>
    * <p>Vanillas Tickratenpaket sagt nur, dass acht TPS gelten. Dieses Paket ergänzt den
    * räumlichen Ursprung der Druckwelle, die verbleibende Echtzeit und ob ein später
    * beigetretener Spieler den Startimpuls noch sehen soll.</p>
    */
   public record TimeDistortionPayload(boolean active, boolean burst, double x, double y,
                                               double z, int remainingMillis)
      implements CustomPacketPayload {
      public static final Type<TimeDistortionPayload> TYPE =
         new Type<>(OneShotOneKill.INSTANCE.id("time_distortion"));
      public static final TimeDistortionPayload STOP =
         new TimeDistortionPayload(false, false, 0.0, 0.0, 0.0, 0);

      public static final StreamCodec<ByteBuf, TimeDistortionPayload> STREAM_CODEC = new StreamCodec<>() {
         @Override
         public TimeDistortionPayload decode(ByteBuf buffer) {
            boolean active = ByteBufCodecs.BOOL.decode(buffer);
            if (!active) {
               return STOP;
            }
            return new TimeDistortionPayload(
               true,
               ByteBufCodecs.BOOL.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.DOUBLE.decode(buffer),
               ByteBufCodecs.VAR_INT.decode(buffer));
         }

         @Override
         public void encode(ByteBuf buffer, TimeDistortionPayload payload) {
            ByteBufCodecs.BOOL.encode(buffer, payload.active);
            if (payload.active) {
               ByteBufCodecs.BOOL.encode(buffer, payload.burst);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.x);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.y);
               ByteBufCodecs.DOUBLE.encode(buffer, payload.z);
               ByteBufCodecs.VAR_INT.encode(buffer, payload.remainingMillis);
            }
         }
      };

      @Override
      public Type<TimeDistortionPayload> type() {
         return TYPE;
      }
   }

   // --- ExplosionShakePayload.java ---
   /**
    * Lässt den Bildschirm bei einer Explosion wackeln.
    * <p>
    * Der Luftangriff bekommt sein Wackeln als Nebenwirkung der Alarmmeldung, die zusätzlich die
    * Einschlagsanzeige im HUD steuert. Für alles andere – Bomben des Tarnkappenbombers zum Beispiel –
    * wäre diese Anzeige falsch, deshalb gibt es hier ein Paket, das nichts weiter tut als
    * erschüttern. Wie stark, entscheidet der Client anhand seiner Entfernung.
    */
   public record ExplosionShakePayload(double x, double y, double z, float maxDistance, float intensity, int durationTicks) implements CustomPacketPayload {
      public static final Type<ExplosionShakePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("explosion_shake"));

      public static final StreamCodec<ByteBuf, ExplosionShakePayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.DOUBLE, ExplosionShakePayload::x,
         ByteBufCodecs.DOUBLE, ExplosionShakePayload::y,
         ByteBufCodecs.DOUBLE, ExplosionShakePayload::z,
         ByteBufCodecs.FLOAT, ExplosionShakePayload::maxDistance,
         ByteBufCodecs.FLOAT, ExplosionShakePayload::intensity,
         ByteBufCodecs.VAR_INT, ExplosionShakePayload::durationTicks,
         ExplosionShakePayload::new);

      public double getX() {
         return x;
      }

      public double getY() {
         return y;
      }

      public double getZ() {
         return z;
      }

      public float getMaxDistance() {
         return maxDistance;
      }

      public float getIntensity() {
         return intensity;
      }

      public int getDurationTicks() {
         return durationTicks;
      }

      @Override
      public Type<ExplosionShakePayload> type() {
         return TYPE;
      }
   }

   // --- GiveSpecialItemPayload.java ---
   public record GiveSpecialItemPayload(String itemId) implements CustomPacketPayload {
       public static final Type<GiveSpecialItemPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("give_special_item"));
       public static final StreamCodec<ByteBuf, GiveSpecialItemPayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GiveSpecialItemPayload::itemId, GiveSpecialItemPayload::new);
       public String getItemId() { return itemId; }
       @Override public Type<GiveSpecialItemPayload> type() { return TYPE; }
   }

   // --- GlideBoostPayload.java ---
   /**
    * Doppelter Druck auf die Sprungtaste: der Gleitflug startet neu.
    * <p>
    * Das Paket ist leer, weil der Server ohnehin alles nachprüft – ob überhaupt ein Flug läuft und
    * ob die Sperre gegen doppelte Auslösung abgelaufen ist. Der Client schickt es nur dann, wenn er
    * den eigenen Spieler in {@code client/state/GlideState} findet; damit bleibt es bei zwei
    * Paketen je Landung statt bei einem für jeden Doppelsprung im Spiel.
    */
   public static final class GlideBoostPayload implements CustomPacketPayload {
      public static final GlideBoostPayload EMPTY = new GlideBoostPayload();
      public static final Type<GlideBoostPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("glide_boost"));
      public static final StreamCodec<ByteBuf, GlideBoostPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private GlideBoostPayload() {
      }
   
      @Override
      public Type<GlideBoostPayload> type() {
         return TYPE;
      }
   }

   // --- GrapplePullPayload.java ---
   /**
    * Vollständiger sichtbarer Zustand eines Grappler-Schusses.
    * <p>
    * <p>Die Hakenposition kommt einmal je Servertick. Der Client interpoliert zwischen zwei
    * Stützstellen und benutzt denselben Punkt für Flugmodell, Seil, Körperneigung und Kamera.
    * {@code active} bleibt deshalb vom Abschuss bis zum vollständigen Einzug gesetzt;
    * {@code pulling} bezeichnet nur die Phase, in der der Spieler zum Anker gezogen wird.</p>
    */
   public record GrapplePullPayload(UUID player, boolean active, boolean pulling, boolean retracting,
                                   byte normalDir,
                                   double hookX, double hookY, double hookZ)
      implements CustomPacketPayload {
      public static final Type<GrapplePullPayload> TYPE = new Type<>(
         OneShotOneKill.INSTANCE.id("grapple_pull"));

      public static final StreamCodec<ByteBuf, GrapplePullPayload> STREAM_CODEC = StreamCodec.composite(
         UUIDUtil.STREAM_CODEC, GrapplePullPayload::player,
         ByteBufCodecs.BOOL, GrapplePullPayload::active,
         ByteBufCodecs.BOOL, GrapplePullPayload::pulling,
         ByteBufCodecs.BOOL, GrapplePullPayload::retracting,
         ByteBufCodecs.BYTE, GrapplePullPayload::normalDir,
         ByteBufCodecs.DOUBLE, GrapplePullPayload::hookX,
         ByteBufCodecs.DOUBLE, GrapplePullPayload::hookY,
         ByteBufCodecs.DOUBLE, GrapplePullPayload::hookZ,
         GrapplePullPayload::new);

      public static GrapplePullPayload inactive(UUID player) {
         return new GrapplePullPayload(player, false, false, false, (byte) -1, 0.0, 0.0, 0.0);
      }

      @Override
      public Type<GrapplePullPayload> type() {
         return TYPE;
      }
   }

   // --- GlidingPlayersPayload.java ---
   /**
    * Alle Spieler, die gerade im Gleitflug sind.
    * <p>
    * Mehr braucht der Client nicht: die Flügel zeichnet er selbst an die interpolierte Position
    * des jeweiligen Spielers. Als Display-Entities hinkten sie bei Tempo sichtbar hinterher, weil
    * jede Entity über eigene Positionspakete läuft und der Client zwischen zwei Ticks
    * interpoliert.
    */
   public record GlidingPlayersPayload(List<UUID> players) implements CustomPacketPayload {
      public static final Type<GlidingPlayersPayload> TYPE = new Type<>(
         OneShotOneKill.INSTANCE.id("gliding_players"));

      public static final StreamCodec<ByteBuf, GlidingPlayersPayload> STREAM_CODEC =
         UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(64))
            .map(GlidingPlayersPayload::new, GlidingPlayersPayload::players);

      public GlidingPlayersPayload {
         players = List.copyOf(players);
      }

      @Override
      public Type<GlidingPlayersPayload> type() {
         return TYPE;
      }
   }

   // --- MagnetFieldsPayload.java ---
   /** Alle Spieler, deren Pfeilmagnet-Schutzfeld gerade aktiv und sichtbar ist. */
   public record MagnetFieldsPayload(List<UUID> players) implements CustomPacketPayload {
      public static final Type<MagnetFieldsPayload> TYPE = new Type<>(
         OneShotOneKill.INSTANCE.id("magnet_fields"));

      public static final StreamCodec<ByteBuf, MagnetFieldsPayload> STREAM_CODEC =
         UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(64))
            .map(MagnetFieldsPayload::new, MagnetFieldsPayload::players);

      public MagnetFieldsPayload {
         players = List.copyOf(players);
      }

      @Override
      public Type<MagnetFieldsPayload> type() {
         return TYPE;
      }
   }

   // --- MatchCountdownPayload.java ---
   /**
    * Der Stand des Start-Countdowns.
    * <p>
    * Übertragen werden Ticks, nicht Sekunden: der Client zählt sie selbst herunter und kann die
    * Anzeige zwischen den Ticks interpolieren. Käme nur einmal je Sekunde eine Zahl, ruckelte jede
    * Bewegung auf dem Bildschirm im Sekundentakt.
    * <p>
    * {@code remainingTicks < 0} bricht ab, {@code isGo} ist der Startschuss.
    */
   public record MatchCountdownPayload(int remainingTicks, boolean isGo, String arenaName, String gameMode) implements CustomPacketPayload {
      public static final Type<MatchCountdownPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("match_countdown"));

      public static final StreamCodec<ByteBuf, MatchCountdownPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.VAR_INT, MatchCountdownPayload::remainingTicks,
         ByteBufCodecs.BOOL, MatchCountdownPayload::isGo,
         ByteBufCodecs.STRING_UTF8, MatchCountdownPayload::arenaName,
         ByteBufCodecs.STRING_UTF8, MatchCountdownPayload::gameMode,
         MatchCountdownPayload::new);

      public static MatchCountdownPayload cancelled() {
         return new MatchCountdownPayload(-1, false, "", "");
      }

      public static MatchCountdownPayload go(String arenaName, String gameMode) {
         return new MatchCountdownPayload(0, true, arenaName, gameMode);
      }

      public static MatchCountdownPayload of(int remainingTicks, String arenaName, String gameMode) {
         return new MatchCountdownPayload(remainingTicks, false, arenaName, gameMode);
      }

      public int getRemainingTicks() {
         return remainingTicks;
      }

      public boolean isGo() {
         return isGo;
      }

      public String getArenaName() {
         return arenaName;
      }

      public String getGameMode() {
         return gameMode;
      }

      @Override
      public Type<MatchCountdownPayload> type() {
         return TYPE;
      }
   }

   // --- MinigunHudPayload.java ---
   /**
    * Meldungen des Servers an das Minigun-HUD.
    * <p>
    * Neben dem Anlass trägt die Meldung eine Zahl. Bei {@link #HIT_CONFIRMED} steht dort, wie oft
    * das aktuelle Ziel schon getroffen wurde – anders könnte das HUD nicht anzeigen, wie viele
    * Treffer noch fehlen, denn die Buchführung darüber liegt auf dem Server.
    */
   public record MinigunHudPayload(String event, int value) implements CustomPacketPayload {
       public static final String STARTED = "started", EXPIRING = "expiring",
           HIT_CONFIRMED = "hit_confirmed", KILL_CONFIRMED = "kill_confirmed";
   
       public static final Type<MinigunHudPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("minigun_hud"));
       public static final StreamCodec<ByteBuf, MinigunHudPayload> STREAM_CODEC = StreamCodec.composite(
           ByteBufCodecs.STRING_UTF8, MinigunHudPayload::event,
           ByteBufCodecs.VAR_INT, MinigunHudPayload::value,
           MinigunHudPayload::new);
   
       public MinigunHudPayload(String event) {
           this(event, 0);
       }
   
       public String getEvent() {
           return event;
       }
   
       public int getValue() {
           return value;
       }
   
       @Override
       public Type<MinigunHudPayload> type() {
           return TYPE;
       }
   }

   // --- NukeStatePayload.java ---
   /**
    * Der Stand der Nuke-Sequenz: ein Tickzähler und der Ort des Einschlags.
    * <p>
    * <p>Es gibt bewusst nur dieses eine Paket statt je eines für Start, Blitz und Ende. Der Client
    * liest aus dem Tick über {@code NukePhase} ab, was gerade zu tun ist – Sirene, Countdown,
    * Blitz, Nebel, Abschlusstafel. Damit kann er nichts verpassen: Wer mitten in der Sequenz
    * verbindet oder ein Paket verliert, bekommt beim nächsten Abgleich denselben Tick und ist
    * sofort synchron. Bei getrennten Ereignispaketen müsste er verpasste Übergänge nachholen, und
    * genau daran gehen solche Abläufe kaputt.</p>
    * <p>
    * <p>Ein Tick von {@code -1} bedeutet: keine Sequenz. Das ist zugleich das Aufräumsignal.</p>
    */
   public record NukeStatePayload(int tick, double x, double y, double z) implements CustomPacketPayload {
      /** Der Zustand „nichts läuft" – zugleich das Signal zum Aufräumen. */
      public static final NukeStatePayload IDLE = new NukeStatePayload(-1, 0.0, 0.0, 0.0);
   
      public static final Type<NukeStatePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("nuke_state"));
   
      public static final StreamCodec<ByteBuf, NukeStatePayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.VAR_INT, NukeStatePayload::tick,
         ByteBufCodecs.DOUBLE, NukeStatePayload::x,
         ByteBufCodecs.DOUBLE, NukeStatePayload::y,
         ByteBufCodecs.DOUBLE, NukeStatePayload::z,
         NukeStatePayload::new);
   
      @Override
      public Type<NukeStatePayload> type() {
         return TYPE;
      }
   }

   // --- NukeVictoryPayload.java ---
   /**
    * Die Zahlen für die Abschlusstafel nach dem Einschlag.
    * <p>
    * <p>Fertig aufbereitet und nicht als Rohdaten: Der Client soll die Tafel zeichnen, nicht die
    * Rangfolge ausrechnen. Wer gewonnen hat, entscheidet ohnehin der Server – ihn das zweimal tun
    * zu lassen, einmal für die Wertung und einmal fürs Bild, wäre die Art von Doppelung, aus der
    * später zwei verschiedene Sieger werden.</p>
    * <p>
    * <p>Die Rangliste kommt als Liste statt als feste Zahl von Feldern. Damit hängt die Länge der
    * Tafel an der Zahl der Mitspieler und nicht an einer Annahme, die bei drei Leuten leere Zeilen
    * malt und bei zwanzig die Hälfte verschweigt.</p>
    * <p>
    * <p>{@code winner} ist leer, wenn das Match unentschieden endete. {@code mvp} ist der Spieler
    * mit der höchsten Serie und darf ein anderer sein als der Sieger – das ist gewollt, denn beides
    * misst etwas anderes.</p>
    */
   public record NukeVictoryPayload(String winner, String reason, int matchSeconds,
                                    int totalKills, int totalDeaths, int playerCount,
                                    String mvp, int mvpStreak,
                                    String mostDeaths, int mostDeathsCount,
                                    List<Row> ranking) implements CustomPacketPayload {
   
      /** Wie viele Plätze die Tafel höchstens zeigt. */
      public static final int MAX_ROWS = 6;
   
      public static final Type<NukeVictoryPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("nuke_victory"));
   
      public static final StreamCodec<ByteBuf, NukeVictoryPayload> STREAM_CODEC = new StreamCodec<>() {
         @Override
         public NukeVictoryPayload decode(ByteBuf buffer) {
            String winner = ByteBufCodecs.STRING_UTF8.decode(buffer);
            String reason = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int matchSeconds = ByteBufCodecs.VAR_INT.decode(buffer);
            int totalKills = ByteBufCodecs.VAR_INT.decode(buffer);
            int totalDeaths = ByteBufCodecs.VAR_INT.decode(buffer);
            int playerCount = ByteBufCodecs.VAR_INT.decode(buffer);
            String mvp = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int mvpStreak = ByteBufCodecs.VAR_INT.decode(buffer);
            String mostDeaths = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int mostDeathsCount = ByteBufCodecs.VAR_INT.decode(buffer);
   
            int rows = ByteBufCodecs.VAR_INT.decode(buffer);
            List<Row> ranking = new ArrayList<>(rows);
            for (int index = 0; index < rows; index++) {
               ranking.add(new Row(
                  ByteBufCodecs.STRING_UTF8.decode(buffer),
                  ByteBufCodecs.VAR_INT.decode(buffer),
                  ByteBufCodecs.VAR_INT.decode(buffer),
                  ByteBufCodecs.VAR_INT.decode(buffer)));
            }
            return new NukeVictoryPayload(winner, reason, matchSeconds, totalKills, totalDeaths,
               playerCount, mvp, mvpStreak, mostDeaths, mostDeathsCount, List.copyOf(ranking));
         }
   
         @Override
         public void encode(ByteBuf buffer, NukeVictoryPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.winner);
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.reason);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.matchSeconds);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.totalKills);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.totalDeaths);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.playerCount);
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.mvp);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.mvpStreak);
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.mostDeaths);
            ByteBufCodecs.VAR_INT.encode(buffer, payload.mostDeathsCount);
   
            ByteBufCodecs.VAR_INT.encode(buffer, payload.ranking.size());
            for (Row row : payload.ranking) {
               ByteBufCodecs.STRING_UTF8.encode(buffer, row.name());
               ByteBufCodecs.VAR_INT.encode(buffer, row.kills());
               ByteBufCodecs.VAR_INT.encode(buffer, row.deaths());
               ByteBufCodecs.VAR_INT.encode(buffer, row.bestStreak());
            }
         }
      };
   
      public boolean isDraw() {
         return winner.isEmpty();
      }
   
      /** Kills je Minute über das ganze Match – der einzige Wert, der sich lohnt auszurechnen. */
      public double killsPerMinute() {
         return matchSeconds <= 0 ? 0.0 : totalKills * 60.0 / matchSeconds;
      }
   
      @Override
      public Type<NukeVictoryPayload> type() {
         return TYPE;
      }
   
      /** Eine Zeile der Rangliste. */
      public record Row(String name, int kills, int deaths, int bestStreak) {
         public String ratio() {
            return String.format(java.util.Locale.ROOT, "%.2f", deaths <= 0 ? (double) kills : kills / (double) deaths);
         }
      }
   }

   // --- PauseMatchPayload.java ---
   public static final class PauseMatchPayload implements CustomPacketPayload {
      public static final PauseMatchPayload EMPTY = new PauseMatchPayload();
      public static final Type<PauseMatchPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("pause_match"));
      public static final StreamCodec<ByteBuf, PauseMatchPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private PauseMatchPayload() {
      }
   
      @Override
      public Type<PauseMatchPayload> type() {
         return TYPE;
      }
   }

   // --- RequestAirstrikePayload.java ---
   public record RequestAirstrikePayload(double targetX, double targetZ) implements CustomPacketPayload {
       public static final Type<RequestAirstrikePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("request_airstrike"));
       public static final StreamCodec<ByteBuf, RequestAirstrikePayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.DOUBLE, RequestAirstrikePayload::targetX, ByteBufCodecs.DOUBLE, RequestAirstrikePayload::targetZ, RequestAirstrikePayload::new);
       public double getTargetX() { return targetX; } public double getTargetZ() { return targetZ; }
       @Override public Type<RequestAirstrikePayload> type() { return TYPE; }
   }

   // --- RequestArenaMenuPayload.java ---
   public static final class RequestArenaMenuPayload implements CustomPacketPayload {
      public static final RequestArenaMenuPayload EMPTY = new RequestArenaMenuPayload();
      public static final Type<RequestArenaMenuPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("request_arena_menu"));
      public static final StreamCodec<ByteBuf, RequestArenaMenuPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private RequestArenaMenuPayload() {
      }
   
      @Override
      public Type<RequestArenaMenuPayload> type() {
         return TYPE;
      }
   }

   // --- RequestRespawnPayload.java ---
   public static final class RequestRespawnPayload implements CustomPacketPayload {
      public static final RequestRespawnPayload EMPTY = new RequestRespawnPayload();
      public static final Type<RequestRespawnPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("request_respawn"));
      public static final StreamCodec<ByteBuf, RequestRespawnPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private RequestRespawnPayload() {
      }
   
      @Override
      public Type<RequestRespawnPayload> type() {
         return TYPE;
      }
   }

   // --- ClearArrowsPayload.java ---
   public static final class ClearArrowsPayload implements CustomPacketPayload {
      public static final ClearArrowsPayload EMPTY = new ClearArrowsPayload();
      public static final Type<ClearArrowsPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("clear_arrows"));
      public static final StreamCodec<ByteBuf, ClearArrowsPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);

      private ClearArrowsPayload() {
      }

      @Override
      public Type<ClearArrowsPayload> type() {
         return TYPE;
      }
   }

   // --- ResetArenaPayload.java ---
   public record ResetArenaPayload(String arenaId) implements CustomPacketPayload {
       public static final Type<ResetArenaPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("reset_arena"));
       public static final StreamCodec<ByteBuf, ResetArenaPayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ResetArenaPayload::arenaId, ResetArenaPayload::new);
       public String getArenaId() { return arenaId; }
       @Override public Type<ResetArenaPayload> type() { return TYPE; }
   }

   // --- ResetSpecialItemWeightsPayload.java ---
   public static final class ResetSpecialItemWeightsPayload implements CustomPacketPayload {
      public static final ResetSpecialItemWeightsPayload EMPTY = new ResetSpecialItemWeightsPayload();
      public static final Type<ResetSpecialItemWeightsPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("reset_special_item_weights"));
      public static final StreamCodec<ByteBuf, ResetSpecialItemWeightsPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private ResetSpecialItemWeightsPayload() {
      }
   
      @Override
      public Type<ResetSpecialItemWeightsPayload> type() {
         return TYPE;
      }
   }

   // --- SelectArenaPayload.java ---
   public record SelectArenaPayload(String arenaId) implements CustomPacketPayload {
       public static final Type<SelectArenaPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("select_arena"));
       public static final StreamCodec<ByteBuf, SelectArenaPayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SelectArenaPayload::arenaId, SelectArenaPayload::new);
       public String getArenaId() { return arenaId; }
       @Override public Type<SelectArenaPayload> type() { return TYPE; }
   }

   // --- SelectBomberTargetPayload.java ---
   /** Der im Bomber-Menü gewählte Gegner. Der Server prüft die Wahl noch einmal komplett nach. */
   public record SelectBomberTargetPayload(UUID target) implements CustomPacketPayload {
      public static final Type<SelectBomberTargetPayload> TYPE =
         new Type<>(OneShotOneKill.INSTANCE.id("select_bomber_target"));

      public static final StreamCodec<ByteBuf, SelectBomberTargetPayload> STREAM_CODEC =
         UUIDUtil.STREAM_CODEC.map(SelectBomberTargetPayload::new, SelectBomberTargetPayload::target);

      @Override
      public Type<SelectBomberTargetPayload> type() {
         return TYPE;
      }
   }

   // --- SetItemModePayload.java ---
   public record SetItemModePayload(String mode) implements CustomPacketPayload {
       public static final Type<SetItemModePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("set_item_mode"));
       public static final StreamCodec<ByteBuf, SetItemModePayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SetItemModePayload::mode, SetItemModePayload::new);
       public String getMode() { return mode; }
       @Override public Type<SetItemModePayload> type() { return TYPE; }
   }

   // --- SetMatchTargetPayload.java ---
   public record SetMatchTargetPayload(String mode, int value) implements CustomPacketPayload {
       public static final Type<SetMatchTargetPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("set_match_target"));
       public static final StreamCodec<ByteBuf, SetMatchTargetPayload> STREAM_CODEC = StreamCodec.composite(
           ByteBufCodecs.STRING_UTF8, SetMatchTargetPayload::mode,
           ByteBufCodecs.VAR_INT, SetMatchTargetPayload::value,
           SetMatchTargetPayload::new);
   
       public String getMode() { return mode; }
       public int getValue() { return value; }
       @Override public Type<SetMatchTargetPayload> type() { return TYPE; }
   }

   // --- StartMatchPayload.java ---
   public static final class StartMatchPayload implements CustomPacketPayload {
      public static final StartMatchPayload EMPTY = new StartMatchPayload();
      public static final Type<StartMatchPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("start_match"));
      public static final StreamCodec<ByteBuf, StartMatchPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private StartMatchPayload() {
      }
   
      @Override
      public Type<StartMatchPayload> type() {
         return TYPE;
      }
   }

   // --- StopMatchPayload.java ---
   public static final class StopMatchPayload implements CustomPacketPayload {
      public static final StopMatchPayload EMPTY = new StopMatchPayload();
      public static final Type<StopMatchPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("stop_match"));
      public static final StreamCodec<ByteBuf, StopMatchPayload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private StopMatchPayload() {
      }
   
      @Override
      public Type<StopMatchPayload> type() {
         return TYPE;
      }
   }

   // --- DetonateC4Payload.java ---
   public static final class DetonateC4Payload implements CustomPacketPayload {
      public static final DetonateC4Payload EMPTY = new DetonateC4Payload();
      public static final Type<DetonateC4Payload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("detonate_c4"));
      public static final StreamCodec<ByteBuf, DetonateC4Payload> STREAM_CODEC = StreamCodec.unit(EMPTY);
   
      private DetonateC4Payload() {
      }
   
      @Override
      public Type<DetonateC4Payload> type() {
         return TYPE;
      }
   }

   // --- MatchNotificationPayload.java ---
   public record MatchNotificationPayload(String getEvent, String getTitle, String getSubtitle, int getDurationTicks, int getAccentColor) implements CustomPacketPayload {
      public static final Type<MatchNotificationPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("match_notification"));
      public static final StreamCodec<ByteBuf, MatchNotificationPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8, MatchNotificationPayload::getEvent,
         ByteBufCodecs.STRING_UTF8, MatchNotificationPayload::getTitle,
         ByteBufCodecs.STRING_UTF8, MatchNotificationPayload::getSubtitle,
         ByteBufCodecs.INT, MatchNotificationPayload::getDurationTicks,
         ByteBufCodecs.INT, MatchNotificationPayload::getAccentColor,
         MatchNotificationPayload::new
      );

      @Override
      public Type<MatchNotificationPayload> type() {
         return TYPE;
      }
   }

   // --- SetGameModePayload.java ---
   public record SetGameModePayload(String gameMode) implements CustomPacketPayload {
      public static final Type<SetGameModePayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("set_game_mode"));
      public static final StreamCodec<ByteBuf, SetGameModePayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8, SetGameModePayload::gameMode,
         SetGameModePayload::new
      );

      public String getGameMode() { return gameMode; }

      @Override
      public Type<SetGameModePayload> type() {
         return TYPE;
      }
   }

   // --- GunGameStatusPayload.java ---
   public record GunGameStatusPayload(
      boolean active,
      int currentTier,
      int totalTiers,
      int tierKills,
      int requiredKills,
      String tierName,
      String colorName,
      boolean isLevelUp
   ) implements CustomPacketPayload {
      public static final Type<GunGameStatusPayload> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("gun_game_status"));
      public static final StreamCodec<ByteBuf, GunGameStatusPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL, GunGameStatusPayload::active,
         ByteBufCodecs.VAR_INT, GunGameStatusPayload::currentTier,
         ByteBufCodecs.VAR_INT, GunGameStatusPayload::totalTiers,
         ByteBufCodecs.VAR_INT, GunGameStatusPayload::tierKills,
         ByteBufCodecs.VAR_INT, GunGameStatusPayload::requiredKills,
         ByteBufCodecs.STRING_UTF8, GunGameStatusPayload::tierName,
         ByteBufCodecs.STRING_UTF8, GunGameStatusPayload::colorName,
         ByteBufCodecs.BOOL, GunGameStatusPayload::isLevelUp,
         GunGameStatusPayload::new);

      public static GunGameStatusPayload inactive() {
         return new GunGameStatusPayload(false, 1, 13, 0, 3, "", "yellow", false);
      }

      @Override
      public Type<GunGameStatusPayload> type() {
         return TYPE;
      }
   }
}

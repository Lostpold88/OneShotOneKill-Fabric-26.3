package com.oneshotonekill.client.network;

import com.oneshotonekill.client.movement.ClientClimbing;
import com.oneshotonekill.client.screen.AirstrikeTargetScreen;
import com.oneshotonekill.client.screen.ArenaMenuScreen;
import com.oneshotonekill.client.screen.BomberTargetScreen;
import com.oneshotonekill.client.sound.MinigunSoundController;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import com.oneshotonekill.client.state.ClientStates.AirstrikeAlarmState;
import com.oneshotonekill.client.state.ClientStates.BomberCameraState;
import com.oneshotonekill.client.state.ClientStates.CameraShakeState;
import com.oneshotonekill.client.state.ClientStates.DeployableMarkerState;
import com.oneshotonekill.client.state.ClientStates.GlideState;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.client.state.ClientStates.GunGameHudState;
import com.oneshotonekill.client.state.ClientStates.MagnetFieldState;
import com.oneshotonekill.client.state.ClientStates.MatchBannerState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.client.state.ClientStates.MinigunHudState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.movement.ClimbingNetworking;
import com.oneshotonekill.network.OsokPayloads.AbilityStatusPayload;
import com.oneshotonekill.network.OsokPayloads.AirstrikeAlarmPayload;
import com.oneshotonekill.network.OsokPayloads.ArenaMenuStatePayload;
import com.oneshotonekill.network.OsokPayloads.BomberCameraPayload;
import com.oneshotonekill.network.OsokPayloads.BomberTargetsPayload;
import com.oneshotonekill.network.OsokPayloads.DeployableMarkersPayload;
import com.oneshotonekill.network.OsokPayloads.ExplosionShakePayload;
import com.oneshotonekill.network.OsokPayloads.GlidingPlayersPayload;
import com.oneshotonekill.network.OsokPayloads.GrapplePullPayload;
import com.oneshotonekill.network.OsokPayloads.GunGameStatusPayload;
import com.oneshotonekill.network.OsokPayloads.MagnetFieldsPayload;
import com.oneshotonekill.network.OsokPayloads.MatchCountdownPayload;
import com.oneshotonekill.network.OsokPayloads.MatchNotificationPayload;
import com.oneshotonekill.network.OsokPayloads.MinigunHudPayload;
import com.oneshotonekill.network.OsokPayloads.NukeStatePayload;
import com.oneshotonekill.network.OsokPayloads.NukeVictoryPayload;
import com.oneshotonekill.network.OsokPayloads.TimeDistortionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Nimmt die Pakete entgegen, die nur den Client betreffen.
 * <p>
 * <p>Fabric trennt Sende- und Empfangsseite: Die Pakettypen stehen im gemeinsamen Source-Set in
 * {@code OsokPayloads}, die Empfänger für Server → Client dagegen hier. Das ist keine bloße
 * Ordnungsfrage – diese Klasse fasst Bildschirme, Klangregler und Anzeigezustände an, die es im
 * Source-Set {@code src/main/java} gar nicht gibt und die auf einem dedizierten Server nie
 * geladen werden.</p>
 * <p>
 * <p>Fabric ruft die Empfänger bereits auf dem Client-Thread auf.</p>
 */
@SuppressWarnings("unused")
public final class OsokClientHandlers {
   private OsokClientHandlers() {
   }

   public static void register() {
      ClientPlayNetworking.registerGlobalReceiver(ClimbingNetworking.Motion.TYPE,
         (payload, context) -> ClientClimbing.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(ArenaMenuStatePayload.TYPE,
         (payload, context) -> ArenaMenuScreen.show(payload, context.client()));
      ClientPlayNetworking.registerGlobalReceiver(MatchCountdownPayload.TYPE,
         (payload, context) -> MatchStartState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(MinigunHudPayload.TYPE,
         (payload, context) -> minigunHud(payload));
      ClientPlayNetworking.registerGlobalReceiver(AirstrikeSystem.RadarPayload.TYPE,
         (payload, context) -> radar(payload));
      ClientPlayNetworking.registerGlobalReceiver(AirstrikeAlarmPayload.TYPE,
         (payload, context) -> AirstrikeAlarmState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(AbilityStatusPayload.TYPE,
         (payload, context) -> AbilityStatusState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(TimeDistortionPayload.TYPE,
         (payload, context) -> TimeDistortionEffects.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(DeployableMarkersPayload.TYPE,
         (payload, context) -> DeployableMarkerState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(GlidingPlayersPayload.TYPE,
         (payload, context) -> GlideState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(GrapplePullPayload.TYPE,
         (payload, context) -> GrapplePullState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(MagnetFieldsPayload.TYPE,
         (payload, context) -> MagnetFieldState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(BomberTargetsPayload.TYPE,
         (payload, context) -> BomberTargetScreen.show(payload, context.client()));
      ClientPlayNetworking.registerGlobalReceiver(BomberCameraPayload.TYPE,
         (payload, context) -> BomberCameraState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(NukeStatePayload.TYPE,
         (payload, context) -> NukeState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(NukeVictoryPayload.TYPE,
         (payload, context) -> NukeState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(ExplosionShakePayload.TYPE,
         (payload, context) -> CameraShakeState.INSTANCE.trigger(payload.getX(), payload.getY(), payload.getZ(),
            payload.getMaxDistance(), payload.getIntensity(), payload.getDurationTicks()));
      ClientPlayNetworking.registerGlobalReceiver(MatchNotificationPayload.TYPE,
         (payload, context) -> MatchBannerState.INSTANCE.handle(payload));
      ClientPlayNetworking.registerGlobalReceiver(GunGameStatusPayload.TYPE,
         (payload, context) -> GunGameHudState.INSTANCE.handle(payload));
   }

   private static void minigunHud(MinigunHudPayload payload) {
      MinigunHudState.INSTANCE.handle(payload);
      if (MinigunHudPayload.EXPIRING.equals(payload.getEvent())) {
         MinigunSoundController.INSTANCE.playExpiryHiss();
      }
   }

   private static void radar(AirstrikeSystem.RadarPayload payload) {
      if (Minecraft.getInstance().gui.screen() instanceof AirstrikeTargetScreen screen) {
         screen.updateRadar(payload);
      }
   }
}

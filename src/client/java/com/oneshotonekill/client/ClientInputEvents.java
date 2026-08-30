package com.oneshotonekill.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.screen.AirstrikeTargetScreen;
import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import com.oneshotonekill.client.state.ClientStates.GlideState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.item.types.WeaponItems.MinigunItem;
import com.oneshotonekill.nuke.NukeSequenceManager.NukePhase;
import com.oneshotonekill.registry.ModDataComponents;
import com.oneshotonekill.registry.ModItems;
import java.util.Set;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Eingabe, Kamera und Waffenanimation auf dem Client.
 * <p>
 * <p>Drei dieser Wege bietet Fabric API als Ereignis an: der Tooltip, der Rechtsklick und der
 * eingehende Systemtext. Sie werden in {@link #register()} angemeldet.</p>
 * <p>
 * <p>Für alles Übrige – Sichtfeld, Kameraabstand, Handanimation, Nebel, Armhaltung und
 * Namensschild – gibt es weder Ereignis noch Access-Widener-Eintrag. Die Rechnung steht
 * trotzdem hier und nicht im Mixin: Jeder der Mixins unter {@code client/mixin} ist damit auf
 * seinen Einstiegspunkt beschränkt und ruft nur eine der folgenden Methoden auf.</p>
 */
@SuppressWarnings({"SameReturnValue", "unused"})
public final class ClientInputEvents {
   /** Kameraabstand während des Countdowns; Vanilla übernimmt die Wandprüfung selbst. */
   private static final float COUNTDOWN_CAMERA_DISTANCE = 3.0F;
   private static final float FOV_FADE = 0.015F;
   /** Ab diesem Tempo weitet der Gleitflug das Sichtfeld, und wie stark. */
   private static final double GLIDE_FOV_FLOOR = 0.70;
   private static final double GLIDE_FOV_PER_SPEED = 0.42;
   private static final double GLIDE_FOV_MAX = 0.34;

   private ClientInputEvents() {
   }

   public static void register() {
      ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> onItemTooltip(stack, lines));
      UseItemCallback.EVENT.register(ClientInputEvents::onUseItem);
      ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !isSuppressed(message));
   }

   /** Zeigt dynamische Tooltips für Spezialitems mit den aktuell konfigurierten Tasten an. */
   private static void onItemTooltip(ItemStack stack, java.util.List<Component> lines) {
      if (stack.is(ModItems.GRAPPLING_HOOK)) {
         int charges = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
         lines.add(Component.translatable("tooltip.oneshotonekill.grappling_hook_charges",
            charges, stack.getMaxDamage()).withStyle(ChatFormatting.AQUA));
         lines.add(Component.translatable("tooltip.oneshotonekill.grappling_hook_use")
            .withStyle(ChatFormatting.GRAY));
         return;
      }
      if (!stack.is(ModItems.C4) && !stack.is(ModItems.C4_CHARGE)) {
         return;
      }
      String keyName = OsokClient.detonateC4KeyName().getString();
      if (stack.has(ModDataComponents.C4_ARMED)) {
         lines.add(Component.translatable("tooltip.oneshotonekill.c4_detonator_armed", keyName)
            .withStyle(ChatFormatting.GOLD));
      } else {
         lines.add(Component.translatable("tooltip.oneshotonekill.c4_place")
            .withStyle(ChatFormatting.GRAY));
         lines.add(Component.translatable("tooltip.oneshotonekill.c4_detonate", keyName)
            .withStyle(ChatFormatting.YELLOW));
      }
   }

   /**
    * Öffnet das Luftangriffs-Radar beim Rechtsklick.
    * <p>
    * Der Rückruf feuert auf beiden Seiten; geöffnet wird nur auf dem Client. {@code PASS} lässt
    * Vanilla anschließend weiterlaufen – das Menü ersetzt die Benutzung nicht, es begleitet sie.
    */
   private static InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                              net.minecraft.world.level.Level level, InteractionHand hand) {
      if (!level.isClientSide() || AbilityStatusState.INSTANCE.isFrozen()) {
         return InteractionResult.PASS;
      }
      ItemStack held = player.getItemInHand(hand);
      if (held.is(ModItems.SLOW_MOTION)) {
         TimeDistortionEffects.INSTANCE.beginUse();
      }
      if (!held.is(ModItems.AIRSTRIKE)) {
         return InteractionResult.PASS;
      }
      Minecraft client = Minecraft.getInstance();
      if (client.gui.screen() == null) {
         client.gui.setScreen(new AirstrikeTargetScreen());
      }
      return InteractionResult.PASS;
   }

   /**
    * Sichtfeld: Startstoß beim Match-Beginn, Vibration der laufenden Minigun, Sog im Gleitflug.
    * <p>
    * Aufgerufen aus {@code AbstractClientPlayerFovMixin}.
    */
   public static float modifyFovModifier(float modifier) {
      Minecraft client = Minecraft.getInstance();
      MatchStartState state = MatchStartState.INSTANCE;
      modifier += TimeDistortionEffects.INSTANCE.fovOffset();

      if (state.isCountdownActive() && client.options.getCameraType() != CameraType.THIRD_PERSON_FRONT) {
         client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
      }

      float boost = state.getFovBoost();
      if (boost > 0.0F) {
         modifier += boost;
         state.setFovBoost(Math.max(0.0F, boost - FOV_FADE));
      }

      LocalPlayer player = client.player;
      if (player != null && client.options.getCameraType().isFirstPerson()
         && player.isUsingItem() && player.getUseItem().is(ModItems.MINIGUN)) {
         float spin = MinigunRuntime.Spin.speed(player.getTicksUsingItem());
         float vibration = (float) Math.sin(player.tickCount * 2.7F) * 0.008F;
         modifier += (0.024F + vibration) * spin;
      }

      // Im Gleitflug zieht das Sichtfeld mit dem Tempo auf. Ohne das fühlt sich ein Sturzflug
      // genauso an wie ein Reiseflug – die Zahlen ändern sich, das Bild nicht. Erst ab dem
      // Reisetempo, damit ein Start am Boden das Bild nicht ruckartig weitet.
      if (player != null && GlideState.INSTANCE.activePlayers().contains(player.getUUID())) {
         double speed = player.getDeltaMovement().length();
         modifier += (float) Math.clamp((speed - GLIDE_FOV_FLOOR) * GLIDE_FOV_PER_SPEED, 0.0, GLIDE_FOV_MAX);
      }

      return modifier;
   }

   /**
    * Rückt die Kamera im Countdown vor das Gesicht.
    * <p>
    * Aufgerufen aus {@code CameraZoomMixin}; die Verdeckungsprüfung gegen Wände macht Vanilla
    * danach selbst.
    */
   public static float modifyDetachedCameraDistance(float cameraDistance) {
      if (MatchStartState.INSTANCE.isCountdownActive()) {
         float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
         return MatchStartState.INSTANCE.getCameraDistance(partial);
      }
      return cameraDistance;
   }

   /**
    * Lässt die Minigun in der Hand rütteln: erst das anlaufende Getriebe, dann der Rückstoß.
    * <p>
    * Gerechnet wird mit der Einsatzdauer samt Zwischenbild-Anteil statt mit {@code tickCount} –
    * sonst steht das Bild zwischen zwei Ticks still und das Rütteln wirkt wie ein Bildfehler.
    * Die Auslenkung bleibt klein, denn sie verschiebt auch die sichtbare Mündung gegenüber der
    * Stelle, an der der Server die Schüsse ansetzt.
    * <p>
    * Aufgerufen aus {@code ItemInHandRendererMixin}, auf einer eigenen Ebene des Pose-Stapels.
    */
   public static void applyMinigunHandShake(ItemStack itemStack, float partialTick, PoseStack poseStack) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null || !itemStack.is(ModItems.MINIGUN) || !player.isUsingItem()) {
         return;
      }

      float useTicks = player.getTicksUsingItem(partialTick);
      float spin = MinigunRuntime.Spin.speed(useTicks);

      if (useTicks >= MinigunItem.WARM_UP_TICKS) {
         // Zwei Pfeile je Tick: der Rückstoß schlägt entsprechend doppelt so schnell.
         float recoil = (float) Math.sin(useTicks * Math.PI * MinigunItem.ARROWS_PER_TICK);
         float shake = (float) Math.sin(useTicks * 6.3);
         poseStack.translate(shake * 0.004F, shake * 0.003F, -0.012F - Math.abs(recoil) * 0.022F);
      } else {
         float rumble = (float) Math.sin(useTicks * 2.4) * 0.004F * spin;
         poseStack.translate(rumble, -rumble * 0.5F, 0.0F);
      }
   }

   /** Schwebende Uhr und kurzer mechanischer Rücklauf beim Aktivieren. */
   public static void applyTimeDistorterHandPose(ItemStack itemStack, PoseStack poseStack) {
      TimeDistortionEffects.INSTANCE.applyHandPose(itemStack, poseStack);
   }

   /**
    * Die Armhaltung für Railgun und Minigun sowie die neutrale Ausgangshaltung des Grapplers.
    * <p>
    * Beide sind schwere Waffen und werden im Anschlag gehalten wie ein gespannter Bogen.
    * Aufgerufen aus {@code AvatarRendererMixin}.
    * <p>
    * @return die Haltung, oder {@code null}, wenn Vanilla entscheiden soll
    */
   public static HumanoidModel.@Nullable ArmPose heavyWeaponArmPose(Avatar avatar, ItemStack itemInHand,
                                                                   InteractionHand hand) {
      if (itemInHand.is(ModItems.GRAPPLING_HOOK)) {
         // Die richtungsabhängige Haltung wird nach Vanillas Animation in HumanoidModelMixin
         // aufgebaut. ITEM ist hier die neutrale Ausgangslage für das weiche Einblenden.
         return HumanoidModel.ArmPose.ITEM;
      }
      if (!itemInHand.is(ModItems.RAILGUN) && !itemInHand.is(ModItems.MINIGUN)) {
         return null;
      }
      if (avatar.getUsedItemHand() == hand && avatar.getUseItemRemainingTicks() > 0) {
         return HumanoidModel.ArmPose.BOW_AND_ARROW;
      }
      return HumanoidModel.ArmPose.ITEM;
   }

   /**
    * Verhindert das Namensschild über unsichtbaren Spielern.
    * <p>
    * Der eigene Spieler bleibt ausgenommen: Sein Schild sieht ohnehin nur er selbst, und zwar
    * nur in der Verfolgeransicht. Aufgerufen aus {@code AvatarRendererMixin}.
    */
   public static boolean hidesNameTag(Avatar avatar) {
      return avatar.isInvisible() && avatar != Minecraft.getInstance().player;
   }

   // --- Chat Filtering (System Presence) ---
   private static final Set<String> SUPPRESSED_KEYS = Set.of(
      "multiplayer.player.joined",
      "multiplayer.player.joined.renamed",
      "multiplayer.player.left");

   /**
    * Verwirft Vanillas eigene Beitritts- und Abschiedszeilen.
    * <p>
    * Der Server schickt stattdessen die Zeile aus {@code PlayerEvents.PresenceMessages}. Vanilla
    * meldet den Beitritt ohne abbrechbares Ereignis direkt aus {@code PlayerList}, deshalb wird
    * die Zeile hier auf dem Client verworfen. Das ist gefahrlos, weil Client und Server dieselbe
    * Mod-Fassung laden müssen.
    */
   private static boolean isSuppressed(Component message) {
      return message.getContents() instanceof TranslatableContents translatable
         && SUPPRESSED_KEYS.contains(translatable.getKey());
   }

   // --- Nuke Fallout Fog Effects ---
   private static final float FOG_RISE_TICKS = 40.0F;
   private static final float FOG_NEAR = 1.5F;
   private static final float FOG_FAR = 24.0F;
   private static final float FOG_RED = 0.42F;
   private static final float FOG_GREEN = 0.40F;
   private static final float FOG_BLUE = 0.37F;

   /**
    * Legt den Fallout-Schleier über die Welt.
    * <p>
    * Aufgerufen aus {@code FogRendererMixin}. Farbe und Reichweite laufen in 26.2 in einem
    * einzigen {@link FogData} zusammen; unter NeoForge waren das zwei getrennte Ereignisse.
    */
   public static void applyNukeFog(FogData fog) {
      float strength = nukeFogStrength();
      if (strength <= 0.0F) {
         return;
      }
      fog.environmentalStart = Mth.lerp(strength, fog.environmentalStart, FOG_NEAR);
      fog.environmentalEnd = Mth.lerp(strength, fog.environmentalEnd, FOG_FAR);
      fog.color.set(
         Mth.lerp(strength, fog.color.x(), FOG_RED),
         Mth.lerp(strength, fog.color.y(), FOG_GREEN),
         Mth.lerp(strength, fog.color.z(), FOG_BLUE),
         fog.color.w());
   }

   private static float nukeFogStrength() {
      NukeState state = NukeState.INSTANCE;
      if (!state.isRunning() || !state.hasDetonated()) {
         return 0.0F;
      }
      if (state.isFalloutOnly()) {
         return 1.0F;
      }
      float sinceBlast = state.currentTick() - NukePhase.DETONATION.from();
      return Math.clamp(sinceBlast / FOG_RISE_TICKS, 0.0F, 1.0F);
   }
}

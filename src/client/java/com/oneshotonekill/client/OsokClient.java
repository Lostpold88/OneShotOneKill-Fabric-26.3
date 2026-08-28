package com.oneshotonekill.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.client.hud.CombatHudLayers;
import com.oneshotonekill.client.hud.CombatHudLayers.AbilityStatusLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.AirstrikeAlarmLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.BomberCameraLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.DeployableMarkerLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.ItemBoxLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.MinigunHudLayer;
import com.oneshotonekill.client.hud.CombatHudLayers.RailgunHudLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.GunGameHudLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchBannerLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchCountdownLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchStartOverlayLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeCountdownLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeFlashLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeVictoryLayer;
import com.oneshotonekill.client.model.OsokClientModels;
import com.oneshotonekill.client.network.OsokClientHandlers;
import com.oneshotonekill.client.renderer.ChainLightningItemRenderer;
import com.oneshotonekill.client.renderer.GliderWingRenderer;
import com.oneshotonekill.client.renderer.MagnetShieldRenderer;
import com.oneshotonekill.client.renderer.ReflectorShieldRenderer;
import com.oneshotonekill.client.screen.AdminItemScreen;
import com.oneshotonekill.client.sound.MinigunSoundController;
import com.oneshotonekill.client.sound.NukeSoundController;
import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import com.oneshotonekill.client.state.ClientStates.AirstrikeAlarmState;
import com.oneshotonekill.client.state.ClientStates.BomberCameraState;
import com.oneshotonekill.client.state.ClientStates.CameraShakeState;
import com.oneshotonekill.client.state.ClientStates.GlideState;
import com.oneshotonekill.client.state.ClientStates.GunGameHudState;
import com.oneshotonekill.client.state.ClientStates.MagnetFieldState;
import com.oneshotonekill.client.state.ClientStates.MatchBannerState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.client.state.ClientStates.MinigunHudState;
import com.oneshotonekill.client.state.ClientStates.MinigunSpinState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.network.OsokPayloads.DetonateC4Payload;
import com.oneshotonekill.network.OsokPayloads.GlideBoostPayload;
import com.oneshotonekill.network.OsokPayloads.RequestArenaMenuPayload;
import com.oneshotonekill.registry.ModEntities;
import com.oneshotonekill.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Clientseitiger Einstieg: Tastenbelegung, HUD-Ebenen, Modelle und der Client-Takt.
 *
 * <p>Fabric ruft {@link #onInitializeClient()} nur auf dem Client auf. Der Einstiegspunkt steht
 * in {@code fabric.mod.json} unter {@code client}, und der ganze Zweig {@code src/client/java}
 * wird auf einem dedizierten Server gar nicht erst geladen – die Trennung ist damit schon zur
 * Bauzeit dicht und braucht keine Abfrage der Laufzeitumgebung.</p>
 *
 * <p>Alle vierzehn HUD-Ebenen laufen über {@link HudElementRegistry}; gezeichnet wird derselbe
 * Code wie zuvor. Die drei Modellerweiterungen brauchen keine Fabric-API: Vanilla führt seine
 * Modelltypen, Modell-Bedingungen und Sonderrenderer in offenen {@code ID_MAPPER}-Tabellen, in
 * die sich ein eigener Eintrag unmittelbar einhängen lässt.</p>
 */
public final class OsokClient implements ClientModInitializer {
   public static final KeyMapping.Category OSOK_CATEGORY =
      KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, "main"));
   private static final int DOUBLE_JUMP_WINDOW = 8;

   private static KeyMapping detonateC4Key;
   private static KeyMapping menuKey;
   private static KeyMapping adminMenuKey;
   private static boolean jumpWasDown;
   private static int ticksSinceJump = Integer.MAX_VALUE;

   @Override
   public void onInitializeClient() {
      registerKeys();
      registerHudElements();
      registerModels();
      registerRenderers();

      OsokClientHandlers.register();
      ClientInputEvents.register();

      ClientTickEvents.END_CLIENT_TICK.register(OsokClient::onClientTick);
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
   }

   private static void registerKeys() {
      detonateC4Key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.detonate_c4",
         InputConstants.Type.KEYSYM, InputConstants.KEY_R, OSOK_CATEGORY));
      menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.open_menu",
         InputConstants.Type.KEYSYM, InputConstants.KEY_C, OSOK_CATEGORY));
      adminMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.open_admin_menu",
         InputConstants.Type.KEYSYM, InputConstants.KEY_X, OSOK_CATEGORY));
   }

   /**
    * Alle HUD-Ebenen der Mod.
    *
    * {@code addLast} hängt sie hinter das gesamte Vanilla-HUD – dasselbe Ergebnis wie das
    * frühere Einhängen am Ende von {@code Hud#extractRenderState}.
    */
   private static void registerHudElements() {
      HudElementRegistry.addLast(id("minigun_hud"), new MinigunHudLayer());
      HudElementRegistry.addLast(id("railgun_hud"), new RailgunHudLayer());
      HudElementRegistry.addLast(id("airstrike_alarm"), new AirstrikeAlarmLayer());
      HudElementRegistry.addLast(id("bomber_camera"), new BomberCameraLayer());
      HudElementRegistry.addLast(id("item_box_marker"), new ItemBoxLayer());
      HudElementRegistry.addLast(id("deployable_markers"), new DeployableMarkerLayer());
      HudElementRegistry.addLast(id("ability_status"), new AbilityStatusLayer());
      HudElementRegistry.addLast(id("match_start_overlay"), new MatchStartOverlayLayer());
      HudElementRegistry.addLast(id("match_countdown"), new MatchCountdownLayer());
      HudElementRegistry.addLast(id("match_banner"), new MatchBannerLayer());
      HudElementRegistry.addLast(id("gun_game_hud"), new GunGameHudLayer());
      HudElementRegistry.addLast(id("nuke_countdown"), new NukeCountdownLayer());
      HudElementRegistry.addLast(id("nuke_victory"), new NukeVictoryLayer());
      HudElementRegistry.addLast(id("nuke_flash"), new NukeFlashLayer());

      // Das Vanilla-Fadenkreuz wird ausgeblendet, solange Minigun oder Railgun in der Hand liegen:
      HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
         Minecraft client = Minecraft.getInstance();
         if (client.player != null && client.options.getCameraType().isFirstPerson()) {
            boolean hasHeavyWeapon = client.player.getMainHandItem().is(ModItems.MINIGUN)
               || client.player.getMainHandItem().is(ModItems.RAILGUN)
               || client.player.getOffhandItem().is(ModItems.RAILGUN);
            if (hasHeavyWeapon) {
               return;
            }
         }
         original.extractRenderState(graphics, deltaTracker);
      });
   }

   /**
    * Die drei eigenen Modellbausteine.
    *
    * <p>Ein Item-Modell ist von Haus aus starr, und eine Modell-Bedingung kennt nur, was Vanilla
    * mitbringt. Beides lässt sich ohne Mixin erweitern: {@code ItemModels},
    * {@code ConditionalItemModelProperties} und {@code SpecialModelRenderers} halten je eine
    * öffentliche {@code LateBoundIdMapper}-Tabelle, in die ein eigener Codec unter eigener
    * Kennung eingetragen wird. Genau darauf verweisen die Modell-Definitionen unter
    * {@code assets/oneshotonekill/items/}.</p>
    */
   private static void registerModels() {
      ItemModels.ID_MAPPER.put(id("spinning_rotor"),
         OsokClientModels.SpinningRotorModel.Unbaked.MAP_CODEC);
      ConditionalItemModelProperties.ID_MAPPER.put(id("has_placed_c4"),
         OsokClientModels.HasPlacedC4Property.MAP_CODEC);
      SpecialModelRenderers.ID_MAPPER.put(id("chain_lightning"),
         ChainLightningItemRenderer.Unbaked.MAP_CODEC);
   }

   private static void registerRenderers() {
      // Das besitzerexklusive Display zeichnet sich wie ein gewöhnliches Item-Display.
      EntityRenderers.register(ModEntities.OWNER_VISIBLE_ITEM_DISPLAY, OwnerVisibleDisplayRenderer::new);

      PictureInPictureRendererRegistry.register(ctx -> new CombatHudLayers.BomberCameraPipRenderer(
         ctx.minecraft().getEntityRenderDispatcher(),
         new BlockModelResolver(ctx.minecraft().getModelManager())));

      ReflectorShieldRenderer.register();
      MagnetShieldRenderer.register();
      GliderWingRenderer.register();
   }

   private static void onClientTick(Minecraft client) {
      MinigunHudState.INSTANCE.tick();
      MatchStartState.INSTANCE.tick();
      MatchBannerState.INSTANCE.tick();
      GunGameHudState.INSTANCE.tick();
      MinigunSpinState.INSTANCE.tick(client);
      AirstrikeAlarmState.INSTANCE.tick();
      BomberCameraState.INSTANCE.tick();
      AbilityStatusState.INSTANCE.tick();
      ReflectorShieldRenderer.tick();
      MagnetShieldRenderer.tick();
      GliderWingRenderer.tick();
      CameraShakeState.INSTANCE.tick();
      MinigunSoundController.INSTANCE.tickClient(client);
      NukeState.INSTANCE.tick();
      NukeSoundController.INSTANCE.tick(client);

      if (detonateC4Key != null && detonateC4Key.consumeClick() && client.player != null && client.gui.screen() == null) {
         ClientPlayNetworking.send(DetonateC4Payload.EMPTY);
      }

      if (menuKey != null && menuKey.consumeClick() && client.player != null) {
         ClientPlayNetworking.send(RequestArenaMenuPayload.EMPTY);
      }

      tickGlideBoost(client);

      if (adminMenuKey != null && adminMenuKey.consumeClick() && client.player != null && OneShotOneKill.isAdmin(client.player)) {
         Screen current = client.gui.screen();
         if (current == null) {
            client.gui.setScreen(new AdminItemScreen());
         } else if (current instanceof AdminItemScreen) {
            client.gui.setScreen(null);
         }
      }
   }

   /**
    * Doppelter Druck auf die Sprungtaste startet einen gelandeten Gleitflug neu.
    *
    * Geschickt wird nur, wenn der eigene Spieler gerade in der Liste der Flieger steht – das
    * ist dieselbe Liste, aus der die Tragflächen gezeichnet werden. Ohne diese Bedingung ginge
    * bei jedem Doppelsprung im Spiel ein Paket zum Server, und das sind viele.
    *
    * Gezählt wird der Tastendruck selbst, nicht der Sprung: Wer landet, drückt zweimal, und
    * beide Male soll zählen – auch der erste, bei dem der Spieler noch am Boden klebt.
    */
   private static void tickGlideBoost(Minecraft client) {
      if (ticksSinceJump < Integer.MAX_VALUE) {
         ticksSinceJump++;
      }
      boolean jumpDown = client.options.keyJump.isDown();
      boolean pressed = jumpDown && !jumpWasDown;
      jumpWasDown = jumpDown;
      if (!pressed) {
         return;
      }

      if (ticksSinceJump <= DOUBLE_JUMP_WINDOW && client.player != null
         && GlideState.INSTANCE.activePlayers().contains(client.player.getUUID())) {
         ClientPlayNetworking.send(GlideBoostPayload.EMPTY);
         ticksSinceJump = Integer.MAX_VALUE;
         return;
      }
      ticksSinceJump = 0;
   }

   /** Beim Verlassen des Servers müssen Kamera, Klang und Zustände zurückgesetzt werden. */
   private static void onDisconnect() {
      MatchStartState.INSTANCE.clear();
      MinigunHudState.INSTANCE.clear();
      MinigunSpinState.INSTANCE.clear();
      GunGameHudState.INSTANCE.clear();
      AirstrikeAlarmState.INSTANCE.clear();
      BomberCameraState.INSTANCE.clear();
      AbilityStatusState.INSTANCE.clear();
      ReflectorShieldRenderer.clear();
      MagnetFieldState.INSTANCE.clear();
      MagnetShieldRenderer.clear();
      GliderWingRenderer.clear();
      CameraShakeState.INSTANCE.clear();
      MinigunSoundController.INSTANCE.stopAll();
      NukeState.INSTANCE.clear();
      NukeSoundController.INSTANCE.stopAll();
   }

   public static boolean isDetonateC4Key(KeyEvent event) {
      return detonateC4Key != null && detonateC4Key.matches(event);
   }

   public static Component detonateC4KeyName() {
      return detonateC4Key == null ? Component.literal("?") : detonateC4Key.getTranslatedKeyMessage().copy();
   }

   public static boolean isMenuKey(KeyEvent event) {
      return menuKey != null && menuKey.matches(event);
   }

   public static Component menuKeyName() {
      return menuKey == null ? Component.literal("?") : menuKey.getTranslatedKeyMessage().copy();
   }

   public static boolean isAdminMenuKey(KeyEvent event) {
      return adminMenuKey != null && adminMenuKey.matches(event);
   }

   public static Component adminMenuKeyName() {
      return adminMenuKey == null ? Component.literal("?") : adminMenuKey.getTranslatedKeyMessage().copy();
   }

   private static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, path);
   }

   // --- Owner Visible Display Renderer ---
   public static final class OwnerVisibleDisplayRenderer extends net.minecraft.client.renderer.entity.DisplayRenderer.ItemDisplayRenderer {
      public OwnerVisibleDisplayRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
         super(context);
      }
   }
}

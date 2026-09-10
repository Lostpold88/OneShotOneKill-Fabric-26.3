package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.movement.ClientClimbing;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Contextual hint below the crosshair, using the player's actual key bindings.
 */
@SuppressWarnings({"NullableProblems", "unused"})
public final class MantleHudLayers {
    private MantleHudLayers() {
    }

    public static final class MantleHudLayer implements HudElement {
        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            ClientClimbing mantle = ClientClimbing.INSTANCE;
            if (client.player == null || client.gui.screen() != null
                    || (!mantle.isActive() && !mantle.hasLedge() && !mantle.isWaiting())) return;
            Component label = mantle.isWallClimbing()
                    ? Component.translatable("hud.oneshotonekill.wall_climbing", client.options.keyUp.getTranslatedKeyMessage(),
                            client.options.keyDown.getTranslatedKeyMessage(), client.options.keyLeft.getTranslatedKeyMessage(),
                            client.options.keyRight.getTranslatedKeyMessage(), client.options.keyJump.getTranslatedKeyMessage())
                    : mantle.isActive()
                    ? Component.translatable("hud.oneshotonekill.mantling", client.options.keyShift.getTranslatedKeyMessage())
                    : Component.translatable("hud.oneshotonekill.mantle", client.options.keyUp.getTranslatedKeyMessage(),
                    client.options.keyJump.getTranslatedKeyMessage());
            int width = client.font.width(label);
            int left = (graphics.guiWidth() - width) / 2;
            int top = graphics.guiHeight() / 2 + 35;
            graphics.fill(left - 7, top - 5, left + width + 7, top + 15, 0xB0101822);
            graphics.text(client.font, label, left, top, 0xFFE3F9FF);
            if (mantle.isActive() && !mantle.isWallClimbing()) {
                int filled = Math.round((width + 14) * mantle.progress(deltaTracker.getGameTimeDeltaPartialTick(false)));
                graphics.fill(left - 7, top + 13, left - 7 + filled, top + 15, 0xFF67E8F9);
            }
        }
    }
}

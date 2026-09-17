package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Verhindert das Absenken der Hand beim Einsatz des Grapplers in der ersten Person.
 */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
    /**
     * Vanilla setzt bei jedem erfolgreichen Rechtsklick die Höhe der benutzten Hand auf null.
     * Für den Grappler sähe das wie eine Block-Platzieranimation aus und würde genau den Moment
     * verdecken, in dem der Pömpel das Rohr verlässt.
     */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void osok$keepGrapplingHookRaised(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getItemInHand(hand).is(ModItems.GRAPPLING_HOOK)) {
            ci.cancel();
        }
    }
}

package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.item.runtime.StatusAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wer im Düsengeschirr fliegt, liegt waagerecht - wie in der Elytra.
 * <p>
 * Vanilla leitet die Haltung aus dem Flag {@code isFallFlying} ab, und das lässt sich nicht
 * setzen, ohne dass {@code LivingEntity.travel} die Elytra-Physik darüberlegt. Gesetzt wird deshalb
 * allein die Haltung: Sie liefert die flache Hitbox (0,6 Blöcke) und die tiefe Augenhöhe. Die
 * Darstellung des Körpers zieht der Client in {@code AvatarGlideMixin} nach; die Gegenseite für
 * Clientspieler ist {@code ClientPlayerGlidePoseMixin}.
 */
@Mixin(Player.class)
public abstract class PlayerGlidePoseMixin {
   @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
   private void osok$glidePose(CallbackInfo ci) {
      if ((Object) this instanceof ServerPlayer player && StatusAbilities.INSTANCE.isGlideFlying(player)) {
         player.setPose(Pose.FALL_FLYING);
         ci.cancel();
      }
   }
}

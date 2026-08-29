package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zwei Eingriffe an der Spielerfigur, für die Fabric API nichts anbietet.
 *
 * <p><b>Haltung:</b> NeoForge lässt einen Gegenstand seine Armhaltung über
 * {@code IClientItemExtensions#getArmPose} selbst bestimmen. Fabric API kennt keine solche
 * Erweiterung, und ein Access Widener hilft nicht – {@code AvatarRenderer#getArmPose} ist zwar
 * privat, gebraucht wird aber ein anderer Rückgabewert. Railgun und Minigun sollen beim Einsatz
 * im Anschlag liegen; der Grappler behält denselben ausgestreckten Anschlag vom Abschuss bis
 * zum vollständigen Einzug. Ohne diesen Eingriff hinge die Waffe lose in der Faust.</p>
 *
 * <p><b>Namensschild:</b> ersetzt {@code RenderNameTagEvent.CanRender}. Über einem unsichtbaren
 * Spieler darf kein Name stehen, sonst verrät der Tarnmantel seinen Träger. Der eigene Spieler
 * ist ausgenommen: Sein Schild sieht ohnehin nur er selbst in der Verfolgeransicht.</p>
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
   @Inject(
      method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
      at = @At("HEAD"),
      cancellable = true)
   private static void osok$heavyWeaponPose(Avatar avatar, ItemStack itemInHand, InteractionHand hand,
                                            CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
      HumanoidModel.ArmPose pose = ClientInputEvents.heavyWeaponArmPose(avatar, itemInHand, hand);
      if (pose != null) {
         cir.setReturnValue(pose);
      }
   }

   // Ausgeschriebener Deskriptor: javap zeigt neben der eigentlichen Methode noch die Bruecken
   // shouldShowName(LivingEntity, double) und shouldShowName(Entity, double).
   @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
   private void osok$hideInvisibleName(Avatar entity, double distanceToCameraSq,
                                       CallbackInfoReturnable<Boolean> cir) {
      if (cir.getReturnValueZ() && ClientInputEvents.hidesNameTag(entity)) {
         cir.setReturnValue(false);
      }
   }
}

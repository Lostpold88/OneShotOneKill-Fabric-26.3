package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.renderer.GliderWingRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flugpose im Düsengeschirr: Arme und Beine hängen locker im Fahrtwind, statt wie in Vanillas
 * Elytra-Haltung steif herunterzuhängen oder - bei Fluggeschwindigkeit - im Laufschritt zu schlagen.
 * <p>
 * Im Modellraum liegt die Figur auf dem Bauch und die Körperachse zeigt nach vorn. Ein Arm mit
 * {@code xRot} 0 hängt damit nach hinten in den Wind; {@code zRot} spreizt ihn nach außen,
 * {@code xRot} hebt ihn zum Rücken (positiv) oder drückt ihn zur Brust (negativ). Die Arme dürfen
 * höchstens leicht zum Rücken schwingen: Dort liegen Rückeneinheit und Tragflächen.
 * <p>
 * Das Flattern ist eine Summe zweier Sinuswellen mit unverwandten Frequenzen und je Gliedmaße
 * verschobener Phase - eine einzelne Welle sähe nach Winken aus, nicht nach Böen. Die Amplitude
 * wächst mit dem Tempo. Alles wird mit dem Liegegrad überblendet, den auch Körper und Geschirr
 * lesen, deshalb gibt es beim Abheben und Landen keinen Sprung.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelGlideMixin {
   /** Grundspreizung der Arme nach außen, Bogenmaß. */
   @Unique private static final float ARM_SPREAD = 0.34F;
   /** Grundhaltung der Arme: leicht zur Brust gedrückt, damit sie unter den Tragflächen bleiben. */
   @Unique private static final float ARM_BACK = 0.04F;
   @Unique private static final float LEG_SPREAD = 0.10F;
   @Unique private static final float LEG_BACK = 0.12F;
   /** Kopfneigung im Flug; Vanilla nimmt -PI/4, etwas steiler hebt den Blick weiter nach vorn. */
   @Unique private static final float HEAD_UP = -0.92F;
   /** Tempo, ab dem das Flattern seine volle Stärke hat. */
   @Unique private static final float FULL_SPEED = 2.4F;

   @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
           at = @At("RETURN"))
   private void osok$poseGliding(HumanoidRenderState state, CallbackInfo ci) {
      if (!(state instanceof AvatarRenderState avatar)) {
         return;
      }
      Float stored = avatar.getData(GliderWingRenderer.LIE);
      if (stored == null || stored <= 0.001F) {
         return;
      }
      float w = stored;

      float speed = 0.6F;
      if (Minecraft.getInstance().level != null) {
         Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
         if (entity != null) {
            speed = (float) Math.min(1.0, entity.getDeltaMovement().length() / FULL_SPEED);
         }
      }
      float amplitude = 0.10F + 0.16F * speed;
      float t = avatar.ageInTicks;
      HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;

      // Kopf: folgt dem Blick, soweit die Figur es zulässt; Vanillas Festwert wird hier ersetzt.
      float lookUp = avatar.xRot * Mth.DEG_TO_RAD;
      model.head.xRot = Mth.lerp(w, lookUp, HEAD_UP + 0.04F * Mth.sin(t * 0.21F));
      model.head.zRot = Mth.lerp(w, model.head.zRot, 0.05F * Mth.sin(t * 0.17F + 1.0F) * speed);

      // Arme: nach hinten in den Wind, nach außen gespreizt, von Böen bewegt.
      // Rechts ist im Modellraum -X; positives zRot spreizt den rechten Arm nach außen.
      swing(model.rightArm, w, 1.0F,
         ARM_BACK + amplitude * gust(t, 0.0F),
         0.35F * amplitude * gust(t, 2.2F),
         ARM_SPREAD + 0.10F * speed + amplitude * gust(t, 4.1F));
      swing(model.leftArm, w, -1.0F,
         ARM_BACK + amplitude * gust(t, 1.7F),
         -0.35F * amplitude * gust(t, 3.6F),
         ARM_SPREAD + 0.10F * speed + amplitude * gust(t, 5.3F));

      // Beine: gestreckt und leicht gespreizt, mit schwachem Nachschwingen.
      float legAmplitude = 0.4F * amplitude;
      swing(model.rightLeg, w, 1.0F,
         LEG_BACK + legAmplitude * gust(t, 0.9F),
         0.0F,
         LEG_SPREAD + legAmplitude * gust(t, 2.8F));
      swing(model.leftLeg, w, -1.0F,
         LEG_BACK + legAmplitude * gust(t, 3.3F),
         0.0F,
         LEG_SPREAD + legAmplitude * gust(t, 1.4F));

      // Rumpf: ein Hauch Rollen, als drücke der Wind von der Seite.
      model.body.zRot = Mth.lerp(w, model.body.zRot, 0.035F * speed * gust(t, 6.0F));
   }

   /**
    * Setzt eine Gliedmaße auf die Zielhaltung, überblendet mit dem Liegegrad.
    * {@code side} ist +1 für rechts und -1 für links: Die Spreizung geht auf beiden Seiten nach
    * außen, also mit gegensätzlichem Vorzeichen.
    */
   @Unique
   private static void swing(net.minecraft.client.model.geom.ModelPart part, float weight, float side,
                             float pitch, float twist, float spread) {
      part.xRot = Mth.lerp(weight, part.xRot, pitch);
      part.yRot = Mth.lerp(weight, part.yRot, twist * side);
      part.zRot = Mth.lerp(weight, part.zRot, spread * side);
   }

   /** Böe zwischen -1 und 1: zwei Wellen, die sich nie genau wiederholen. */
   @Unique
   private static float gust(float t, float phase) {
      return 0.6F * Mth.sin(t * 0.37F + phase) + 0.4F * Mth.sin(t * 0.91F + phase * 1.7F + 1.3F);
   }
}

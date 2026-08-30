package com.oneshotonekill.item;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Ein Gegenstand, der erfahren will, wenn seine Benutzung abbricht.
 * <p>
 * <p>Vanilla meldet einem Gegenstand nur das Loslassen ({@code Item#releaseUsing}). Bricht die
 * Benutzung dagegen anders ab – Waffenwechsel, Slotwechsel, Tod –, läuft das über
 * {@code LivingEntity#stopUsingItem}, und dort fragt Vanilla den Gegenstand nicht mehr.
 * NeoForge hatte dafür {@code IItemExtension#onStopUsing}; Fabric API kennt kein Gegenstück.</p>
 * <p>
 * <p>Diesen einen Weg macht {@code LivingEntityStopUsingMixin} wieder auf. Ein Gegenstand, der
 * beim Abbruch etwas aufzuräumen hat, setzt dieses Interface – so bleibt der Mixin frei von
 * Wissen darüber, welche Gegenstände das betrifft.</p>
 */
public interface StopUsingAware {
   /**
    * @param stack  der Gegenstand, dessen Benutzung endet
    * @param entity wer ihn benutzt hat
    */
   void onStopUsing(ItemStack stack, LivingEntity entity);
}

package com.oneshotonekill.client.mixin;

import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Zugang zu den laufenden Tonkanälen, um deren Tonhöhe nachträglich zu ändern.
 *
 * <p>{@code SoundEngine#refreshCategoryVolume} zieht ausschließlich die Lautstärke nach, und die
 * Tonhöhe wird nur für {@code TickableSoundInstance} je Tick neu gesetzt. Ein gewöhnlicher
 * Weltklang behält deshalb bis zu seinem Ende die Tonhöhe, mit der er gestartet ist – während der
 * Zeitlupe klänge also alles Neue tief und alles bereits Laufende unverändert, genau umgekehrt
 * zur Erwartung. Ein Access Widener genügt nicht: {@code calculatePitch} ist zwar nur privat,
 * aber die Zuordnung von Instanz zu Kanal liegt in einer privaten Sammlung, die ohnehin
 * durchlaufen werden muss.</p>
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
   @Accessor("instanceToChannel")
   Map<SoundInstance, ChannelAccess.ChannelHandle> osok$instanceToChannel();

   /** Liefert bereits den vom {@code SoundEngineTimeDistortionMixin} abgesenkten Wert. */
   @Invoker("calculatePitch")
   float osok$calculatePitch(SoundInstance instance);
}

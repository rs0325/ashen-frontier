package io.github.rs0325.ashenfrontier.core;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AshenFrontierCore.MOD_ID)
public final class AshenFrontierCore {
    public static final String MOD_ID = "ashen_frontier_core";
    public static final String NAMESPACE = "ashen_frontier";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Ashen Frontier全Mod共通の名前空間でリソースIDを作る。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}

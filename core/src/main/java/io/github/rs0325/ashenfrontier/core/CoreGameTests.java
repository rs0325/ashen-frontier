package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Map;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class CoreGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void coreGameTestRuns(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void materialDataLoads(GameTestHelper helper) {
        MaterialData material = MaterialData.get(Items.STONE).orElseThrow();
        if (material.massKg() != 1.0) {
            helper.fail("Stone material mass was not loaded");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void itemPropertiesStack(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.STONE);
        first.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(500, 100));
        first.set(ItemProperties.WEAR, new ItemProperties.Wear(4, 10));
        first.set(ItemProperties.COMPOSITION, new ItemProperties.Composition(Map.of("iron", 0.9)));
        first.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.1)));
        first.set(ItemProperties.RADIOACTIVITY, new ItemProperties.Radioactivity("test:short_lived", 10, 100));
        first.set(ItemProperties.ROT, new ItemProperties.Rot(100, 100, 0));
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        ItemStack loaded = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, first).getOrThrow()).getOrThrow();
        ItemStack second = loaded.copy();
        if (!ItemStack.isSameItemSameComponents(first, second)) {
            helper.fail("ItemStack codec round trip changed components");
            return;
        }

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        if (!streamRoundTrips(ItemProperties.Temperature.STREAM_CODEC, buffer, first.get(ItemProperties.TEMPERATURE))
                || !streamRoundTrips(ItemProperties.Wear.STREAM_CODEC, buffer, first.get(ItemProperties.WEAR))
                || !streamRoundTrips(ItemProperties.Composition.STREAM_CODEC, buffer, first.get(ItemProperties.COMPOSITION))
                || !streamRoundTrips(ItemProperties.Contamination.STREAM_CODEC, buffer, first.get(ItemProperties.CONTAMINATION))
                || !streamRoundTrips(ItemProperties.Radioactivity.STREAM_CODEC, buffer, first.get(ItemProperties.RADIOACTIVITY))
                || !streamRoundTrips(ItemProperties.Rot.STREAM_CODEC, buffer, first.get(ItemProperties.ROT))) {
            buffer.release();
            helper.fail("A component stream codec round trip changed its value");
            return;
        }
        buffer.release();

        second.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(505, 100));
        if (ItemStack.isSameItemSameComponents(first, second)) {
            helper.fail("Different temperature buckets stacked");
            return;
        }
        ItemStack sameBucket = first.copy();
        sameBucket.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(504, 119));
        if (!ItemStack.isSameItemSameComponents(first, sameBucket)) {
            helper.fail("Values in the same temperature bucket did not stack");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void delayedPropertiesAdvance(GameTestHelper helper) {
        ItemProperties.registerHalfLife("test:short_lived", 1.0);
        if (ItemProperties.currentTemperature(new ItemStack(Items.AIR), 20, 300).isPresent()) {
            helper.fail("Temperature was calculated without material specific heat");
            return;
        }
        ItemStack stack = new ItemStack(Items.STONE);
        ItemStack unregistered = new ItemStack(Items.STONE);
        unregistered.set(ItemProperties.RADIOACTIVITY,
                new ItemProperties.Radioactivity("test:unregistered", 100, 0));
        if (ItemProperties.currentActivity(unregistered, 20).isPresent()
                || ItemProperties.writeCurrentActivity(unregistered, 20).isPresent()) {
            helper.fail("Unregistered nuclide was unexpectedly evaluated");
            return;
        }
        stack.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(400, 0));
        stack.set(ItemProperties.RADIOACTIVITY, new ItemProperties.Radioactivity("test:short_lived", 100, 0));
        stack.set(ItemProperties.ROT, new ItemProperties.Rot(0, 0, 0));

        double currentTemperature = ItemProperties.currentTemperature(stack, 200, 300).orElseThrow();
        double currentActivity = ItemProperties.currentActivity(stack, 20).orElseThrow();
        double currentRot = ItemProperties.currentRotProgress(stack, 20, 303.15).orElseThrow();
        if (!(currentTemperature < 400 && currentTemperature > 300) || currentActivity != 50 || currentRot <= 20) {
            helper.fail("Delayed temperature, activity, or rot calculation was incorrect");
            return;
        }

        ItemProperties.writeCurrentTemperature(stack, 200, 300).orElseThrow();
        ItemProperties.writeCurrentActivity(stack, 20).orElseThrow();
        ItemProperties.writeRotAtTemperature(stack, 20, 303.15).orElseThrow();
        if (stack.get(ItemProperties.TEMPERATURE).gameTime() != 200
                || stack.get(ItemProperties.RADIOACTIVITY).gameTime() != 20
                || stack.get(ItemProperties.ROT).lastCalculatedAt() != 20) {
            helper.fail("Current values were not committed to their components");
            return;
        }
        helper.succeed();
    }

    private static <T> boolean streamRoundTrips(StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                RegistryFriendlyByteBuf buffer, T value) {
        buffer.clear();
        codec.encode(buffer, value);
        buffer.readerIndex(0);
        return value.equals(codec.decode(buffer));
    }

}

package io.github.rs0325.ashenfrontier.core;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.world.item.Items;

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
}

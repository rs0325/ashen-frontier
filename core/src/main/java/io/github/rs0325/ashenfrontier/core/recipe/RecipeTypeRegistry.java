package io.github.rs0325.ashenfrontier.core.recipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 各機能Modの共通加工レシピ型をRecipeManagerへ登録する。 */
public final class RecipeTypeRegistry {
    private static final Map<ResourceLocation, Registration> REGISTRATIONS = new LinkedHashMap<>();

    private RecipeTypeRegistry() {}

    /** 共通レシピ型とserializerを登録し、後でJEI・EMIカテゴリ用に列挙できるようにする。 */
    public static Supplier<RecipeType<CommonRecipe>> register(IEventBus modBus, ResourceLocation id) {
        return register(modBus, id, TimeBasis.REAL);
    }

    /** 型の時間系を固定して登録する。既存の2引数登録は実秒。 */
    public static Supplier<RecipeType<CommonRecipe>> register(IEventBus modBus, ResourceLocation id, TimeBasis timeBasis) {
        if (REGISTRATIONS.containsKey(id)) {
            throw new IllegalArgumentException("レシピ型が重複しています: " + id);
        }
        DeferredRegister<RecipeType<?>> types = DeferredRegister.create(Registries.RECIPE_TYPE, id.getNamespace());
        DeferredRegister<RecipeSerializer<?>> serializers =
                DeferredRegister.create(Registries.RECIPE_SERIALIZER, id.getNamespace());
        DeferredHolder<RecipeType<?>, RecipeType<CommonRecipe>> type = types.register(id.getPath(),
                () -> RecipeType.simple(id));
        DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CommonRecipe>> serializer =
                serializers.register(id.getPath(), () -> CommonRecipe.serializer(id));
        types.register(modBus);
        serializers.register(modBus);
        REGISTRATIONS.put(id, new Registration(type, serializer, timeBasis));
        return type;
    }

    public static RecipeType<CommonRecipe> type(ResourceLocation id) {
        return registration(id).type().get();
    }

    public static RecipeSerializer<CommonRecipe> serializer(ResourceLocation id) {
        return registration(id).serializer().get();
    }

    public static TimeBasis timeBasis(ResourceLocation id) { return registration(id).timeBasis(); }

    public static Map<ResourceLocation, Supplier<RecipeType<CommonRecipe>>> registeredTypes() {
        Map<ResourceLocation, Supplier<RecipeType<CommonRecipe>>> result = new LinkedHashMap<>();
        REGISTRATIONS.forEach((id, registration) -> result.put(id, registration.type()));
        return Map.copyOf(result);
    }

    private static Registration registration(ResourceLocation id) {
        Registration registration = REGISTRATIONS.get(id);
        if (registration == null) throw new IllegalArgumentException("未登録のレシピ型です: " + id);
        return registration;
    }

    private record Registration(DeferredHolder<RecipeType<?>, RecipeType<CommonRecipe>> type,
                                DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CommonRecipe>> serializer,
                                TimeBasis timeBasis) {}
}

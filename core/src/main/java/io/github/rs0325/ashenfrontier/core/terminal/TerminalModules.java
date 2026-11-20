package io.github.rs0325.ashenfrontier.core.terminal;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** 端末拡張モジュールと解放する表示種別を登録する。 */
public final class TerminalModules {
    private static final Map<ResourceLocation, Module> MODULES = new LinkedHashMap<>();

    private TerminalModules() {}

    /**
     * Modの初期化時に端末モジュールを登録する。同じIDの登録は既存定義を置き換える。
     *
     * @param id モジュールの一意なID
     * @param item 差し込み・取り外しに使うアイテム
     * @param displays モジュールが有効にする表示種別ID
     */
    public static void register(ResourceLocation id, Item item, Set<ResourceLocation> displays) {
        MODULES.put(id, new Module(id, item, Set.copyOf(displays)));
    }

    static Map<ResourceLocation, Module> all() {
        return Map.copyOf(MODULES);
    }

    /**
     * プレイヤーに装着されたモジュールが表示種別を解放しているか返す。
     *
     * @param player 判定対象のサーバープレイヤー
     * @param display 確認する表示種別ID
     * @return 対応するモジュールが装着済みならtrue
     */
    public static boolean isDisplayEnabled(ServerPlayer player, ResourceLocation display) {
        Set<ResourceLocation> installed = player.getData(TerminalAttachments.MODULES);
        return installed.stream().map(MODULES::get).filter(Objects::nonNull)
                .anyMatch(module -> module.displays().contains(display));
    }

    /**
     * 所持品を検証してモジュールを差し込み、成功時はアイテムを消費する。
     *
     * @param player 差し込みを行うサーバープレイヤー
     * @param id 登録済みモジュールID
     * @return 差し込みに成功した場合はtrue
     */
    public static boolean insert(ServerPlayer player, ResourceLocation id) {
        Module module = MODULES.get(id);
        if (module == null) return false;
        var installed = player.getData(TerminalAttachments.MODULES);
        if (installed.contains(id)) return false;
        int slot = player.getInventory().findSlotMatchingItem(new ItemStack(module.item()));
        if (slot < 0) return false;
        player.getInventory().removeItem(slot, 1);
        installed.add(id);
        return true;
    }

    /**
     * 所持品に戻せる場合だけ装着モジュールを取り外す。
     *
     * @param player 取り外しを行うサーバープレイヤー
     * @param id 登録済みモジュールID
     * @return 取り外しに成功した場合はtrue
     */
    public static boolean remove(ServerPlayer player, ResourceLocation id) {
        Module module = MODULES.get(id);
        var installed = player.getData(TerminalAttachments.MODULES);
        if (module == null || !installed.contains(id)
                || !player.getInventory().add(new ItemStack(module.item()))) return false;
        installed.remove(id);
        return true;
    }

    record Module(ResourceLocation id, Item item, Set<ResourceLocation> displays) {}
}

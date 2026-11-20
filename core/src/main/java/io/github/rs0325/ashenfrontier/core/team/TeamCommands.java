package io.github.rs0325.ashenfrontier.core.team;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import java.util.stream.Collectors;

public final class TeamCommands {
    private TeamCommands() {}

    /** 共通ルートにコマンドを追加し、各モジュールの登録入口として使う。 */
    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("ashenfrontier");
    }
    public static void addSubcommand(RegisterCommandsEvent event,
                                     LiteralArgumentBuilder<CommandSourceStack> subcommand) {
        event.getDispatcher().register(root().then(subcommand));
    }
    public static void register(RegisterCommandsEvent event) {
        addSubcommand(event, Commands.literal("team")
                .then(Commands.literal("create").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TeamData data = TeamData.get(ctx.getSource().getServer());
                    if (data.teamFor(player.getUUID()).isPresent()) {
                        ctx.getSource().sendFailure(Component.translatable(
                                "command.ashen_frontier.team.already_member"));
                        return 0;
                    }
                    data.create(player.getUUID(), player.getGameProfile().getName());
                    ResearchManager.sync(player);
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.ashen_frontier.team.created"), false);
                    return 1;
                }))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    if (!TeamData.get(ctx.getSource().getServer()).invite(player.getUUID(), target.getUUID())) return 0;
                    target.sendSystemMessage(Component.translatable("command.ashen_frontier.team.invited"));
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.ashen_frontier.team.invite_sent"), false);
                    return 1;
                })))
                .then(Commands.literal("accept").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!TeamData.get(ctx.getSource().getServer()).accept(player.getUUID())) {
                        ctx.getSource().sendFailure(Component.translatable("command.ashen_frontier.team.no_invite"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.ashen_frontier.team.joined"), false);
                    ResearchManager.sync(player);
                    return 1;
                }))
                .then(Commands.literal("leave").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!ResearchManager.leaveTeam(player)) {
                        ctx.getSource().sendFailure(Component.translatable("command.ashen_frontier.team.none"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.ashen_frontier.team.left"), false);
                    ResearchManager.sync(player);
                    return 1;
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TeamData.get(ctx.getSource().getServer()).teamFor(player.getUUID()).ifPresentOrElse(
                            t -> ctx.getSource().sendSuccess(() -> Component.literal(
                                    t.name() + ": " + t.members().stream()
                                            .map(Object::toString).collect(Collectors.joining(", "))), false),
                            () -> ctx.getSource().sendFailure(
                                    Component.translatable("command.ashen_frontier.team.none")));
                    return 1;
                })));
    }
}

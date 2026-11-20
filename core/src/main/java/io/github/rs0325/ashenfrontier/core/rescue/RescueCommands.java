package io.github.rs0325.ashenfrontier.core.rescue;

import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgress;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.github.rs0325.ashenfrontier.core.team.TeamCommands;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;

public final class RescueCommands {
    private RescueCommands() {}

    public static void register(RegisterCommandsEvent event) {
        TeamCommands.addSubcommand(event, Commands.literal("rescue").requires(source -> source.hasPermission(2))
                .then(Commands.literal("repair").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> repair(context.getSource(),
                                EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("move").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("position", Vec3Argument.vec3())
                                .executes(context -> teleport(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        Vec3Argument.getVec3(context, "position")))))));
    }

    private static int repair(CommandSourceStack source, ServerPlayer target) {
        TeamData data = TeamData.get(source.getServer());
        TeamData.Team team = data.ensurePersonalTeam(target);
        ResearchProgress progress = ResearchProgress.get(source.getServer());
        boolean changed = progress.recalculate(team.id(), ResearchManager.tree());
        ResearchManager.sync(target);
        log(source, target, "repair team=" + team.id() + " researchChanged=" + changed);
        source.sendSuccess(() -> Component.translatable(
                "command.ashen_frontier.rescue.repaired", target.getGameProfile().getName()), true);
        return 1;
    }

    private static int teleport(CommandSourceStack source, ServerPlayer target, Vec3 position) {
        target.teleportTo((ServerLevel) source.getLevel(), position.x, position.y, position.z,
                Set.of(), target.getYRot(), target.getXRot());
        target.setPos(position.x, position.y, position.z);
        log(source, target, "teleport x=" + position.x + " y=" + position.y + " z=" + position.z);
        source.sendSuccess(() -> Component.translatable(
                "command.ashen_frontier.rescue.teleported", target.getGameProfile().getName()), true);
        return 1;
    }

    private static void log(CommandSourceStack source, ServerPlayer target, String action) {
        String line = Instant.now() + " actor=" + source.getTextName()
                + " target=" + target.getGameProfile().getName() + " action=" + action + System.lineSeparator();
        try {
            Files.writeString(source.getServer().getServerDirectory().resolve("ashen-frontier-rescue.log"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            AshenFrontierCore.LOGGER.error("Could not write rescue command log", exception);
        }
    }
}

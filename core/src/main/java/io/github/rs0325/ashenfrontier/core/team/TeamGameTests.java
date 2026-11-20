package io.github.rs0325.ashenfrontier.core.team;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgress;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class TeamGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void personalTeamsCanJoinAndLeave(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        TeamData data = TeamData.get(server);
        ResearchProgress progress = ResearchProgress.get(server);
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        var destination = data.create(owner, "招待元");
        var previous = data.create(member, "個人チーム");
        progress.record(previous.id(), "test:personal_condition");
        progress.unlock(previous.id(), "test:personal_research");
        progress.know(previous.id(), "test:personal_document");
        progress.unlock(destination.id(), "test:team_research");
        helper.assertTrue(!data.invite(owner, owner), "自分自身を招待できてしまいます");
        helper.assertTrue(data.invite(owner, member), "個人チームのメンバーを招待できません");
        helper.assertTrue(ResearchManager.acceptTeam(server, member), "個人チームから参加できません");
        helper.assertTrue(data.teamFor(member).orElseThrow().id().equals(destination.id()), "所属が移りません");
        helper.assertTrue(progress.conditions(previous.id()).isEmpty()
                && progress.unlocked(previous.id()).isEmpty() && progress.knowledge(previous.id()).isEmpty(),
                "解散した個人チームの進捗が残っています");
        helper.assertTrue(!progress.isUnlocked(destination.id(), "test:personal_research")
                && progress.isUnlocked(destination.id(), "test:team_research"), "個人進捗が混入しました");
        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(!saved.getCompound("teams").contains(previous.id()), "空の個人チームが保存されています");
        helper.assertTrue(ResearchManager.leaveTeam(server, member, "脱退後"), "脱退できません");
        String personal = data.teamFor(member).orElseThrow().id();
        helper.assertTrue(!personal.equals(destination.id()) && progress.unlocked(personal).isEmpty(),
                "脱退後に個人チームが作られないか共有進捗を引き継いでいます");
        helper.assertTrue(progress.isUnlocked(destination.id(), "test:team_research"), "残ったチームの進捗が消えました");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void invitationCannotMoveSharedTeamMembers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        TeamData data = TeamData.get(server);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        data.create(first, "先の招待");
        data.create(second, "参加先");
        data.create(member, "個人");
        helper.assertTrue(data.invite(first, member) && data.invite(second, member), "招待できません");
        helper.assertTrue(ResearchManager.acceptTeam(server, member), "最初の承諾に失敗しました");
        UUID teammate = data.teamFor(member).orElseThrow().members().stream()
                .filter(id -> !id.equals(member)).findFirst().orElseThrow();
        UUID other = teammate.equals(first) ? second : first;
        helper.assertTrue(!data.invite(other, member), "共有チームの所属者を招待できてしまいます");
        helper.assertTrue(!ResearchManager.acceptTeam(server, member), "古い招待で共有チームから移動できてしまいます");
        helper.assertTrue(data.teamFor(member).orElseThrow().members().size() == 2, "共有チームが壊れました");
        helper.succeed();
    }
}

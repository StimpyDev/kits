package dev.jpcode.kits.command;

import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.Formatting;

import dev.jpcode.kits.*;
import dev.jpcode.kits.access.ServerPlayerEntityAccess;

import static dev.jpcode.kits.InventoryUtil.offerAllCopies;
import static dev.jpcode.kits.KitUtil.runCommands;

public class KitClaimCommand implements Command<ServerCommandSource> {

    private final KitsModStorage storage;

    public KitClaimCommand(KitsModStorage storage) {
        this.storage = storage;
    }

    @Override
    public int run(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String kitName = StringArgumentType.getString(context, "kit_name");
        return exec(context.getSource().getPlayerOrThrow(), kitName);
    }

    public int exec(ServerPlayerEntity player, String kitName) {
        PlayerKitData playerData = ((ServerPlayerEntityAccess) player).kits$getPlayerData();
        var commandSource = player.getCommandSource();

        var kitRecordOpt = storage.getKitRecord(kitName);
        if (kitRecordOpt.isEmpty()) {
            player.getCommandSource().sendError(Text.literal(
                "Kit '%s' niet gevonden".formatted(kitName)
            ));
            return 2;
        }
        var kitRecord = kitRecordOpt.get();
        Kit kit = kitRecord.kit();
        long currentTime = Util.getEpochTimeMs();
        Optional<Long> lastUsed = playerData.getKitUsedTime(kitRecord.permissionName());
        long cooldown = kitRecord.cooldownMs();
        long remainingTime = lastUsed.map(aLong -> (aLong + cooldown) - currentTime).orElse(0L);

        if (!KitPerms.checkKit(commandSource, kitRecord)) {
            commandSource.sendError(Text.of(String.format(
                "Onvoldoende rechten voor kit '%s'.",
                kitName)));
            return -1;
        }

        // Check permanent choice for rings
        if (kitRecord.ring() != null && kitRecord.ring().permanentChoice()) {
            if (!playerData.mayClaimFromRing(kitRecord.ringName(), kitName)) {
                commandSource.sendError(Text.of(String.format(
                    "Je hebt '%s' al gekozen als je kit voor ring '%s'.",
                    playerData.getRingChoice(kitRecord.ringName()),
                    kitRecord.ringName())));
                return -1;
            }
        }

if (cooldown < 0 && lastUsed.isPresent()) {
    commandSource.sendError(Text.literal(
            String.format("Kit '%s' kan slechts één keer worden geclaimd.", kitName))
        .formatted(Formatting.RED));
    return -2;
} else if (remainingTime > 0) {
    commandSource.sendError(Text.literal(
            String.format("Kit '%s' is in afkoelperiode. %s resterend.", 
                kitName, TimeUtil.formatTime(remainingTime)))
        .formatted(Formatting.RED));
    return -2;
}

        PlayerInventory playerInventory = player.getInventory();
        playerData.useKit(kitName, kitRecord.cooldownKey());

        // Record ring selection if this is a permanent choice ring
        if (kitRecord.ring() != null && kitRecord.ring().permanentChoice()) {
            playerData.recordRingSelection(kitRecord.ringName(), kitName);
        }

        offerAllCopies(kit.inventory(), playerInventory);
        if (!kit.commands().isEmpty()) runCommands(player, kit.commands());
        
        commandSource.sendFeedback(() ->
            Text.literal(String.format("Kit '%s' succesvol geclaimd!", kitName))
                .formatted(Formatting.GREEN),
            false
        );

        return 1;
    }
}

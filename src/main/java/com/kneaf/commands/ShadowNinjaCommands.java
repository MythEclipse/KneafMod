package com.kneaf.commands;

import com.kneaf.core.KneafCore;
import com.kneaf.entities.ShadowNinjaSpawnHandler;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Commands for Shadow Ninja management
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ShadowNinjaCommands {
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        
        dispatcher.register(
            Commands.literal("shadowninja")
                .requires(source -> source.hasPermission(2)) // Op level 2
                .then(Commands.literal("status")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        var ninja = ShadowNinjaSpawnHandler.getActiveNinja(player.getUUID());
                        
                        if (ninja != null && !ninja.isRemoved()) {
                            context.getSource().sendSuccess(
                                () -> Component.literal("§cYou have an active Shadow Ninja hunting you!"), 
                                false
                            );
                        } else {
                            context.getSource().sendSuccess(
                                () -> Component.literal("§aNo Shadow Ninja is currently hunting you."), 
                                false
                            );
                        }
                        
                        return 1;
                    })
                )
                .then(Commands.literal("clear")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        var ninja = ShadowNinjaSpawnHandler.getActiveNinja(player.getUUID());
                        
                        if (ninja != null && !ninja.isRemoved()) {
                            ShadowNinjaSpawnHandler.despawnNinjaPublic(ninja);
                            context.getSource().sendSuccess(
                                () -> Component.literal("§aCleared your Shadow Ninja."), 
                                false
                            );
                        } else {
                            context.getSource().sendSuccess(
                                () -> Component.literal("§7No active Shadow Ninja to clear."), 
                                false
                            );
                        }
                        
                        return 1;
                    })
                )
                .then(Commands.literal("resetsleep")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        ShadowNinjaSpawnHandler.updatePlayerSleepTime(player);
                        
                        context.getSource().sendSuccess(
                            () -> Component.literal("§aReset your sleep timer."), 
                            false
                        );
                        
                        return 1;
                    })
                )
        );
    }
}

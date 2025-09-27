package com.kneaf.core;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.ArrayList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import java.lang.reflect.Field;
import java.util.Map;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(ExampleMod.MODID)
public class ExampleMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "kneafcore";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "kneafcore" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "kneafcore" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "kneafcore" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "kneafcore:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "kneafcore:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "kneafcore:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "kneafcore:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.kneafcore")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> output.accept(EXAMPLE_ITEM.get())).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register commands - handled by @SubscribeEvent since class is registered
        LOGGER.info("Commands listener will be registered via @SubscribeEvent on class registration");

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach(item -> LOGGER.info("ITEM >> {}", item));

        // Check for mod compatibility and log warnings
        ModCompatibility.checkForConflicts();
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    // Register commands
    @SubscribeEvent
    private void registerCommands(RegisterCommandsEvent event) {
        RustPerfStatusCommand.register(event.getDispatcher());
    }
    private static int tickCounter = 0;
    private static long lastTickTime = 0;

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        updateTPS();
        tickCounter++;
        MinecraftServer server = event.getServer();
        var data = collectEntityData(server);
        var results = processOptimizations(data);
        applyOptimizations(server, results);
        logOptimizations(results);
        removeItems(server, results.itemResult());
    }

    private static void updateTPS() {
        long currentTime = System.nanoTime();
        if (lastTickTime != 0) {
            long delta = currentTime - lastTickTime;
            double tps = 1_000_000_000.0 / delta;
            RustPerformance.setCurrentTPS(Math.min(tps, 20.0));
        }
        lastTickTime = currentTime;
    }

    private static EntityDataCollection collectEntityData(MinecraftServer server) {
        List<EntityData> entities = new ArrayList<>();
        List<ItemEntityData> items = new ArrayList<>();
        List<MobData> mobs = new ArrayList<>();
        List<BlockEntityData> blockEntities = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            collectEntitiesFromLevel(level, entities, items, mobs);
            collectBlockEntitiesFromLevel(level, blockEntities);
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof ItemEntity itemEntity) {
                collectItemEntity(entity, itemEntity, level, entities, items);
            } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
                collectMobEntity(entity, mob, level, mobs);
            }
        }
    }

    private static void collectItemEntity(Entity entity, ItemEntity itemEntity, ServerLevel level, List<EntityData> entities, List<ItemEntityData> items) {
        double distance = calculateDistanceToNearestPlayer(entity, level);
        entities.add(new EntityData(entity.getId(), distance, false, entity.getType().toString()));
        var chunkPos = entity.chunkPosition();
        var itemStack = itemEntity.getItem();
        var itemType = itemStack.getItem().getDescriptionId();
        var count = itemStack.getCount();
        var ageSeconds = itemEntity.getAge() / 20;
        items.add(new ItemEntityData(entity.getId(), chunkPos.x, chunkPos.z, itemType, count, ageSeconds));
    }

    private static void collectMobEntity(Entity entity, net.minecraft.world.entity.Mob mob, ServerLevel level, List<MobData> mobs) {
        double distance = calculateDistanceToNearestPlayer(entity, level);
        boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
        mobs.add(new MobData(entity.getId(), distance, isPassive, entity.getType().toString()));
    }

    private static void collectBlockEntitiesFromLevel(ServerLevel level, List<BlockEntityData> blockEntities) {
        try {
            Field tickersField = ServerLevel.class.getDeclaredField("blockEntityTickers");
            tickersField.setAccessible(true); // NOSONAR
            @SuppressWarnings("unchecked")
            Map<BlockPos, TickingBlockEntity> tickers = (Map<BlockPos, TickingBlockEntity>) tickersField.get(level);
            for (var entry : tickers.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    double distance = calculateDistanceToNearestPlayer(pos, level);
                    String blockType = be.getType().toString();
                    long id = ((long) pos.getX() << 32) | ((long) pos.getZ() << 16) | pos.getY();
                    blockEntities.add(new BlockEntityData(id, distance, blockType, pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to collect block entities", e);
        }
    }

    private static OptimizationResults processOptimizations(EntityDataCollection data) {
        List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities());
        List<Long> blockResult = RustPerformance.getBlockEntitiesToTick(data.blockEntities());
        RustPerformance.ItemProcessResult itemResult = RustPerformance.processItemEntities(data.items());
        RustPerformance.MobProcessResult mobResult = RustPerformance.processMobAI(data.mobs());
        return new OptimizationResults(toTick, blockResult, itemResult, mobResult);
    }

    private static void applyOptimizations(MinecraftServer server, OptimizationResults results) {
        applyItemUpdates(server, results.itemResult());
        applyMobOptimizations(server, results.mobResult());
    }

    private static void applyItemUpdates(MinecraftServer server, RustPerformance.ItemProcessResult itemResult) {
        for (var update : itemResult.getItemUpdates()) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity((int) update.getId());
                if (entity instanceof ItemEntity itemEntity) {
                    itemEntity.getItem().setCount(update.getNewCount());
                }
            }
        }
    }

    private static void applyMobOptimizations(MinecraftServer server, RustPerformance.MobProcessResult mobResult) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Long id : mobResult.getMobsToDisableAI()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setNoAi(true);
                }
            }
            for (Long id : mobResult.getMobsToSimplifyAI()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity instanceof net.minecraft.world.entity.Mob) {
                    LOGGER.debug("Simplifying AI for mob {}", id);
                }
            }
        }
    }

    private static void logOptimizations(OptimizationResults results) {
        if (tickCounter % 100 == 0 && hasOptimizations(results)) {
            LOGGER.info("Entities to tick: {}", results.toTick().size());
            LOGGER.info("Block entities to tick: {}", results.blockResult().size());
            LOGGER.info("Item optimization: {} merged, {} despawned", results.itemResult().getMergedCount(), results.itemResult().getDespawnedCount());
            LOGGER.info("Mob AI optimization: {} disabled, {} simplified", results.mobResult().getMobsToDisableAI().size(), results.mobResult().getMobsToSimplifyAI().size());
        }
    }

    private static boolean hasOptimizations(OptimizationResults results) {
        return !results.toTick().isEmpty() || results.itemResult().getMergedCount() > 0 || results.itemResult().getDespawnedCount() > 0 ||
               !results.mobResult().getMobsToDisableAI().isEmpty() || !results.mobResult().getMobsToSimplifyAI().isEmpty() || !results.blockResult().isEmpty();
    }

    private static void removeItems(MinecraftServer server, RustPerformance.ItemProcessResult itemResult) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Long id : itemResult.getItemsToRemove()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity != null) {
                    entity.remove(RemovalReason.DISCARDED);
                }
            }
        }
    }

    private record EntityDataCollection(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, List<BlockEntityData> blockEntities) {}
    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, RustPerformance.ItemProcessResult itemResult, RustPerformance.MobProcessResult mobResult) {}

    private static double calculateDistanceToNearestPlayer(Entity entity, ServerLevel level) {
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = entity.distanceTo(player);
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }

    private static double calculateDistanceToNearestPlayer(BlockPos pos, ServerLevel level) {
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = Math.sqrt(pos.distSqr(player.blockPosition()));
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }


    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = ExampleMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    static class ClientModEvents {
        private ClientModEvents() {}

        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
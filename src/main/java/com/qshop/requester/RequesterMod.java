package com.qshop.requester;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

@Mod(RequesterMod.MODID)
public final class RequesterMod {
    public static final String MODID = "qshop_requester";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<Block, Block> REQUESTER = BLOCKS.register("requester",
            () -> new RequesterBlock(Block.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F, 2.5F)
                    .sound(SoundType.WOOD)));
    public static final DeferredHolder<Item, Item> REQUESTER_ITEM = ITEMS.register("requester",
            () -> new BlockItem(REQUESTER.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RequesterBlockEntity>> REQUESTER_ENTITY =
            BLOCK_ENTITIES.register("requester", () -> BlockEntityType.Builder.of(
                    RequesterBlockEntity::new, REQUESTER.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<RequesterMenu>> REQUESTER_MENU = MENUS.register(
            "requester", () -> {
                IContainerFactory<RequesterMenu> factory = RequesterMenu::new;
                return new MenuType<>(factory, net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
            });
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("requester",
            () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.qshop_requester"))
                    .icon(() -> REQUESTER_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(REQUESTER_ITEM.get()))
                    .build());

    public RequesterMod(ModContainer modContainer, net.neoforged.bus.api.IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        modContainer.registerConfig(ModConfig.Type.COMMON, RequesterConfig.SPEC,
                "qshop_requester-common.toml");
        RequesterNetwork.init(bus);
        bus.addListener(RequesterMod::registerCapabilities);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.addListener(RequesterClient::registerMenuScreens);
        }
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, REQUESTER_ENTITY.get(),
                (box, side) -> side == net.minecraft.core.Direction.DOWN
                        ? new RequesterItemHandler(box.purchased(), false, true)
                        : new RequesterItemHandler(box.supplied(), true, false));
    }
}

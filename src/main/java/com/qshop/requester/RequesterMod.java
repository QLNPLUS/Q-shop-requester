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
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

@Mod(RequesterMod.MODID)
public final class RequesterMod {
    public static final String MODID = "qshop_requester";

    // 必须声明为子类型 DeferredRegister.Blocks / .Items —— 收窄成 DeferredRegister<Block>
    // 会丢掉 registerBlock / registerSimpleBlockItem 这些会写 id 的重载。
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 26.1.2 起 BlockBehaviour.Properties 必须带上自己的 ResourceKey:BlockBehaviour 的
    // 构造器会调用 Properties.effectiveDrops(),那里 requireNonNull 这个 id,否则
    // 抛 "Block id not set"。DeferredRegister$Blocks.registerBlock 会在构造方块之前
    // 调用 Properties.setId(...);朴素的 register(name, supplier) 不会,所以必须用它。
    public static final DeferredBlock<Block> REQUESTER = BLOCKS.registerBlock("requester",
            properties -> new RequesterBlock(properties
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F, 2.5F)
                    .sound(SoundType.WOOD)));
    // 同理,Item.Properties 也要 setId;registerSimpleBlockItem 会一并处理。
    public static final DeferredItem<BlockItem> REQUESTER_ITEM =
            ITEMS.registerSimpleBlockItem("requester", REQUESTER);
    // 26.1.2:BlockEntityType.Builder 已移除。直接用构造器;
    // 原 Builder.of(factory, blocks).build(null) 的等价形式是 (supplier, Block...) 重载
    // —— Type(datafixer)参数已从构造器签名中移除。
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RequesterBlockEntity>> REQUESTER_ENTITY =
            BLOCK_ENTITIES.register("requester", () -> new BlockEntityType<>(
                    RequesterBlockEntity::new, REQUESTER.get()));
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
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            bus.addListener(RequesterClient::registerMenuScreens);
        }
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, REQUESTER_ENTITY.get(),
                (box, side) -> side == net.minecraft.core.Direction.DOWN
                        ? new RequesterItemHandler(box.purchased(), false, true)
                        : new RequesterItemHandler(box.supplied(), true, false));
    }
}

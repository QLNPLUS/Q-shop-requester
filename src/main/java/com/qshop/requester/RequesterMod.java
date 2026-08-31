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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.IContainerFactory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(RequesterMod.MODID)
public final class RequesterMod {
    public static final String MODID = "qshop_requester";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<Block> REQUESTER = BLOCKS.register("requester",
            () -> new RequesterBlock(Block.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F, 2.5F)
                    .sound(SoundType.WOOD)));
    public static final RegistryObject<Item> REQUESTER_ITEM = ITEMS.register("requester",
            () -> new BlockItem(REQUESTER.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<RequesterBlockEntity>> REQUESTER_ENTITY =
            BLOCK_ENTITIES.register("requester", () -> BlockEntityType.Builder.of(
                    RequesterBlockEntity::new, REQUESTER.get()).build(null));
    public static final RegistryObject<MenuType<RequesterMenu>> REQUESTER_MENU = MENUS.register(
            "requester", () -> {
                IContainerFactory<RequesterMenu> factory = RequesterMenu::new;
                return new MenuType<>(factory, net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
            });
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("requester",
            () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.qshop_requester"))
                    .icon(() -> REQUESTER_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(REQUESTER_ITEM.get()))
                    .build());

    public RequesterMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, RequesterConfig.SPEC, "qshop_requester-common.toml");
        RequesterNetwork.init();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> bus.addListener(RequesterClient::onClientSetup));
    }
}

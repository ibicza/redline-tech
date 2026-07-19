package com.ibicza.redlineatlasworldgen.gametest;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.function.Consumer;

public final class WaterSurfaceFlowGameTests {
    private static final BlockPos FLOW = new BlockPos(1, 2, 1);
    private static final BlockPos SUPPORT = FLOW.below();
    private static final BlockPos TARGET = FLOW.east();
    private static final BlockPos TARGET_SUPPORT = TARGET.below();
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    private static final Identifier ENVIRONMENT = id("water_surface_flow_environment");

    private static final TestFunction SUPPORTED_WATER = testFunction(
            "water_spreads_across_water_support",
            WaterSurfaceFlowGameTests::waterSpreadsAcrossWaterSupport
    );
    private static final TestFunction UNSUPPORTED_EDGE = testFunction(
            "water_does_not_self_support_over_air",
            WaterSurfaceFlowGameTests::waterDoesNotSelfSupportOverAir
    );
    private static final TestFunction SOLID_EDGE = testFunction(
            "water_keeps_vanilla_solid_edge_flow",
            WaterSurfaceFlowGameTests::waterKeepsVanillaSolidEdgeFlow
    );

    private WaterSurfaceFlowGameTests() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(WaterSurfaceFlowGameTests::registerFunctions);
        modEventBus.addListener(WaterSurfaceFlowGameTests::registerTests);
    }

    private static void registerFunctions(RegisterEvent event) {
        event.register(BuiltInRegistries.TEST_FUNCTION.key(), registry -> {
            registerFunction(registry, SUPPORTED_WATER);
            registerFunction(registry, UNSUPPORTED_EDGE);
            registerFunction(registry, SOLID_EDGE);
        });
    }

    private static void registerFunction(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> registry,
                                         TestFunction testFunction) {
        registry.register(testFunction.id(), testFunction.function());
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                ENVIRONMENT,
                new TestEnvironmentDefinition.AllOf()
        );
        registerTest(event, environment, SUPPORTED_WATER);
        registerTest(event, environment, UNSUPPORTED_EDGE);
        registerTest(event, environment, SOLID_EDGE);
    }

    private static void registerTest(RegisterGameTestsEvent event,
                                     Holder<TestEnvironmentDefinition<?>> environment,
                                     TestFunction testFunction) {
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                EMPTY_STRUCTURE,
                20,
                0,
                true
        );
        event.registerTest(
                testFunction.id(),
                new FunctionGameTestInstance(testFunction.key(), data)
        );
    }

    private static TestFunction testFunction(String path, Consumer<GameTestHelper> function) {
        Identifier id = id(path);
        ResourceKey<Consumer<GameTestHelper>> key = ResourceKey.create(Registries.TEST_FUNCTION, id);
        return new TestFunction(id, key, function);
    }

    private static void waterSpreadsAcrossWaterSupport(GameTestHelper helper) {
        prepareFixture(helper, Blocks.WATER, Blocks.WATER);
        flowOnce(helper);
        assertWaterAndSucceed(
                helper,
                TARGET,
                "Water did not spread onto a water-supported target"
        );
    }

    private static void waterDoesNotSelfSupportOverAir(GameTestHelper helper) {
        prepareFixture(helper, Blocks.WATER, Blocks.AIR);
        flowOnce(helper);
        helper.assertTrue(
                helper.getBlockState(TARGET).isAir(),
                "Water spread sideways over an unsupported air column"
        );
        helper.succeed();
    }

    private static void waterKeepsVanillaSolidEdgeFlow(GameTestHelper helper) {
        prepareFixture(helper, Blocks.STONE, Blocks.AIR);
        flowOnce(helper);
        assertWaterAndSucceed(
                helper,
                TARGET,
                "Water on solid support no longer follows vanilla edge flow"
        );
    }

    private static void flowOnce(GameTestHelper helper) {
        helper.setBlock(FLOW, Blocks.WATER);
        BlockPos flowPos = helper.absolutePos(FLOW);
        helper.getLevel().getFluidState(flowPos).tick(
                helper.getLevel(),
                flowPos,
                helper.getLevel().getBlockState(flowPos)
        );
    }

    private static void prepareFixture(GameTestHelper helper, Block flowSupport, Block targetSupport) {
        helper.setBlock(FLOW, Blocks.AIR);
        helper.setBlock(SUPPORT, flowSupport);
        helper.setBlock(TARGET, Blocks.AIR);
        helper.setBlock(TARGET_SUPPORT, targetSupport);
    }

    private static void assertWaterAndSucceed(GameTestHelper helper, BlockPos pos, String message) {
        helper.assertTrue(helper.getBlockState(pos).getFluidState().is(FluidTags.WATER), message);
        helper.succeed();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RedlineAtlasWorldgen.MOD_ID, path);
    }

    private record TestFunction(
            Identifier id,
            ResourceKey<Consumer<GameTestHelper>> key,
            Consumer<GameTestHelper> function
    ) {
    }
}

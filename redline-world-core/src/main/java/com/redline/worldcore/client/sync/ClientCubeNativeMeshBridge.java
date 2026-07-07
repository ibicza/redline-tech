package com.redline.worldcore.client.sync;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Vector3fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M19.8.2 vanilla-backed native cube renderer.
 *
 * <p>The renderer lifecycle is still fully cube-native: snapshots come from {@link ClientCubeSectionStore}, mesh dirtying
 * is by {@link CubePos}, and outside-shell rendering never writes into vanilla {@code ClientLevel}/{@code LevelChunkSection}.
 * The mesh builder now uses vanilla baked block quads and the vanilla fluid renderer through a small cube-backed
 * {@link BlockAndTintGetter}.  The old map-color cuboid renderer stays as a fallback for model failures only.</p>
 */
public final class ClientCubeNativeMeshBridge {
    private static final int HORIZONTAL_SCAN_RADIUS = 4;
    private static final int VERTICAL_SCAN_RADIUS = 3;
    private static final boolean SURFACE_PROJECTION_MESH_ENABLED = false;
    private static final int MAX_DISCOVERY_SCAN_PER_TICK = 160;
    private static final int MAX_MESH_SECTIONS_PER_TICK = 2;
    private static final int MAX_MESH_BLOCKS_PER_TICK = 8_192;
    private static final int MAX_MESH_MICROS_PER_TICK = 3_000;
    private static final int MAX_TRACKED_MESHES = 512;
    private static final int MAX_RENDER_MESHES_PER_FRAME = 64;
    private static final int MAX_RENDER_QUADS_PER_FRAME = 85_000;
    private static final int MAX_QUADS_PER_SECTION = 8192;
    private static final int FULL_BRIGHT = 0x00F000F0;

    private static final ArrayDeque<CubePos> MESH_QUEUE = new ArrayDeque<>();
    private static final Map<CubePos, MeshBuildTask> TASKS = new HashMap<>();
    private static final Map<CubePos, NativeSectionMesh> MESHES = new HashMap<>();

    private static boolean enabled = true;
    private static long scans;
    private static long tasksQueued;
    private static long sectionsBuilt;
    private static long blocksScanned;
    private static long solidBlocks;
    private static long vanillaQuadsBuilt;
    private static long fluidQuadsBuilt;
    private static long fallbackFacesBuilt;
    private static long modelFailures;
    private static long hashHits;
    private static long invalidations;
    private static long budgetHits;
    private static long timeBudgetHits;
    private static long renderSubmits;
    private static long renderSkippedInsideShell;
    private static int queueSize;
    private static int meshCount;
    private static int blocksLastTick;
    private static int readyAroundPlayer;
    private static int renderedMeshesLastFrame;
    private static int solidQuadsLastFrame;
    private static int translucentQuadsLastFrame;
    private static String lastResetReason = "never";

    private static ModelBlockRenderer modelRenderer;
    private static FluidRenderer fluidRenderer;

    private ClientCubeNativeMeshBridge() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !CubicDimensionKeys.isCubicTest(minecraft.level)) {
            clearTransientStats();
            return;
        }
        CubePos playerCube = CubePos.fromBlock(minecraft.player.blockPosition());
        Map<CubePos, ClientCubeSectionSnapshot> sections = ClientCubeSectionStore.copySections();
        discoverMeshTasks(minecraft.level, playerCube, sections);
        processMeshQueue(minecraft, minecraft.level, sections, playerCube);
        trimMeshes();
        updateReadyStats(minecraft.level, playerCube);
        scans++;
    }

    public static void submitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (!enabled) {
            renderedMeshesLastFrame = 0;
            solidQuadsLastFrame = 0;
            translucentQuadsLastFrame = 0;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !CubicDimensionKeys.isCubicTest(minecraft.level)) {
            renderedMeshesLastFrame = 0;
            solidQuadsLastFrame = 0;
            translucentQuadsLastFrame = 0;
            return;
        }

        CubePos playerCube = CubePos.fromBlock(minecraft.player.blockPosition());
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        List<NativeSectionMesh> renderList = snapshotMeshesForFrame(minecraft.level, playerCube);
        if (renderList.isEmpty()) {
            renderedMeshesLastFrame = 0;
            solidQuadsLastFrame = 0;
            translucentQuadsLastFrame = 0;
            return;
        }

        int solidQuads = 0;
        int translucentQuads = 0;
        for (NativeSectionMesh mesh : renderList) {
            solidQuads += mesh.solidQuads.size() + mesh.cutoutQuads.size() + mesh.fallbackFaces.size();
            translucentQuads += mesh.translucentQuads.size();
        }
        renderedMeshesLastFrame = renderList.size();
        solidQuadsLastFrame = solidQuads;
        translucentQuadsLastFrame = translucentQuads;
        renderSubmits++;
        RuntimeProfiler.addCount("client.native_renderer_submit_meshes", renderList.size());
        RuntimeProfiler.addCount("client.native_renderer_submit_solid_quads", solidQuads);
        RuntimeProfiler.addCount("client.native_renderer_submit_translucent_quads", translucentQuads);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        if (solidQuads > 0) {
            event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.solidMovingBlock(), (pose, consumer) -> {
                for (NativeSectionMesh mesh : renderList) {
                    mesh.emitSolid(pose, consumer);
                }
            });
            event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.cutoutMovingBlock(), (pose, consumer) -> {
                for (NativeSectionMesh mesh : renderList) {
                    mesh.emitCutout(pose, consumer);
                }
            });
        }
        if (translucentQuads > 0) {
            event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), (pose, consumer) -> {
                for (NativeSectionMesh mesh : renderList) {
                    mesh.emitTranslucent(pose, consumer);
                }
            });
        }
        poseStack.popPose();
    }

    public static synchronized void forget(CubePos cubePos) {
        MESHES.remove(cubePos);
        TASKS.remove(cubePos);
        MESH_QUEUE.remove(cubePos);
    }

    public static synchronized void invalidate(CubePos cubePos) {
        if (MESHES.remove(cubePos) != null) {
            invalidations++;
            RuntimeProfiler.addCount("client.native_mesh_invalidations", 1);
        }
        TASKS.remove(cubePos);
        MESH_QUEUE.remove(cubePos);
        enqueue(cubePos);
    }

    public static synchronized void resetAll(String reason) {
        MESHES.clear();
        TASKS.clear();
        MESH_QUEUE.clear();
        lastResetReason = reason;
        RuntimeProfiler.addCount("client.native_renderer_resets", 1);
    }

    public static synchronized void setEnabled(boolean enabled) {
        ClientCubeNativeMeshBridge.enabled = enabled;
        if (!enabled) {
            resetAll("disabled");
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    private static void clearTransientStats() {
        queueSize = 0;
        meshCount = MESHES.size();
        blocksLastTick = 0;
        readyAroundPlayer = 0;
        renderedMeshesLastFrame = 0;
        solidQuadsLastFrame = 0;
        translucentQuadsLastFrame = 0;
        MESH_QUEUE.clear();
        TASKS.clear();
    }

    private static void discoverMeshTasks(ClientLevel level, CubePos playerCube, Map<CubePos, ClientCubeSectionSnapshot> sections) {
        int scanned = 0;
        List<Map.Entry<CubePos, ClientCubeSectionSnapshot>> sorted = sections.entrySet().stream()
                .sorted((first, second) -> Integer.compare(meshPriority(first.getKey(), playerCube), meshPriority(second.getKey(), playerCube)))
                .toList();
        for (Map.Entry<CubePos, ClientCubeSectionSnapshot> entry : sorted) {
            if (scanned++ >= MAX_DISCOVERY_SCAN_PER_TICK) {
                RuntimeProfiler.addCount("client.native_mesh_discovery_budget_hits", 1);
                break;
            }
            CubePos cubePos = entry.getKey();
            ClientCubeSectionSnapshot snapshot = entry.getValue();
            if (!isOutsideVanillaShell(level, cubePos)) {
                renderSkippedInsideShell++;
                continue;
            }
            if (!isWithinScanRadius(cubePos, playerCube, snapshot)) {
                continue;
            }
            NativeSectionMesh mesh = MESHES.get(cubePos);
            if (mesh != null && mesh.hash == snapshot.hash()) {
                hashHits++;
                RuntimeProfiler.addCount("client.native_mesh_hash_hits", 1);
                continue;
            }
            MeshBuildTask existing = TASKS.get(cubePos);
            if (existing != null && existing.hash == snapshot.hash()) {
                continue;
            }
            TASKS.put(cubePos, new MeshBuildTask(snapshot.hash()));
            enqueue(cubePos);
        }
        queueSize = MESH_QUEUE.size();
        meshCount = MESHES.size();
        RuntimeProfiler.addCount("client.native_mesh_discovery_scanned", scanned);
    }

    private static void enqueue(CubePos cubePos) {
        if (!MESH_QUEUE.contains(cubePos)) {
            MESH_QUEUE.addLast(cubePos);
            tasksQueued++;
            RuntimeProfiler.addCount("client.native_mesh_tasks_queued", 1);
        }
    }

    private static void processMeshQueue(Minecraft minecraft, ClientLevel level, Map<CubePos, ClientCubeSectionSnapshot> sections, CubePos playerCube) {
        int blocks = 0;
        int completed = 0;
        long start = System.nanoTime();
        while (!MESH_QUEUE.isEmpty() && completed < MAX_MESH_SECTIONS_PER_TICK && blocks < MAX_MESH_BLOCKS_PER_TICK) {
            if (blocks > 0 && elapsedMicrosSince(start) >= MAX_MESH_MICROS_PER_TICK) {
                timeBudgetHits++;
                RuntimeProfiler.addCount("client.native_mesh_time_budget_hits", 1);
                break;
            }
            CubePos cubePos = MESH_QUEUE.removeFirst();
            ClientCubeSectionSnapshot snapshot = sections.get(cubePos);
            MeshBuildTask task = TASKS.get(cubePos);
            if (snapshot == null || !isOutsideVanillaShell(level, cubePos) || !isWithinScanRadius(cubePos, playerCube, snapshot)) {
                TASKS.remove(cubePos);
                MESHES.remove(cubePos);
                RuntimeProfiler.addCount("client.native_mesh_missing_or_stale", 1);
                continue;
            }
            if (task == null || task.hash != snapshot.hash()) {
                task = new MeshBuildTask(snapshot.hash());
                TASKS.put(cubePos, task);
            }
            int scanned = meshSlice(minecraft, level, sections, snapshot, task, MAX_MESH_BLOCKS_PER_TICK - blocks, start);
            blocks += scanned;
            if (task.cursor >= CubePos.BLOCK_COUNT || task.quadLimitHit) {
                TASKS.remove(cubePos);
                MESHES.put(cubePos, task.toMesh(snapshot.hash()));
                completed++;
                sectionsBuilt++;
                RuntimeProfiler.addCount("client.native_mesh_sections_built", 1);
            } else {
                MESH_QUEUE.addLast(cubePos);
                budgetHits++;
                RuntimeProfiler.addCount("client.native_mesh_budget_hits", 1);
                break;
            }
        }
        blocksLastTick = blocks;
        blocksScanned += blocks;
        queueSize = MESH_QUEUE.size();
        meshCount = MESHES.size();
        RuntimeProfiler.addCount("client.native_mesh_blocks_scanned", blocks);
        RuntimeProfiler.addCount("client.native_mesh_queue", queueSize);
    }

    private static int meshSlice(Minecraft minecraft, ClientLevel level, Map<CubePos, ClientCubeSectionSnapshot> sections,
                                 ClientCubeSectionSnapshot snapshot, MeshBuildTask task, int maxBlocks, long tickStartNanos) {
        ensureVanillaRenderers(minecraft);
        CubeRenderView view = new CubeRenderView(level, sections);
        ModelManager modelManager = minecraft.getModelManager();
        int scanned = 0;
        while (task.cursor < CubePos.BLOCK_COUNT && scanned < maxBlocks && !task.quadLimitHit) {
            if (scanned > 0 && (scanned & 31) == 0 && elapsedMicrosSince(tickStartNanos) >= MAX_MESH_MICROS_PER_TICK) {
                break;
            }
            int localIndex = task.cursor++;
            BlockState state = snapshot.blockStateAtLocalIndex(localIndex);
            if (!shouldRenderState(state)) {
                scanned++;
                continue;
            }
            task.solidBlocks++;
            solidBlocks++;
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = localIndex >> (CubePos.SIZE_BITS * 2);
            int worldX = snapshot.cubePos().minBlockX() + localX;
            int worldY = snapshot.cubePos().minBlockY() + localY;
            int worldZ = snapshot.cubePos().minBlockZ() + localZ;
            BlockPos pos = new BlockPos(worldX, worldY, worldZ);

            boolean rendered = false;
            boolean fluidPresent = false;
            boolean fluidRendered = false;
            try {
                FluidState fluid = state.getFluidState();
                fluidPresent = !fluid.isEmpty();
                if (fluidPresent && fluidRenderer != null) {
                    int beforeFluidQuads = task.quadCount();
                    fluidRenderer.tesselate(view, pos, task.fluidOutput, state, fluid);
                    fluidRendered = task.quadCount() > beforeFluidQuads;
                    rendered |= fluidRendered;
                }
                if (state.getRenderShape() == RenderShape.MODEL) {
                    int beforeModelQuads = task.quadCount();
                    BlockStateModel model = modelManager.getBlockStateModelSet().get(state);
                    task.currentState = state;
                    task.currentPos = pos;
                    modelRenderer.tesselateBlock(task.blockOutput, 0.0F, 0.0F, 0.0F, view, pos, state, model, net.minecraft.util.Mth.getSeed(pos));
                    rendered |= task.quadCount() > beforeModelQuads;
                }
            } catch (Throwable failure) {
                modelFailures++;
                RuntimeProfiler.addCount("client.native_model_failures", 1);
                rendered = false;
            } finally {
                task.currentState = null;
                task.currentPos = null;
            }

            // Only use the crude translucent fallback if vanilla FluidRenderer emitted nothing at all.  Drawing fallback
            // faces on top of successful fluid quads samples the whole block atlas with 0..1 UVs and creates the cursed
            // blue texture-atlas mosaic that showed up in M19.8.4.
            if (fluidPresent && !fluidRendered) {
                buildFallbackFaces(sections, snapshot, state, localX, localY, localZ, worldX, worldY, worldZ, task);
                rendered = true;
            }
            if (!rendered && state.getRenderShape() != RenderShape.INVISIBLE) {
                buildFallbackFaces(sections, snapshot, state, localX, localY, localZ, worldX, worldY, worldZ, task);
            }
            if (task.quadCount() >= MAX_QUADS_PER_SECTION) {
                task.quadLimitHit = true;
                RuntimeProfiler.addCount("client.native_mesh_quad_limit_hits", 1);
            }
            scanned++;
        }
        return scanned;
    }

    private static void ensureVanillaRenderers(Minecraft minecraft) {
        if (modelRenderer == null) {
            modelRenderer = new ModelBlockRenderer(true, true, minecraft.getBlockColors());
        }
        if (fluidRenderer == null) {
            fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
        }
    }

    private static boolean shouldRenderState(BlockState state) {
        return !state.isAir() && (state.getRenderShape() != RenderShape.INVISIBLE || !state.getFluidState().isEmpty());
    }

    private static void buildFallbackFaces(Map<CubePos, ClientCubeSectionSnapshot> sections, ClientCubeSectionSnapshot snapshot,
                                           BlockState state, int localX, int localY, int localZ, int worldX, int worldY, int worldZ,
                                           MeshBuildTask task) {
        int baseColor = fallbackColor(state);
        boolean translucent = isTranslucentFallback(state);
        for (Direction direction : Direction.values()) {
            if (faceVisible(sections, snapshot, state, localX, localY, localZ, direction)) {
                NativeFallbackFace face = new NativeFallbackFace(worldX, worldY, worldZ, direction, shadeColor(baseColor, direction, translucent));
                if (translucent) {
                    task.translucentFallbackFaces.add(face);
                } else {
                    task.fallbackFaces.add(face);
                }
                fallbackFacesBuilt++;
            }
        }
    }

    private static boolean faceVisible(Map<CubePos, ClientCubeSectionSnapshot> sections, ClientCubeSectionSnapshot snapshot,
                                       BlockState state, int localX, int localY, int localZ, Direction direction) {
        int nx = localX + direction.getStepX();
        int ny = localY + direction.getStepY();
        int nz = localZ + direction.getStepZ();
        BlockState neighbor;
        if (CubePos.isLocal(nx) && CubePos.isLocal(ny) && CubePos.isLocal(nz)) {
            neighbor = snapshot.blockStateAtLocalIndex(CubePos.localIndex(nx, ny, nz));
        } else {
            neighbor = neighborAcrossCubeBoundary(sections, snapshot, nx, ny, nz);
            if (neighbor == null) {
                return true;
            }
        }
        if (neighbor.isAir()) {
            return true;
        }
        if (isTranslucentFallback(state) || isTranslucentFallback(neighbor)) {
            return !state.equals(neighbor);
        }
        return !state.skipRendering(neighbor, direction);
    }

    private static BlockState neighborAcrossCubeBoundary(Map<CubePos, ClientCubeSectionSnapshot> sections, ClientCubeSectionSnapshot snapshot, int localX, int localY, int localZ) {
        int cubeDx = localX < 0 ? -1 : localX >= CubePos.SIZE ? 1 : 0;
        int cubeDy = localY < 0 ? -1 : localY >= CubePos.SIZE ? 1 : 0;
        int cubeDz = localZ < 0 ? -1 : localZ >= CubePos.SIZE ? 1 : 0;
        ClientCubeSectionSnapshot neighborSnapshot = sections.get(snapshot.cubePos().offset(cubeDx, cubeDy, cubeDz));
        if (neighborSnapshot == null) {
            return null;
        }
        int nx = localX & CubePos.MASK;
        int ny = localY & CubePos.MASK;
        int nz = localZ & CubePos.MASK;
        return neighborSnapshot.blockStateAtLocalIndex(CubePos.localIndex(nx, ny, nz));
    }

    private static boolean isTranslucentFallback(BlockState state) {
        return state.liquid()
                || !state.getFluidState().isEmpty()
                || state.getBlock() == Blocks.GLASS
                || state.getBlock() == Blocks.GLASS_PANE
                || state.getBlock() == Blocks.TINTED_GLASS
                || !state.canOcclude();
    }

    private static int fallbackColor(BlockState state) {
        if (state.getBlock() == Blocks.WATER || !state.getFluidState().isEmpty() && state.liquid()) {
            return state.getBlock() == Blocks.LAVA ? argb(220, 255, 96, 20) : argb(190, 55, 130, 255);
        }
        if (state.getBlock() == Blocks.LAVA) {
            return argb(230, 255, 96, 20);
        }
        return argb(255, 190, 190, 190);
    }

    private static int shadeColor(int argb, Direction direction, boolean translucent) {
        float shade = switch (direction) {
            case UP -> 1.00F;
            case DOWN -> 0.52F;
            case NORTH, SOUTH -> 0.78F;
            case WEST, EAST -> 0.66F;
        };
        int alpha = argb >>> 24;
        if (translucent) {
            alpha = Math.min(alpha, 210);
        }
        int red = Math.min(255, Math.max(0, Math.round(((argb >> 16) & 255) * shade)));
        int green = Math.min(255, Math.max(0, Math.round(((argb >> 8) & 255) * shade)));
        int blue = Math.min(255, Math.max(0, Math.round((argb & 255) * shade)));
        return argb(alpha, red, green, blue);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | (blue & 255);
    }

    private static List<NativeSectionMesh> snapshotMeshesForFrame(ClientLevel level, CubePos playerCube) {
        ArrayList<Map.Entry<CubePos, NativeSectionMesh>> candidates = new ArrayList<>(MESHES.size());
        for (Map.Entry<CubePos, NativeSectionMesh> entry : MESHES.entrySet()) {
            if (isOutsideVanillaShell(level, entry.getKey()) && isWithinScanRadius(entry.getKey(), playerCube)) {
                candidates.add(entry);
            }
        }
        candidates.sort((first, second) -> Integer.compare(meshPriority(first.getKey(), playerCube), meshPriority(second.getKey(), playerCube)));

        ArrayList<NativeSectionMesh> result = new ArrayList<>(Math.min(candidates.size(), MAX_RENDER_MESHES_PER_FRAME));
        int quads = 0;
        for (Map.Entry<CubePos, NativeSectionMesh> entry : candidates) {
            if (result.size() >= MAX_RENDER_MESHES_PER_FRAME) {
                RuntimeProfiler.addCount("client.native_mesh_frame_section_cap_hits", 1);
                break;
            }
            NativeSectionMesh mesh = entry.getValue();
            int meshQuads = mesh.quadCount();
            if (!result.isEmpty() && quads + meshQuads > MAX_RENDER_QUADS_PER_FRAME) {
                RuntimeProfiler.addCount("client.native_mesh_frame_quad_cap_hits", 1);
                break;
            }
            result.add(mesh);
            quads += meshQuads;
        }
        return result;
    }

    private static boolean isOutsideVanillaShell(ClientLevel level, CubePos cubePos) {
        return cubePos.maxBlockY() < level.getMinY() || cubePos.minBlockY() >= level.getMinY() + level.getHeight();
    }

    private static boolean isWithinScanRadius(CubePos cubePos, CubePos playerCube, ClientCubeSectionSnapshot snapshot) {
        int horizontal = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        if (horizontal > HORIZONTAL_SCAN_RADIUS) {
            return false;
        }
        if (Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_SCAN_RADIUS) {
            return true;
        }
        if (SURFACE_PROJECTION_MESH_ENABLED && snapshot != null && snapshot.hasVisibleBlocks()) {
            RuntimeProfiler.addCount("client.surface_projection_native_mesh_accept", 1);
            return true;
        }
        return false;
    }

    private static boolean isWithinScanRadius(CubePos cubePos, CubePos playerCube) {
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= HORIZONTAL_SCAN_RADIUS
                && Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_SCAN_RADIUS;
    }

    private static int meshPriority(CubePos cubePos, CubePos playerCube) {
        int dx = cubePos.x() - playerCube.x();
        int dz = cubePos.z() - playerCube.z();
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        return horizontal * 64 + dy * 16 + dx * dx + dz * dz;
    }

    private static void trimMeshes() {
        while (MESHES.size() > MAX_TRACKED_MESHES) {
            CubePos first = MESHES.keySet().iterator().next();
            MESHES.remove(first);
            RuntimeProfiler.addCount("client.native_mesh_evictions", 1);
        }
    }

    private static void updateReadyStats(ClientLevel level, CubePos playerCube) {
        int ready = 0;
        for (CubePos cubePos : MESHES.keySet()) {
            if (isOutsideVanillaShell(level, cubePos) && isWithinScanRadius(cubePos, playerCube)) {
                ready++;
            }
        }
        readyAroundPlayer = ready;
    }

    private static long elapsedMicrosSince(long startNanos) {
        return Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
    }

    public static String statusLine() {
        return "enabled=" + enabled
                + " mode=vanilla-backed"
                + " meshes=" + meshCount
                + " ready=" + readyAroundPlayer
                + " queue=" + queueSize
                + " rendered=" + renderedMeshesLastFrame
                + " quads=" + solidQuadsLastFrame + "/" + translucentQuadsLastFrame
                + " caps=" + MAX_RENDER_MESHES_PER_FRAME + "/" + MAX_RENDER_QUADS_PER_FRAME
                + " built=" + sectionsBuilt
                + " modelFail=" + modelFailures
                + " reset=" + lastResetReason;
    }

    public static long scans() { return scans; }
    public static int queueSize() { return queueSize; }
    public static int meshCount() { return meshCount; }
    public static int blocksLastTick() { return blocksLastTick; }
    public static int readyAroundPlayer() { return readyAroundPlayer; }
    public static int renderedMeshesLastFrame() { return renderedMeshesLastFrame; }
    public static int solidFacesLastFrame() { return solidQuadsLastFrame; }
    public static int translucentFacesLastFrame() { return translucentQuadsLastFrame; }
    public static long renderSubmits() { return renderSubmits; }
    public static long tasksQueued() { return tasksQueued; }
    public static long sectionsBuilt() { return sectionsBuilt; }
    public static long blocksScanned() { return blocksScanned; }
    public static long solidBlocks() { return solidBlocks; }
    public static long facesEstimated() { return vanillaQuadsBuilt + fluidQuadsBuilt + fallbackFacesBuilt; }
    public static long solidFacesBuilt() { return vanillaQuadsBuilt + fallbackFacesBuilt; }
    public static long translucentFacesBuilt() { return fluidQuadsBuilt; }
    public static long hashHits() { return hashHits; }
    public static long invalidations() { return invalidations; }
    public static long budgetHits() { return budgetHits; }
    public static long timeBudgetHits() { return timeBudgetHits; }
    public static long renderSkippedInsideShell() { return renderSkippedInsideShell; }

    private static final class MeshBuildTask {
        private final long hash;
        private int cursor;
        private int solidBlocks;
        private boolean quadLimitHit;
        private BlockState currentState;
        private BlockPos currentPos;
        private final ArrayList<NativeRenderableQuad> solidQuads = new ArrayList<>(1024);
        private final ArrayList<NativeRenderableQuad> cutoutQuads = new ArrayList<>(256);
        private final ArrayList<NativeRenderableQuad> translucentQuads = new ArrayList<>(128);
        private final ArrayList<NativeFallbackFace> fallbackFaces = new ArrayList<>(64);
        private final ArrayList<NativeFallbackFace> translucentFallbackFaces = new ArrayList<>(16);
        private final BlockQuadOutput blockOutput = this::putBlockQuad;
        private final FluidRenderer.Output fluidOutput = layer -> new CollectingVertexConsumer(this, layer);

        private MeshBuildTask(long hash) {
            this.hash = hash;
        }

        private int quadCount() {
            return solidQuads.size() + cutoutQuads.size() + translucentQuads.size() + fallbackFaces.size() + translucentFallbackFaces.size();
        }

        private void putBlockQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            if (currentPos == null) {
                return;
            }
            NativeBakedQuad nativeQuad = NativeBakedQuad.copy(currentPos.getX() + x, currentPos.getY() + y, currentPos.getZ() + z, quad, instance);
            ChunkSectionLayer layer = quad.materialInfo().layer();
            if (layer == ChunkSectionLayer.TRANSLUCENT || quad.materialInfo().sprite().transparency().hasTranslucent()) {
                translucentQuads.add(nativeQuad);
            } else if (layer == ChunkSectionLayer.CUTOUT) {
                cutoutQuads.add(nativeQuad);
            } else {
                solidQuads.add(nativeQuad);
            }
            vanillaQuadsBuilt++;
            if (quadCount() >= MAX_QUADS_PER_SECTION) {
                quadLimitHit = true;
            }
        }

        private NativeSectionMesh toMesh(long hash) {
            return new NativeSectionMesh(hash, solidBlocks, List.copyOf(solidQuads), List.copyOf(cutoutQuads),
                    List.copyOf(translucentQuads), List.copyOf(fallbackFaces), List.copyOf(translucentFallbackFaces));
        }
    }

    private record NativeSectionMesh(long hash, int solidBlocks, List<NativeRenderableQuad> solidQuads,
                                     List<NativeRenderableQuad> cutoutQuads, List<NativeRenderableQuad> translucentQuads,
                                     List<NativeFallbackFace> fallbackFaces, List<NativeFallbackFace> translucentFallbackFaces) {
        private int quadCount() {
            return solidQuads.size() + cutoutQuads.size() + translucentQuads.size() + fallbackFaces.size() + translucentFallbackFaces.size();
        }

        private void emitSolid(PoseStack.Pose pose, VertexConsumer consumer) {
            for (NativeRenderableQuad quad : solidQuads) {
                quad.emit(pose, consumer);
            }
            for (NativeFallbackFace face : fallbackFaces) {
                face.emit(pose, consumer);
            }
        }

        private void emitCutout(PoseStack.Pose pose, VertexConsumer consumer) {
            for (NativeRenderableQuad quad : cutoutQuads) {
                quad.emit(pose, consumer);
            }
        }

        private void emitTranslucent(PoseStack.Pose pose, VertexConsumer consumer) {
            for (NativeRenderableQuad quad : translucentQuads) {
                quad.emit(pose, consumer);
            }
            for (NativeFallbackFace face : translucentFallbackFaces) {
                face.emit(pose, consumer);
            }
        }
    }

    private interface NativeRenderableQuad {
        void emit(PoseStack.Pose pose, VertexConsumer consumer);
    }

    private record NativeBakedQuad(float x, float y, float z, BakedQuad quad, int color0, int color1, int color2, int color3,
                                   int light0, int light1, int light2, int light3, int overlay) implements NativeRenderableQuad {
        private static NativeBakedQuad copy(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            return new NativeBakedQuad(x, y, z, quad,
                    forceVisibleAlpha(instance.getColor(0)), forceVisibleAlpha(instance.getColor(1)), forceVisibleAlpha(instance.getColor(2)), forceVisibleAlpha(instance.getColor(3)),
                    instance.getLightCoordsWithEmission(0, quad.materialInfo().lightEmission()),
                    instance.getLightCoordsWithEmission(1, quad.materialInfo().lightEmission()),
                    instance.getLightCoordsWithEmission(2, quad.materialInfo().lightEmission()),
                    instance.getLightCoordsWithEmission(3, quad.materialInfo().lightEmission()),
                    instance.overlayCoords());
        }

        @Override
        public void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            Vector3fc normal = quad.direction().getUnitVec3f();
            emitVertex(pose, consumer, 0, color0, light0, normal);
            emitVertex(pose, consumer, 1, color1, light1, normal);
            emitVertex(pose, consumer, 2, color2, light2, normal);
            emitVertex(pose, consumer, 3, color3, light3, normal);
        }

        private static int forceVisibleAlpha(int color) {
            return (color & 0xFF000000) == 0 ? (color | 0xFF000000) : color;
        }

        private void emitVertex(PoseStack.Pose pose, VertexConsumer consumer, int index, int color, int light, Vector3fc normal) {
            Vector3fc position = quad.position(index);
            long uv = quad.packedUV(index);
            consumer.addVertex(pose, x + position.x(), y + position.y(), z + position.z())
                    .setColor(color)
                    .setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, normal.x(), normal.y(), normal.z());
        }
    }

    private static final class CollectingVertexConsumer implements VertexConsumer {
        private final MeshBuildTask task;
        private final ChunkSectionLayer layer;
        private final ArrayList<NativeVertex> pending = new ArrayList<>(4);
        private float x;
        private float y;
        private float z;
        private int color = 0xFFFFFFFF;
        private float u;
        private float v;
        private int overlay;
        private int light = FULL_BRIGHT;
        private float nx;
        private float ny = 1.0F;
        private float nz;

        private CollectingVertexConsumer(MeshBuildTask task, ChunkSectionLayer layer) {
            this.task = task;
            this.layer = layer;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            // FluidRenderer emits coordinates local to the 16x16x16 render section (pos & 15), while our native renderer
            // stores world-space meshes.  Until M19.8.5 these local vertices were kept as-is, so water at Y=9000 was
            // actually submitted near Y=8/9 and became invisible from the real camera.
            BlockPos pos = task.currentPos;
            if (pos != null) {
                this.x = (pos.getX() & ~CubePos.MASK) + x;
                this.y = (pos.getY() & ~CubePos.MASK) + y;
                this.z = (pos.getZ() & ~CubePos.MASK) + z;
            } else {
                this.x = x;
                this.y = y;
                this.z = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.color = (alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | (blue & 255);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            // Some vanilla fluid/model paths pass RGB tint without an alpha byte.  VertexConsumer color is later emitted
            // as packed ARGB; leaving alpha at zero made water/lava meshes fully invisible outside the shell.
            this.color = (color & 0xFF000000) == 0 ? (color | 0xFF000000) : color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.overlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.light = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            NativeVertex vertex = new NativeVertex(this.x, this.y, this.z, color, this.u, this.v, overlay, light, nx, ny, nz);
            pending.add(vertex);
            if (pending.size() == 4) {
                NativeFluidQuad quad = new NativeFluidQuad(pending.get(0), pending.get(1), pending.get(2), pending.get(3));
                if (layer == ChunkSectionLayer.TRANSLUCENT) {
                    task.translucentQuads.add(quad);
                } else if (layer == ChunkSectionLayer.CUTOUT) {
                    task.cutoutQuads.add(quad);
                } else {
                    task.solidQuads.add(quad);
                }
                pending.clear();
                fluidQuadsBuilt++;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private record NativeFluidQuad(NativeVertex a, NativeVertex b, NativeVertex c, NativeVertex d) implements NativeRenderableQuad {
        @Override
        public void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            emit(pose, consumer, a);
            emit(pose, consumer, b);
            emit(pose, consumer, c);
            emit(pose, consumer, d);
        }

        private void emit(PoseStack.Pose pose, VertexConsumer consumer, NativeVertex vertex) {
            consumer.addVertex(pose, vertex.x, vertex.y, vertex.z)
                    .setColor(vertex.color)
                    .setUv(vertex.u, vertex.v)
                    .setOverlay(vertex.overlay)
                    .setLight(vertex.light)
                    .setNormal(pose, vertex.nx, vertex.ny, vertex.nz);
        }
    }

    private record NativeVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
    }

    private record NativeFallbackFace(int x, int y, int z, Direction direction, int argb) {
        private void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            float x0 = x;
            float y0 = y;
            float z0 = z;
            float x1 = x + 1.0F;
            float y1 = y + 1.0F;
            float z1 = z + 1.0F;
            switch (direction) {
                case UP -> quad(pose, consumer, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0.0F, 1.0F, 0.0F);
                case DOWN -> quad(pose, consumer, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0.0F, -1.0F, 0.0F);
                case NORTH -> quad(pose, consumer, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0.0F, 0.0F, -1.0F);
                case SOUTH -> quad(pose, consumer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0.0F, 0.0F, 1.0F);
                case WEST -> quad(pose, consumer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1.0F, 0.0F, 0.0F);
                case EAST -> quad(pose, consumer, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1.0F, 0.0F, 0.0F);
            }
        }

        private void quad(PoseStack.Pose pose, VertexConsumer consumer,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz,
                          float nx, float ny, float nz) {
            // Fallback is color/debug geometry, not texture geometry.  Keep UV pinned to avoid sampling the whole block atlas.
            vertex(pose, consumer, ax, ay, az, 0.0F, 0.0F, nx, ny, nz);
            vertex(pose, consumer, bx, by, bz, 0.0F, 0.0F, nx, ny, nz);
            vertex(pose, consumer, cx, cy, cz, 0.0F, 0.0F, nx, ny, nz);
            vertex(pose, consumer, dx, dy, dz, 0.0F, 0.0F, nx, ny, nz);
        }

        private void vertex(PoseStack.Pose pose, VertexConsumer consumer, float vx, float vy, float vz, float u, float v, float nx, float ny, float nz) {
            consumer.addVertex(pose, vx, vy, vz)
                    .setColor((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255)
                    .setUv(u, v)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    private static final class CubeRenderView implements BlockAndTintGetter {
        private final ClientLevel delegate;
        private final Map<CubePos, ClientCubeSectionSnapshot> sections;
        private final CubicDimensionSettings settings = CubicDimensionSettings.defaults();

        private CubeRenderView(ClientLevel delegate, Map<CubePos, ClientCubeSectionSnapshot> sections) {
            this.delegate = delegate;
            this.sections = sections;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return delegate.cardinalLighting();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            try {
                return delegate.getBlockTint(pos, resolver);
            } catch (RuntimeException ignored) {
                return 0xFFFFFF;
            }
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return delegate.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer lightLayer, BlockPos pos) {
            if (!settings.containsBlockY(pos.getY())) {
                return 0;
            }
            if (!settings.isBlockInsideVanillaShell(pos.getY())) {
                return lightLayer == LightLayer.SKY ? 15 : getBlockState(pos).getLightEmission();
            }
            return BlockAndTintGetter.super.getBrightness(lightLayer, pos);
        }

        @Override
        public int getRawBrightness(BlockPos pos, int amount) {
            if (!settings.containsBlockY(pos.getY())) {
                return 0;
            }
            if (!settings.isBlockInsideVanillaShell(pos.getY())) {
                return Math.max(0, 15 - amount);
            }
            return BlockAndTintGetter.super.getRawBrightness(pos, amount);
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            if (!settings.isBlockInsideVanillaShell(pos.getY())) {
                return true;
            }
            return BlockAndTintGetter.super.canSeeSky(pos);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!settings.containsBlockY(pos.getY())) {
                return Blocks.AIR.defaultBlockState();
            }
            CubePos cubePos = CubePos.fromBlock(pos);
            ClientCubeSectionSnapshot snapshot = sections.get(cubePos);
            if (snapshot != null) {
                return snapshot.blockStateAtLocalIndex(CubePos.localIndexFromBlock(pos.getX(), pos.getY(), pos.getZ()));
            }
            if (settings.isBlockInsideVanillaShell(pos.getY())) {
                try {
                    return delegate.getBlockState(pos);
                } catch (RuntimeException ignored) {
                    return Blocks.AIR.defaultBlockState();
                }
            }
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return settings.blockHeight();
        }

        @Override
        public int getMinY() {
            return settings.minBlockY();
        }
    }
}

package com.ibicza.redlinetech.datagen;

import com.google.common.hash.Hashing;
import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.block.MiningTier;
import com.ibicza.redlinetech.content.block.MiningTool;
import com.ibicza.redlinetech.content.material.RegisteredMaterialBlock;
import com.ibicza.redlinetech.content.material.RegisteredMaterialItem;
import com.ibicza.redlinetech.content.ore.OreLikeDefinition;
import com.ibicza.redlinetech.content.ore.RegisteredOreBlock;
import com.ibicza.redlinetech.registry.ModBlocks;
import com.ibicza.redlinetech.registry.ModItems;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
public final class RedlineGeneratedResourcesProvider implements DataProvider {
    private final Path root;

    public RedlineGeneratedResourcesProvider(PackOutput output) {
        this.root = output.getOutputFolder();
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        return CompletableFuture.runAsync(() -> {
            try {
                generateLang(cachedOutput, "ru_ru");
                generateLang(cachedOutput, "en_us");

                generateOreResources(cachedOutput);
                generateMaterialItemResources(cachedOutput);
                generateMaterialBlockResources(cachedOutput);

                generateOreTextures(cachedOutput);
                generateMaterialItemTextures(cachedOutput);
                generateMaterialBlockTextures(cachedOutput);

                generateTags(cachedOutput);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    @Override
    public String getName() {
        return "Redline Tech generated resources";
    }

    private void generateOreResources(CachedOutput cachedOutput) throws IOException {
        for (RegisteredOreBlock oreBlock : ModBlocks.ORE_BLOCKS) {
            String blockId = oreBlock.blockId();

            writeJson(cachedOutput, assets("blockstates/" + blockId + ".json"), blockStateJson(blockId));
            writeJson(cachedOutput, assets("models/block/" + blockId + ".json"), cubeAllBlockModelJson(blockId));
            writeJson(cachedOutput, assets("models/item/" + blockId + ".json"), blockItemModelJson(blockId));
            writeJson(cachedOutput, assets("items/" + blockId + ".json"), clientItemJson(blockId));

            writeJson(
                    cachedOutput,
                    data(RedlineTech.MOD_ID + "/loot_table/blocks/" + blockId + ".json"),
                    selfDropLootTableJson(blockId)
            );
        }
    }

    private void generateMaterialItemResources(CachedOutput cachedOutput) throws IOException {
        for (RegisteredMaterialItem materialItem : ModItems.MATERIAL_ITEMS) {
            String itemId = materialItem.itemId();

            writeJson(cachedOutput, assets("models/item/" + itemId + ".json"), generatedItemModelJson(itemId));
            writeJson(cachedOutput, assets("items/" + itemId + ".json"), clientItemJson(itemId));
        }
    }

    private void generateMaterialBlockResources(CachedOutput cachedOutput) throws IOException {
        for (RegisteredMaterialBlock materialBlock : ModBlocks.MATERIAL_BLOCKS) {
            String blockId = materialBlock.blockId();

            writeJson(cachedOutput, assets("blockstates/" + blockId + ".json"), blockStateJson(blockId));
            writeJson(cachedOutput, assets("models/block/" + blockId + ".json"), cubeAllBlockModelJson(blockId));
            writeJson(cachedOutput, assets("models/item/" + blockId + ".json"), blockItemModelJson(blockId));
            writeJson(cachedOutput, assets("items/" + blockId + ".json"), clientItemJson(blockId));

            writeJson(
                    cachedOutput,
                    data(RedlineTech.MOD_ID + "/loot_table/blocks/" + blockId + ".json"),
                    selfDropLootTableJson(blockId)
            );
        }
    }

    private void generateLang(CachedOutput cachedOutput, String locale) throws IOException {
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append("  \"mod.redline_tech.name\": \"Redline Tech\",\n");
        json.append("  \"itemGroup.redline_tech.main\": \"Redline Tech\"");

        for (RegisteredOreBlock oreBlock : ModBlocks.ORE_BLOCKS) {
            appendLang(
                    json,
                    "block." + RedlineTech.MOD_ID + "." + oreBlock.blockId(),
                    oreName(oreBlock, locale)
            );
        }

        for (RegisteredMaterialItem materialItem : ModItems.MATERIAL_ITEMS) {
            appendLang(
                    json,
                    "item." + RedlineTech.MOD_ID + "." + materialItem.itemId(),
                    materialItemName(materialItem, locale)
            );
        }

        for (RegisteredMaterialBlock materialBlock : ModBlocks.MATERIAL_BLOCKS) {
            appendLang(
                    json,
                    "block." + RedlineTech.MOD_ID + "." + materialBlock.blockId(),
                    materialBlockName(materialBlock, locale)
            );
        }

        json.append("\n}\n");

        writeJson(cachedOutput, assets("lang/" + locale + ".json"), json.toString());
    }

    private static void appendLang(StringBuilder json, String key, String value) {
        json.append(",\n");
        json.append("  \"")
                .append(escapeJson(key))
                .append("\": \"")
                .append(escapeJson(value))
                .append("\"");
    }

    private static String oreName(RegisteredOreBlock oreBlock, String locale) {
        OreLikeDefinition definition = oreBlock.definition();

        if ("ru_ru".equals(locale)) {
            return switch (oreBlock.variant()) {
                case STONE -> definition.ruName();
                case DEEPSLATE -> "Глубинная " + lowerFirst(definition.ruName());
            };
        }

        return switch (oreBlock.variant()) {
            case STONE -> definition.enName();
            case DEEPSLATE -> "Deepslate " + definition.enName();
        };
    }

    private static String materialItemName(RegisteredMaterialItem item, String locale) {
        if ("ru_ru".equals(locale)) {
            return item.form().ruPrefix() + ": " + item.material().ruName();
        }

        return item.material().enName() + " " + item.form().enSuffix();
    }

    private static String materialBlockName(RegisteredMaterialBlock block, String locale) {
        if ("ru_ru".equals(locale)) {
            return "Блок: " + block.material().ruName();
        }

        return block.material().enName() + " Block";
    }

    private void generateOreTextures(CachedOutput cachedOutput) throws IOException {
        BufferedImage stoneBase = readTemplateImage("redline_templates/ore/stone_base.png");
        BufferedImage deepslateBase = readTemplateImage("redline_templates/ore/deepslate_base.png");

        for (RegisteredOreBlock oreBlock : ModBlocks.ORE_BLOCKS) {
            OreLikeDefinition definition = oreBlock.definition();

            BufferedImage base = switch (oreBlock.variant()) {
                case STONE -> stoneBase;
                case DEEPSLATE -> deepslateBase;
            };

            String overlayPath = textureIdToTemplatePath(definition.overlayTexture());
            BufferedImage overlayTemplate = readTemplateImage(overlayPath);
            BufferedImage tintedOverlay = tintGrayscale(overlayTemplate, definition.color());
            BufferedImage result = overlay(base, tintedOverlay);

            writePng(cachedOutput, assets("textures/block/" + oreBlock.blockId() + ".png"), result);
        }
    }

    private void generateMaterialItemTextures(CachedOutput cachedOutput) throws IOException {
        for (RegisteredMaterialItem materialItem : ModItems.MATERIAL_ITEMS) {
            BufferedImage template = readTemplateImage(
                    "redline_templates/material/" + materialItem.form().templateFile()
            );

            BufferedImage result = tintGrayscale(template, materialItem.material().color());

            writePng(cachedOutput, assets("textures/item/" + materialItem.itemId() + ".png"), result);
        }
    }

    private void generateMaterialBlockTextures(CachedOutput cachedOutput) throws IOException {
        for (RegisteredMaterialBlock materialBlock : ModBlocks.MATERIAL_BLOCKS) {
            BufferedImage template = readTemplateImage("redline_templates/material/block_template.png");
            BufferedImage result = tintGrayscale(template, materialBlock.material().color());

            writePng(cachedOutput, assets("textures/block/" + materialBlock.blockId() + ".png"), result);
        }
    }

    private void generateTags(CachedOutput cachedOutput) throws IOException {
        Map<MiningTool, List<String>> mineableTags = new EnumMap<>(MiningTool.class);
        Map<MiningTier, List<String>> tierTags = new EnumMap<>(MiningTier.class);

        for (RegisteredOreBlock oreBlock : ModBlocks.ORE_BLOCKS) {
            String value = RedlineTech.MOD_ID + ":" + oreBlock.blockId();

            addTagValue(mineableTags, oreBlock.definition().tool(), value);
            addTagValue(tierTags, oreBlock.definition().tier(), value);
        }

        for (RegisteredMaterialBlock materialBlock : ModBlocks.MATERIAL_BLOCKS) {
            String value = RedlineTech.MOD_ID + ":" + materialBlock.blockId();

            addTagValue(mineableTags, MiningTool.PICKAXE, value);
            addTagValue(tierTags, MiningTier.STONE, value);
        }

        for (Map.Entry<MiningTool, List<String>> entry : mineableTags.entrySet()) {
            String path = switch (entry.getKey()) {
                case PICKAXE -> "minecraft/tags/block/mineable/pickaxe.json";
                case AXE -> "minecraft/tags/block/mineable/axe.json";
                case SHOVEL -> "minecraft/tags/block/mineable/shovel.json";
                case HOE -> "minecraft/tags/block/mineable/hoe.json";
                case NONE -> null;
            };

            if (path != null) {
                writeJson(cachedOutput, data(path), tagJson(entry.getValue()));
            }
        }

        for (Map.Entry<MiningTier, List<String>> entry : tierTags.entrySet()) {
            String path = switch (entry.getKey()) {
                case STONE -> "minecraft/tags/block/needs_stone_tool.json";
                case IRON -> "minecraft/tags/block/needs_iron_tool.json";
                case DIAMOND -> "minecraft/tags/block/needs_diamond_tool.json";
                case NONE, WOOD -> null;
            };

            if (path != null) {
                writeJson(cachedOutput, data(path), tagJson(entry.getValue()));
            }
        }
    }

    private static void addTagValue(Map<MiningTool, List<String>> tags, MiningTool tool, String value) {
        if (tool == MiningTool.NONE) {
            return;
        }

        tags.computeIfAbsent(tool, ignored -> new ArrayList<>()).add(value);
    }

    private static void addTagValue(Map<MiningTier, List<String>> tags, MiningTier tier, String value) {
        if (tier == MiningTier.NONE || tier == MiningTier.WOOD) {
            return;
        }

        tags.computeIfAbsent(tier, ignored -> new ArrayList<>()).add(value);
    }

    private Path assets(String relativePath) {
        return root.resolve("assets").resolve(RedlineTech.MOD_ID).resolve(relativePath);
    }

    private Path data(String relativePath) {
        return root.resolve("data").resolve(relativePath);
    }

    private static String blockStateJson(String blockId) {
        return """
                {
                  "variants": {
                    "": {
                      "model": "redline_tech:block/%s"
                    }
                  }
                }
                """.formatted(blockId);
    }

    private static String cubeAllBlockModelJson(String blockId) {
        return """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": {
                    "all": "redline_tech:block/%s"
                  }
                }
                """.formatted(blockId);
    }

    private static String blockItemModelJson(String blockId) {
        return """
                {
                  "parent": "redline_tech:block/%s"
                }
                """.formatted(blockId);
    }

    private static String generatedItemModelJson(String itemId) {
        return """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {
                    "layer0": "redline_tech:item/%s"
                  }
                }
                """.formatted(itemId);
    }

    private static String clientItemJson(String itemId) {
        return """
                {
                  "model": {
                    "type": "minecraft:model",
                    "model": "redline_tech:item/%s"
                  }
                }
                """.formatted(itemId);
    }

    private static String selfDropLootTableJson(String blockId) {
        return """
                {
                  "type": "minecraft:block",
                  "pools": [
                    {
                      "rolls": 1,
                      "entries": [
                        {
                          "type": "minecraft:item",
                          "name": "redline_tech:%s"
                        }
                      ],
                      "conditions": [
                        {
                          "condition": "minecraft:survives_explosion"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(blockId);
    }

    private static String tagJson(List<String> values) {
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append("  \"replace\": false,\n");
        json.append("  \"values\": [\n");

        for (int i = 0; i < values.size(); i++) {
            json.append("    \"").append(escapeJson(values.get(i))).append("\"");

            if (i < values.size() - 1) {
                json.append(",");
            }

            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        return json.toString();
    }

    private static void writeJson(CachedOutput cachedOutput, Path path, String content) throws IOException {
        writeText(cachedOutput, path, content);
    }

    private static void writeText(CachedOutput cachedOutput, Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(path.getParent());
        cachedOutput.writeIfNeeded(path, bytes, Hashing.sha1().hashBytes(bytes));
    }

    private static void writePng(CachedOutput cachedOutput, Path path, BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer available for: " + path);
        }

        byte[] bytes = output.toByteArray();

        Files.createDirectories(path.getParent());
        cachedOutput.writeIfNeeded(path, bytes, Hashing.sha1().hashBytes(bytes));
    }

    private static BufferedImage readTemplateImage(String resourcePath) throws IOException {
        ClassLoader classLoader = RedlineGeneratedResourcesProvider.class.getClassLoader();

        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Template image not found: " + resourcePath);
            }

            BufferedImage image = ImageIO.read(input);

            if (image == null) {
                throw new IOException("Failed to decode template image: " + resourcePath);
            }

            return image;
        }
    }

    private static String textureIdToTemplatePath(String textureId) {
        String prefix = RedlineTech.MOD_ID + ":template/";

        if (!textureId.startsWith(prefix)) {
            throw new IllegalStateException(
                    "Only redline_tech:template/... texture ids are supported by this generator now. Got: "
                            + textureId
            );
        }

        String path = textureId.substring(prefix.length());

        return "redline_templates/" + path + ".png";
    }

    private static BufferedImage tintGrayscale(BufferedImage template, int rgbColor) {
        int width = template.getWidth();
        int height = template.getHeight();

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int baseR = (rgbColor >> 16) & 0xFF;
        int baseG = (rgbColor >> 8) & 0xFF;
        int baseB = rgbColor & 0xFF;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = template.getRGB(x, y);

                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;

                int brightness = (red + green + blue) / 3;

                int outR = clamp(baseR * brightness / 255);
                int outG = clamp(baseG * brightness / 255);
                int outB = clamp(baseB * brightness / 255);

                int outArgb = (alpha << 24) | (outR << 16) | (outG << 8) | outB;

                result.setRGB(x, y, outArgb);
            }
        }

        return result;
    }

    private static BufferedImage overlay(BufferedImage base, BufferedImage overlay) {
        if (base.getWidth() != overlay.getWidth() || base.getHeight() != overlay.getHeight()) {
            throw new IllegalStateException(
                    "Base and overlay textures must have same size. Base: "
                            + base.getWidth()
                            + "x"
                            + base.getHeight()
                            + ", overlay: "
                            + overlay.getWidth()
                            + "x"
                            + overlay.getHeight()
            );
        }

        BufferedImage result = new BufferedImage(
                base.getWidth(),
                base.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(base, 0, 0, null);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.drawImage(overlay, 0, 0, null);
        graphics.dispose();

        return result;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        return value.substring(0, 1).toLowerCase() + value.substring(1);
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
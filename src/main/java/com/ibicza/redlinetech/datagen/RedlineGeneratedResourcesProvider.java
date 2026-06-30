package com.ibicza.redlinetech.datagen;

import com.google.common.hash.Hashing;
import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.block.MiningTier;
import com.ibicza.redlinetech.content.block.MiningTool;
import com.ibicza.redlinetech.content.liquid.RegisteredLiquid;
import com.ibicza.redlinetech.content.material.RegisteredMaterialBlock;
import com.ibicza.redlinetech.content.material.RegisteredMaterialItem;
import com.ibicza.redlinetech.content.ore.OreLikeDefinition;
import com.ibicza.redlinetech.content.ore.RegisteredOreBlock;
import com.ibicza.redlinetech.registry.ModBlocks;
import com.ibicza.redlinetech.registry.ModItems;
import com.ibicza.redlinetech.registry.ModLiquids;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import com.ibicza.redlinetech.content.gas.GasRenderMode;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModGases;

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
import com.google.gson.JsonObject;

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

                generateLiquidResources(cachedOutput);
                generateLiquidTextures(cachedOutput);
                generateLiquidTextureMetadata(cachedOutput);

                generateGasResources(cachedOutput);
                generateGasTextures(cachedOutput);

                generateGasCapsuleResources(cachedOutput);
                generateGasCapsuleTexture(cachedOutput);


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

    private void generateLiquidTextureMetadata(CachedOutput cachedOutput) throws IOException {
        for (RegisteredLiquid liquid : ModLiquids.LIQUIDS) {
            writeString(
                    cachedOutput,
                    assets("textures/block/fluid/" + liquid.id() + "_still.png.mcmeta"),
                    liquidAnimationMetadataJson()
            );

            writeString(
                    cachedOutput,
                    assets("textures/block/fluid/" + liquid.id() + "_flow.png.mcmeta"),
                    liquidAnimationMetadataJson()
            );
        }
    }

    private static void writeString(CachedOutput cachedOutput, Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        cachedOutput.writeIfNeeded(path, bytes, Hashing.sha1().hashBytes(bytes));
    }

    private static String liquidAnimationMetadataJson() {
        return """
            {
              "animation": {
                "frametime": 2,
                "interpolate": true
              }
            }
            """;
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

        for (RegisteredLiquid liquid : ModLiquids.LIQUIDS) {
            appendLang(
                    json,
                    "fluid_type." + RedlineTech.MOD_ID + "." + liquid.id(),
                    liquidName(liquid, locale)
            );

            appendLang(
                    json,
                    "item." + RedlineTech.MOD_ID + "." + liquid.bucketItemId(),
                    liquidBucketName(liquid, locale)
            );

            appendLang(
                    json,
                    "block." + RedlineTech.MOD_ID + "." + liquid.id(),
                    liquidName(liquid, locale)
            );
        }

        for (RegisteredGas gas : ModGases.GASES) {
            appendLang(json, "block." + RedlineTech.MOD_ID + "." + gas.blockId(), gasName(gas, locale));
        }
        appendLang(json, "item." + RedlineTech.MOD_ID + ".gas_capsule", "ru_ru".equals(locale) ? "Газовая капсула" : "Gas Capsule");

        generateEffectLang(json, locale);

        json.append("\n}\n");

        writeJson(cachedOutput, assets("lang/" + locale + ".json"), json.toString());
    }

    private static void generateEffectLang(StringBuilder json, String locale) {
        if ("ru_ru".equals(locale)) {
            appendLang(json, "effect." + RedlineTech.MOD_ID + ".radiation", "Радиация");
            appendLang(json, "effect." + RedlineTech.MOD_ID + ".chemical_burn", "Химический ожог");
            appendLang(json, "effect." + RedlineTech.MOD_ID + ".toxic_exposure", "Токсическое поражение");
            appendLang(json, "effect." + RedlineTech.MOD_ID + ".oil_coated", "Нефтяная плёнка");
            return;
        }

        appendLang(json, "effect." + RedlineTech.MOD_ID + ".radiation", "Radiation");
        appendLang(json, "effect." + RedlineTech.MOD_ID + ".chemical_burn", "Chemical Burn");
        appendLang(json, "effect." + RedlineTech.MOD_ID + ".toxic_exposure", "Toxic Exposure");
        appendLang(json, "effect." + RedlineTech.MOD_ID + ".oil_coated", "Oil Coated");
    }

    private static String liquidName(RegisteredLiquid liquid, String locale) {
        if ("ru_ru".equals(locale)) {
            return liquid.definition().ruName();
        }

        return liquid.definition().enName();
    }

    private static String liquidBucketName(RegisteredLiquid liquid, String locale) {
        if ("ru_ru".equals(locale)) {
            return "Ведро: " + liquid.definition().ruName();
        }

        return liquid.definition().enName() + " Bucket";
    }

    private void generateLiquidResources(CachedOutput cachedOutput) throws IOException {
        for (RegisteredLiquid liquid : ModLiquids.LIQUIDS) {
            String bucketItemId = liquid.bucketItemId();

            writeJson(
                    cachedOutput,
                    assets("models/item/" + bucketItemId + ".json"),
                    generatedItemModelJson(bucketItemId)
            );

            writeJson(
                    cachedOutput,
                    assets("items/" + bucketItemId + ".json"),
                    clientItemJson(bucketItemId)
            );
        }
    }

    private void generateLiquidTextures(CachedOutput cachedOutput) throws IOException {
        BufferedImage stillTemplate = readTemplateImage("redline_templates/liquid/water_still.png");
        BufferedImage flowTemplate = readTemplateImage("redline_templates/liquid/water_flow.png");
        BufferedImage bucketTemplate = readTemplateImage("redline_templates/liquid/water_bucket.png");

        for (RegisteredLiquid liquid : ModLiquids.LIQUIDS) {
            int color = liquid.definition().color();
            int alpha = liquid.definition().alpha();

            BufferedImage still = tintFullTexture(stillTemplate, color, alpha);
            BufferedImage flow = tintFullTexture(flowTemplate, color, alpha);
            BufferedImage bucket = tintWaterBucket(bucketTemplate, color);

            writePng(
                    cachedOutput,
                    assets("textures/block/fluid/" + liquid.id() + "_still.png"),
                    still
            );

            writePng(
                    cachedOutput,
                    assets("textures/block/fluid/" + liquid.id() + "_flow.png"),
                    flow
            );

            writePng(
                    cachedOutput,
                    assets("textures/item/" + liquid.bucketItemId() + ".png"),
                    bucket
            );
        }
    }

    private void generateGasCapsuleResources(CachedOutput cachedOutput) throws IOException {
        writeJson(cachedOutput, assets("models/item/gas_capsule.json"), generatedItemModelJson("gas_capsule"));
        writeJson(cachedOutput, assets("items/gas_capsule.json"), clientItemJson("gas_capsule"));
    }

    private void generateGasCapsuleTexture(CachedOutput cachedOutput) throws IOException {
        BufferedImage template = readTemplateImage("redline_templates/item/gas_capsule_template.png");
        writePng(cachedOutput, assets("textures/item/gas_capsule.png"), template);
    }


    private static String gasBlockModelJson(RegisteredGas gas, int amount) {
        double fromY = 0.0D;
        double toY = 16.0D;

        if (gas.definition().renderMode() == GasRenderMode.FLOOR_LAYER) {
            toY = amount;
        } else if (gas.definition().renderMode() == GasRenderMode.CEILING_LAYER) {
            fromY = 16.0D - amount;
        }

        String texture = RedlineTech.MOD_ID + ":block/gas/" + gas.blockId() + "_" + amount;

        return """
            {
              "render_type": "minecraft:translucent",
              "ambientocclusion": false,
              "textures": {
                "particle": "%1$s",
                "gas": "%1$s"
              },
              "elements": [
                {
                  "from": [0, %2$.3f, 0],
                  "to": [16, %3$.3f, 16],
                  "faces": {
                    "down":  { "texture": "#gas" },
                    "up":    { "texture": "#gas" },
                    "north": { "texture": "#gas" },
                    "south": { "texture": "#gas" },
                    "west":  { "texture": "#gas" },
                    "east":  { "texture": "#gas" }
                  }
                }
              ]
            }
            """.formatted(texture, fromY, toY);
    }

    private static int gasTextureAlpha(RegisteredGas gas, int amount) {
        int maxAmount = Math.max(1, gas.definition().maxAmount());
        int scaled = gas.definition().alpha() * amount / maxAmount;
        return clamp(Math.max(48, scaled));
    }


    private static BufferedImage tintFullTexture(BufferedImage template, int rgbColor, int forcedAlpha) {
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

                int brightness = Math.max(red, Math.max(green, blue));

                int outR = clamp(baseR * brightness / 255);
                int outG = clamp(baseG * brightness / 255);
                int outB = clamp(baseB * brightness / 255);
                int outA = clamp(alpha * forcedAlpha / 255);

                result.setRGB(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);
            }
        }

        return result;
    }

    private static BufferedImage tintWaterBucket(BufferedImage template, int rgbColor) {
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

                if (isVanillaWaterPixel(red, green, blue, alpha)) {
                    int brightness = Math.max(red, Math.max(green, blue));

                    int outR = clamp(baseR * brightness / 255);
                    int outG = clamp(baseG * brightness / 255);
                    int outB = clamp(baseB * brightness / 255);

                    result.setRGB(x, y, (alpha << 24) | (outR << 16) | (outG << 8) | outB);
                } else {
                    result.setRGB(x, y, argb);
                }
            }
        }

        return result;
    }

    private static boolean isVanillaWaterPixel(int red, int green, int blue, int alpha) {
        if (alpha == 0) {
            return false;
        }

        return blue > red + 20 && blue >= green;
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

    private static String gasName(RegisteredGas gas, String locale) {
        if ("ru_ru".equals(locale)) {
            return gas.definition().ruName();
        }

        return gas.definition().enName();
    }

    private void generateGasResources(CachedOutput cachedOutput) throws IOException {
        for (RegisteredGas gas : ModGases.GASES) {
            writeJson(
                    cachedOutput,
                    assets("blockstates/" + gas.blockId() + ".json"),
                    gasBlockStateJson(gas)
            );

            for (int amount = 1; amount <= gas.definition().maxAmount(); amount++) {
                writeJson(
                        cachedOutput,
                        assets("models/block/gas/" + gas.blockId() + "_" + amount + ".json"),
                        gasModelJson(gas, amount)
                );
            }
        }
    }

    private static String gasBlockStateJson(RegisteredGas gas) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"variants\": {\n");

        for (int amount = 1; amount <= gas.definition().maxAmount(); amount++) {
            json.append("    \"amount=").append(amount).append("\": { \"model\": \"")
                    .append(RedlineTech.MOD_ID)
                    .append(":block/gas/")
                    .append(gas.blockId())
                    .append("_")
                    .append(amount)
                    .append("\" }");

            if (amount < gas.definition().maxAmount()) {
                json.append(",");
            }

            json.append("\n");
        }

        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static String gasModelJson(RegisteredGas gas, int amount) {
        double fraction = (double) amount / (double) gas.definition().maxAmount();

        double minY = 0.0D;
        double maxY = 16.0D;

        if (gas.definition().renderMode() == GasRenderMode.FLOOR_LAYER) {
            maxY = Math.max(1.0D, 16.0D * fraction);
        } else if (gas.definition().renderMode() == GasRenderMode.CEILING_LAYER) {
            minY = Math.min(15.0D, 16.0D * (1.0D - fraction));
        }

        String texture = RedlineTech.MOD_ID + ":block/gas/" + gas.blockId() + "_" + amount;

        return """
            {
              "render_type": "minecraft:translucent",
              "ambientocclusion": false,
              "textures": {
                "particle": "%s",
                "gas": "%s"
              },
              "elements": [
                {
                  "from": [0, %.3f, 0],
                  "to": [16, %.3f, 16],
                  "shade": false,
                  "faces": {
                    "down":  { "texture": "#gas" },
                    "up":    { "texture": "#gas" },
                    "north": { "texture": "#gas" },
                    "south": { "texture": "#gas" },
                    "west":  { "texture": "#gas" },
                    "east":  { "texture": "#gas" }
                  }
                }
              ]
            }
            """.formatted(texture, texture, minY, maxY);
    }

    private void generateGasTextures(CachedOutput cachedOutput) throws IOException {
        BufferedImage template = readTemplateImage("redline_templates/gas/gas_template.png");

        for (RegisteredGas gas : ModGases.GASES) {
            for (int amount = 1; amount <= gas.definition().maxAmount(); amount++) {
                BufferedImage result = tintGasTextureForAmount(
                        template,
                        gas.definition().color(),
                        gas.definition().alpha(),
                        amount,
                        gas.definition().maxAmount()
                );

                writePng(
                        cachedOutput,
                        assets("textures/block/gas/" + gas.blockId() + "_" + amount + ".png"),
                        result
                );
            }
        }
    }

    private static BufferedImage tintGasTextureForAmount(
            BufferedImage template,
            int rgbColor,
            int maxAlpha,
            int amount,
            int maxAmount
    ) {
        int width = template.getWidth();
        int height = template.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int baseR = (rgbColor >> 16) & 0xFF;
        int baseG = (rgbColor >> 8) & 0xFF;
        int baseB = rgbColor & 0xFF;

        float amountFactor = Math.max(0.15F, amount / (float) maxAmount);
        int visualAlpha = clamp(Math.round(maxAlpha * amountFactor));
        visualAlpha = Math.max(28, visualAlpha);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = template.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;

                if (alpha == 0) {
                    result.setRGB(x, y, 0x00000000);
                    continue;
                }

                int brightness = Math.max(48, (red + green + blue) / 3);
                int outR = clamp(baseR * brightness / 255 + 24);
                int outG = clamp(baseG * brightness / 255 + 24);
                int outB = clamp(baseB * brightness / 255 + 24);
                int outA = clamp(alpha * visualAlpha / 255);

                result.setRGB(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);
            }
        }

        return result;
    }

}
package com.ibicza.redlinetech.content.material;


public enum MaterialItemForm {
    INGOT("ingot", "Слиток", "Ingot", "ingot_template.png"),
    DUST("dust", "Пыль", "Dust", "dust_template.png"),
    SMALL_DUST("small_dust", "Маленькая кучка пыли", "Small Dust Pile", "small_dust_template.png"),
    PLATE("plate", "Пластина", "Plate", "plate_template.png"),
    DENSE_PLATE("dense_plate", "Толстая пластина", "Dense Plate", "dense_plate_template.png"),
    CASING("casing", "Оболочка", "Casing", "casing_template.png"),
    WIRE("wire", "Проволока", "Wire", "wire_template.png"),
    ROD("rod", "Стержень", "Rod", "rod_template.png"),
    FOIL("foil", "Фольга", "Foil", "foil_template.png"),
    RIBBON("ribbon", "Металлическая лента", "Metal Ribbon", "ribbon_template.png"),
    NUGGET("nugget", "Самородок", "Nugget", "nugget_template.png");

    private final String suffix;
    private final String ruPrefix;
    private final String enSuffix;
    private final String templateFile;

    MaterialItemForm(String suffix, String ruPrefix, String enSuffix, String templateFile) {
        this.suffix = suffix;
        this.ruPrefix = ruPrefix;
        this.enSuffix = enSuffix;
        this.templateFile = templateFile;
    }

    public String suffix() {
        return suffix;
    }

    public String ruPrefix() {
        return ruPrefix;
    }

    public String enSuffix() {
        return enSuffix;
    }

    public String templateFile() {
        return templateFile;
    }
}
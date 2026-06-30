package com.ibicza.redlinetech.content.material;


public enum MaterialItemForm {
    INGOT("ingot", "Слиток", "Ingot", "ingot_template.png"),
    DUST("dust", "Пыль", "Dust", "dust_template.png"),
    PLATE("plate", "Пластина", "Plate", "plate_template.png"),
    WIRE("wire", "Провод", "Wire", "wire_template.png"),
    ROD("rod", "Стержень", "Rod", "rod_template.png"),
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

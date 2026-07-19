package com.pigpurchases.model;

public class PurchaseSource {
    private String name;
    private String type;

    public PurchaseSource() {
    }

    public PurchaseSource(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

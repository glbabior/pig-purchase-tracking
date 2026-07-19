package com.pigpurchases.model;

import jakarta.persistence.*;

/**
 * A place statements are imported from: a friendly name plus the absolute
 * folder path where that account's statement files live. parserRules holds the
 * parsing configuration established during ingest setup; it is stored per source
 * but not surfaced in the UI.
 */
@Entity
@Table(name = "statement_sources")
public class StatementSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(length = 1024)
    private String folderPath;

    @Lob
    private String parserRules;

    public StatementSource() {}

    public StatementSource(String name, String folderPath) {
        this.name = name;
        this.folderPath = folderPath;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getParserRules() {
        return parserRules;
    }

    public void setParserRules(String parserRules) {
        this.parserRules = parserRules;
    }
}

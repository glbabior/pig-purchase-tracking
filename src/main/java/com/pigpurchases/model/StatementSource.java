package com.pigpurchases.model;

import jakarta.persistence.*;

@Entity
@Table(name = "statement_sources")
public class StatementSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountName;
    private String fileType; // CSV, PDF, OFX, etc.
    private String parserConfig; // JSON config for parsing specific file formats

    public StatementSource() {}

    public StatementSource(String accountName, String fileType) {
        this.accountName = accountName;
        this.fileType = fileType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getParserConfig() {
        return parserConfig;
    }

    public void setParserConfig(String parserConfig) {
        this.parserConfig = parserConfig;
    }
}

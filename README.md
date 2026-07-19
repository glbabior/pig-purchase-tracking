# Pig Purchases - Budget Tracking Application

## Overview

Pig Purchases is a desktop budget tracking application for managing monthly budgets and analyzing spending patterns. All monetary data stays local on your PC—no cloud processing of transaction amounts or sensitive financial data.

## Core Requirements

### 1. Budget Management
- **Budget Entry Configuration**: Define budget categories with monthly allowance amounts
- **Quantity Support**: Budget items can have quantities (e.g., "Pharm copays: qty 6 × $10 = $60/month")
- **Budget Hints**: Optional hints field to assist with automatic transaction categorization
- **One-Time Import**: Ability to bulk-import budget entries from spreadsheets

### 2. Monthly Statement Processing & Transaction Tracking
- **Statement File Uploads**: Import credit card and bank statements (CSV, PDF, OFX formats)
- **Statement Source Management**: Maintain a list of file types and account names to track which statements have been imported
- **Automatic Categorization**: Parse transaction descriptions and vendors from statements, then auto-categorize against defined budget entries
- **Privacy-First Categorization**: Send only transaction descriptions and vendor names to AI for categorization—all dollar amounts remain local
- **Transaction Storage**: Store hundreds of transactions per month with:
  - Transaction date
  - Description
  - Vendor name
  - Amount (stored locally, never sent out for categorization)
  - Categorization (which budget entry it maps to)
  - Month (YYYY-MM format for grouping)
  - User notes/edits
- **Category Refinement**: Iteratively improve categorization by refining budget entry hints based on user feedback

### 3. Spend Analysis & Reporting
- **Summary Reporting**: Calculate total budget vs. actual spend with variance and percent used
- **Rolling Averages**: Track rolling average budget and spend across months
- **Per-Item Breakdown**: View spend by budget entry category
- **Monthly Comparison**: Compare spend trends across months
- **Graph/Chart Visualization**: Display spending patterns visually
- **Export Functionality**: Download reports in standard formats

### 4. UI/UX
- **Budget Entries Tab**: Table view with add/edit/delete operations
- **Statement Import Tab**: Manage statement sources and upload files
- **Analysis Tab**: View spend calculations and reports
- **Modal Dialogs**: Inline add/edit forms for budget entries and transactions
- **Edit Buttons**: Per-row edit buttons to modify transaction details and categorization

## Technical Architecture

### Technology Stack
- **Backend**: Spring Boot 3.3.2 (Java 17)
- **Database**: H2 (embedded, file-based, no separate server needed)
- **Frontend**: Vanilla JavaScript + HTML/CSS (not a heavy framework)
- **ORM**: Spring Data JPA with Hibernate
- **Build**: Maven 3.9.9

### Data Storage Strategy

#### Database (H2)
- **Location**: `pig-purchases-db` (file-based, stored locally)
- **Auto-initialization**: Database schema created automatically on first run
- **Data Migration**: Existing flat-file budget data migrated to database on startup

#### Database Schema

**budget_entries** table
```sql
id (Long, auto-increment)
name (String)
monthly_allowance (BigDecimal)
quantity (Integer, default 1)
hints (String, up to 1024 chars)
```

**transactions** table
```sql
id (Long, auto-increment)
transaction_date (LocalDate)
description (String)
vendor (String)
amount (BigDecimal)
month (String, YYYY-MM format)
budget_entry_id (Long, foreign key to budget_entries)
notes (String, up to 1024 chars)
```

**statement_sources** table
```sql
id (Long, auto-increment)
account_name (String)
file_type (String, e.g., CSV, PDF, OFX)
parser_config (String, JSON for parser-specific settings)
```

### API Endpoints

#### Budget Management
- `GET /api/entries` - List all budget entries
- `POST /api/entries` - Create new budget entry
- `PUT /api/entries/{id}` - Update budget entry
- `DELETE /api/entries/{id}` - Delete budget entry

#### Transactions (Planned)
- `POST /api/transactions/upload` - Upload statement file
- `GET /api/transactions?month=2024-07` - List transactions for month
- `POST /api/transactions/categorize` - Auto-categorize transactions (descriptions/vendors only sent)
- `PUT /api/transactions/{id}` - Update transaction categorization

#### Statement Sources (Planned)
- `GET /api/statement-sources` - List all sources
- `POST /api/statement-sources` - Create new statement source
- `PUT /api/statement-sources/{id}` - Update source

#### Analysis (Planned)
- `POST /api/summary` - Calculate spend summary
- `GET /api/analysis/monthly` - Get monthly comparison data
- `GET /api/analysis/rolling-average` - Get rolling average data

### Privacy & Security

**What Stays Local**
- All transaction amounts (never sent to any service)
- All spending history
- All budget definitions
- All account information

**What Gets Sent Out (Optional)**
- Only when user initiates categorization:
  - Transaction description (e.g., "Fresh Market")
  - Vendor name
  - Available budget entry hints
- Sent only to local AI service (no cloud storage)

**No External Dependencies**
- No cloud database
- No analytics tracking
- No data sharing with third parties

## Current Implementation Status

### ✅ Completed
- Project scaffolding with Spring Boot + Maven
- H2 database integration with JPA entities
- Budget entry entity and CRUD operations
- Transaction entity with full schema
- StatementSource entity for managing import sources
- Spring Data repositories for all entities
- BudgetController with database-backed endpoints
- Data migration from legacy flat-file format to database
- Budget entries table UI with add/edit/delete
- Entry validation with currency and quantity fields
- Client-side localStorage synchronization
- Spring Boot server with embedded Tomcat

### 🔄 In Progress
- Server restart and database initialization validation

### ⚠️ Planned
- **Statement File Parser**: Support for CSV, PDF, and OFX formats
- **Auto-Categorization Service**: Integration with local AI for description→budget mapping
- **Statement Import UI**: File upload and source management interface
- **Transaction Management UI**: View, edit, and recategorize transactions
- **Analysis Dashboard**: Charts, rolling averages, monthly comparisons
- **Export Functionality**: Generate and download reports
- **Hints Refinement UI**: Iteratively improve categorization with user feedback

## Approach to Auto-Categorization

1. **Privacy-First Design**: Statement files are parsed server-side; only descriptions and vendors are extracted and sent to AI categorization
2. **Iterative Learning**: Budget entry hints field is used as a knowledge base to guide categorization
3. **User Feedback Loop**: When user corrects/adjusts a transaction's category, the hints are optionally updated to improve future categorization
4. **Local Processing**: All AI calls use local models (no cloud dependencies)

## Data Privacy Summary

| Data Type | Location | Sent Out? |
|-----------|----------|-----------|
| Budget entries | Local database | ❌ No |
| Transaction amounts | Local database | ❌ No |
| Transaction descriptions | Local database | ✓ Only for categorization |
| Vendor names | Local database | ✓ Only for categorization |
| Spending history | Local database | ❌ No |
| Account credentials | Not stored | ❌ No |

## Development & Testing

### Build
```bash
$env:Path = "$PWD\maven\apache-maven-3.9.9\bin;$env:Path"
mvn clean package
```

### Run
```bash
mvn spring-boot:run
```

### Test
```bash
mvn test
```

### Desktop Launch
```bash
.\launch.cmd
```
Opens browser to `http://localhost:8080`

## File Structure
```
PigPurchases/
├── src/main/java/com/pigpurchases/
│   ├── model/
│   │   ├── BudgetEntry.java (JPA entity)
│   │   ├── Transaction.java (JPA entity)
│   │   └── StatementSource.java (JPA entity)
│   ├── repository/
│   │   ├── BudgetEntryRepository.java
│   │   ├── TransactionRepository.java
│   │   └── StatementSourceRepository.java
│   ├── service/
│   │   └── BudgetService.java (business logic)
│   └── server/
│       ├── PigPurchasesApplication.java (Spring Boot entry)
│       ├── BudgetController.java (REST endpoints)
│       └── DataInitializer.java (data migration)
├── src/main/resources/
│   ├── static/index.html (frontend)
│   └── application.properties (H2 config)
├── src/test/java/com/pigpurchases/
│   └── BudgetServiceTest.java
├── pom.xml (Maven configuration)
├── launch.cmd (Windows launcher)
├── pig-purchases-db (H2 database file, auto-created)
└── README.md (this file)
```

## Next Steps

1. Validate H2 database initialization and data migration
2. Implement statement file parser with CSV/PDF/OFX support
3. Create transaction import endpoints
4. Build statement source management UI
5. Integrate local AI for auto-categorization
6. Develop transaction viewing and editing UI
7. Implement analysis dashboard with charts
8. Add export/reporting functionality
9. Build hints refinement workflow

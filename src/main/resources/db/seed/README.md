# NextTurn Database Seeds

This folder contains SQL scripts for **manual data seeding** (*nạp dữ liệu mẫu thủ công*).
These scripts are intentionally separated from Flyway schema migrations (`db/migration/`), giving developers full manual control over when and where sample/test data is loaded.

---

## Directory Structure

```
backend/src/main/resources/db/seed/
├── central/
│   └── 01_seed_central_tenants.sql       # Initial central platform tenants (shop-b, spa-a)
├── tenant/
│   └── 01_seed_tenant_sample_data.sql    # Sample services, counters, and staff for tenant schemas
└── README.md                             # Usage instructions
```

---

## How to Run Manually

### 1. Central Platform Tenants (`nextturn_master`)

Seeds initial records into `central__tenants` for testing tenant authentication routing (`shop-b`, `spa-a`):

#### Using Local MySQL CLI:
```bash
mysql -h localhost -P 3306 -u nextturn -pnextturn_dev_secret nextturn_master < src/main/resources/db/seed/central/01_seed_central_tenants.sql
```

#### Using Docker:
```bash
docker exec -i nextturn-mysql mysql -u nextturn -pnextturn_dev_secret nextturn_master < src/main/resources/db/seed/central/01_seed_central_tenants.sql
```

#### Using Podman:
```bash
podman exec -i nextturn-mysql mysql -u nextturn -pnextturn_dev_secret nextturn_master < src/main/resources/db/seed/central/01_seed_central_tenants.sql
```

---

### 2. Tenant Business Catalog (`tenant_{code}`)

Seeds sample services, service counters, and staff members into a specific tenant's schema:

#### Using Local MySQL CLI:
```bash
mysql -h localhost -P 3306 -u nextturn -pnextturn_dev_secret tenant_shop_b < src/main/resources/db/seed/tenant/01_seed_tenant_sample_data.sql
```

#### Using Docker:
```bash
docker exec -i nextturn-mysql mysql -u nextturn -pnextturn_dev_secret tenant_shop_b < src/main/resources/db/seed/tenant/01_seed_tenant_sample_data.sql
```

---

### 3. Using Database GUI Tools (DBeaver, DataGrip, TablePlus, MySQL Workbench)

1. Connect to MySQL (`localhost:3306`, user: `nextturn`, password: `nextturn_dev_secret`).
2. Open SQL editor.
3. Select target database (`nextturn_master` for central seeds, or `tenant_<code_name>` for tenant seeds).
4. Open the SQL file and execute the script.

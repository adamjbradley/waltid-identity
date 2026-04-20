# n8n 2.x Schema Observations

Captured against a throwaway `n8nio/n8n:2.17.3` + `postgres:16-alpine` pair to
inform the owner-seed SQL for `docker-compose/n8n/bootstrap.sh` (Task 2 of the
minimal n8n install plan).

## Environment

| Item | Value |
|------|-------|
| n8n image | `n8nio/n8n:2.17.3` (note: `n8nio/n8n:2-stable` does NOT exist on Docker Hub; `2.17.3` is the current 2.x release) |
| n8n version (from `n8n --version`) | `2.17.3` |
| Node runtime (from container) | `v24.14.1` |
| Postgres | `postgres:16-alpine` |
| Latest migration applied | `CreateTrustedKeyTables1776000000000` |
| Schema boot log signal | `Version: 2.17.3` after final migration, `healthz` returns `{"status":"ok"}` |

## Full list of tables (`\dt`)

```
                     List of relations
 Schema |               Name               | Type  | Owner
--------+----------------------------------+-------+-------
 public | annotation_tag_entity            | table | n8n
 public | auth_identity                    | table | n8n
 public | auth_provider_sync_history       | table | n8n
 public | binary_data                      | table | n8n
 public | chat_hub_agent_tools             | table | n8n
 public | chat_hub_agents                  | table | n8n
 public | chat_hub_messages                | table | n8n
 public | chat_hub_session_tools           | table | n8n
 public | chat_hub_sessions                | table | n8n
 public | chat_hub_tools                   | table | n8n
 public | credential_dependency            | table | n8n
 public | credentials_entity               | table | n8n
 public | data_table                       | table | n8n
 public | data_table_column                | table | n8n
 public | dynamic_credential_entry         | table | n8n
 public | dynamic_credential_resolver      | table | n8n
 public | dynamic_credential_user_entry    | table | n8n
 public | event_destinations               | table | n8n
 public | execution_annotation_tags        | table | n8n
 public | execution_annotations            | table | n8n
 public | execution_data                   | table | n8n
 public | execution_entity                 | table | n8n
 public | execution_metadata               | table | n8n
 public | folder                           | table | n8n
 public | folder_tag                       | table | n8n
 public | insights_by_period               | table | n8n
 public | insights_metadata                | table | n8n
 public | insights_raw                     | table | n8n
 public | installed_nodes                  | table | n8n
 public | installed_packages               | table | n8n
 public | instance_ai_iteration_logs       | table | n8n
 public | instance_ai_messages             | table | n8n
 public | instance_ai_observational_memory | table | n8n
 public | instance_ai_resources            | table | n8n
 public | instance_ai_run_snapshots        | table | n8n
 public | instance_ai_threads              | table | n8n
 public | instance_ai_workflow_snapshots   | table | n8n
 public | instance_version_history         | table | n8n
 public | invalid_auth_token               | table | n8n
 public | migrations                       | table | n8n
 public | oauth_access_tokens              | table | n8n
 public | oauth_authorization_codes        | table | n8n
 public | oauth_clients                    | table | n8n
 public | oauth_refresh_tokens             | table | n8n
 public | oauth_user_consents              | table | n8n
 public | processed_data                   | table | n8n
 public | project                          | table | n8n
 public | project_relation                 | table | n8n
 public | project_secrets_provider_access  | table | n8n
 public | role                             | table | n8n
 public | role_mapping_rule                | table | n8n
 public | role_mapping_rule_project        | table | n8n
 public | role_scope                       | table | n8n
 public | scope                            | table | n8n
 public | secrets_provider_connection      | table | n8n
 public | settings                         | table | n8n
 public | shared_credentials               | table | n8n
 public | shared_workflow                  | table | n8n
 public | tag_entity                       | table | n8n
 public | test_case_execution              | table | n8n
 public | test_run                         | table | n8n
 public | token_exchange_jti               | table | n8n
 public | trusted_key                      | table | n8n
 public | trusted_key_source               | table | n8n
 public | user                             | table | n8n
 public | user_api_keys                    | table | n8n
 public | variables                        | table | n8n
 public | webhook_entity                   | table | n8n
 public | workflow_builder_session         | table | n8n
 public | workflow_dependency              | table | n8n
 public | workflow_entity                  | table | n8n
 public | workflow_history                 | table | n8n
 public | workflow_publish_history         | table | n8n
 public | workflow_published_version       | table | n8n
 public | workflow_statistics              | table | n8n
 public | workflows_tags                   | table | n8n
(76 rows)
```

## `\d "user"`

```
                              Table "public.user"
         Column         |            Type             | Collation | Nullable |              Default
------------------------+-----------------------------+-----------+----------+------------------------------------
 id                     | uuid                        |           | not null | gen_random_uuid()
 email                  | character varying(255)      |           |          |
 firstName              | character varying(32)       |           |          |
 lastName               | character varying(32)       |           |          |
 password               | character varying(255)      |           |          |
 personalizationAnswers | json                        |           |          |
 createdAt              | timestamp(3) with time zone |           | not null | CURRENT_TIMESTAMP(3)
 updatedAt              | timestamp(3) with time zone |           | not null | CURRENT_TIMESTAMP(3)
 settings               | json                        |           |          |
 disabled               | boolean                     |           | not null | false
 mfaEnabled             | boolean                     |           | not null | false
 mfaSecret              | text                        |           |          |
 mfaRecoveryCodes       | text                        |           |          |
 lastActiveAt           | date                        |           |          |
 roleSlug               | character varying(128)      |           | not null | 'global:member'::character varying
Indexes:
    "PK_ea8f538c94b6e352418254ed6474a81f" PRIMARY KEY, btree (id)
    "UQ_e12875dfb3b1d92d7d7c5377e2" UNIQUE CONSTRAINT, btree (email)
    "user_role_idx" btree ("roleSlug")
Foreign-key constraints:
    "FK_eaea92ee7bfb9c1b6cd01505d56" FOREIGN KEY ("roleSlug") REFERENCES role(slug)
Referenced by:
    TABLE "workflow_builder_session" CONSTRAINT "FK_00290cdeee4d4d7db84709be936" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "oauth_user_consents" CONSTRAINT "FK_21e6c3c2d78a097478fae6aaefa" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "chat_hub_agents" CONSTRAINT "FK_441ba2caba11e077ce3fbfa2cd8" FOREIGN KEY ("ownerId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "project_relation" CONSTRAINT "FK_5f0643f6717905a05164090dde7" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "workflow_publish_history" CONSTRAINT "FK_6eab5bd9eedabe9c54bd879fc40" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE SET NULL
    TABLE "oauth_access_tokens" CONSTRAINT "FK_7234a36d8e49a1fa85095328845" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "dynamic_credential_user_entry" CONSTRAINT "FK_a36dc616fabc3f736bb82410a22" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "oauth_refresh_tokens" CONSTRAINT "FK_a699f3ed9fd0c1b19bc2608ac53" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "oauth_authorization_codes" CONSTRAINT "FK_aa8d3560484944c19bdf79ffa16" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "chat_hub_tools" CONSTRAINT "FK_b8030b47af9213f1fd15450fb7f" FOREIGN KEY ("ownerId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "user_api_keys" CONSTRAINT "FK_e131705cbbc8fb589889b02d457" FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "chat_hub_sessions" CONSTRAINT "FK_e9ecf8ede7d989fcd18790fe36a" FOREIGN KEY ("ownerId") REFERENCES "user"(id) ON DELETE CASCADE
    TABLE "auth_identity" CONSTRAINT "auth_identity_userId_fkey" FOREIGN KEY ("userId") REFERENCES "user"(id)
    TABLE "project" CONSTRAINT "projects_creatorId_foreign" FOREIGN KEY ("creatorId") REFERENCES "user"(id) ON DELETE SET NULL
```

### `information_schema.columns` view (`user` table)

```
      column_name       |        data_type         | is_nullable |           column_default
------------------------+--------------------------+-------------+------------------------------------
 id                     | uuid                     | NO          | gen_random_uuid()
 email                  | character varying        | YES         |
 firstName              | character varying        | YES         |
 lastName               | character varying        | YES         |
 password               | character varying        | YES         |
 personalizationAnswers | json                     | YES         |
 createdAt              | timestamp with time zone | NO          | CURRENT_TIMESTAMP(3)
 updatedAt              | timestamp with time zone | NO          | CURRENT_TIMESTAMP(3)
 settings               | json                     | YES         |
 disabled               | boolean                  | NO          | false
 mfaEnabled             | boolean                  | NO          | false
 mfaSecret              | text                     | YES         |
 mfaRecoveryCodes       | text                     | YES         |
 lastActiveAt           | date                     | YES         |
 roleSlug               | character varying        | NO          | 'global:member'::character varying
(15 rows)
```

### Pre-seeded `user` row (freshly booted n8n, owner not yet set up)

```
                  id                  | email | firstName | lastName |   roleSlug   | disabled
--------------------------------------+-------+-----------+----------+--------------+----------
 348adf29-f4f0-462a-8b5e-e24e7cc13d01 |       |           |          | global:owner | f
(1 row)
```

**Key finding:** n8n 2.17.3 **pre-creates** a placeholder owner row on first
migration. `email`, `firstName`, `lastName`, and `password` are all `NULL`;
`roleSlug` is already `global:owner`. The web setup flow does an **UPDATE** on
this row, not an INSERT. Bootstrap should mirror that pattern.

## `\d settings`

```
                         Table "public.settings"
    Column     |          Type          | Collation | Nullable | Default
---------------+------------------------+-----------+----------+---------
 key           | character varying(255) |           | not null |
 value         | text                   |           | not null |
 loadOnStartup | boolean                |           | not null | false
Indexes:
    "PK_dc0fe14e6d9943f268e7b119f69ab8bd" PRIMARY KEY, btree (key)
```

### `SELECT * FROM settings;` (freshly booted n8n)

```
                 key                 |                                           value                                           | loadOnStartup
-------------------------------------+-------------------------------------------------------------------------------------------+---------------
 userManagement.isInstanceOwnerSetUp | false                                                                                     | t
 ui.banners.dismissed                | ["V1"]                                                                                    | t
 features.ldap                       | {"loginEnabled":false,...,"enforceEmailUniqueness":true}                                  | t
(3 rows)
```

(The `features.ldap` value is a full JSON blob; truncated here for
readability. The important row for bootstrap is the first one.)

**Key finding:** `settings` uses a flat `(key text, value text, loadOnStartup
bool)` schema. The row that gates the browser-based setup wizard is
`userManagement.isInstanceOwnerSetUp`, and its `value` is the **string**
`"false"` (not the JSON boolean `false`). Bootstrap should flip it to the
string `"true"`.

## `\d role`

Because `user.roleSlug` is a FK into a separate `role` table, here is that
table's shape:

```
                                   Table "public.role"
   Column    |            Type             | Collation | Nullable |       Default
-------------+-----------------------------+-----------+----------+----------------------
 slug        | character varying(128)      |           | not null |
 displayName | text                        |           |          |
 description | text                        |           |          |
 roleType    | text                        |           |          |
 systemRole  | boolean                     |           | not null | false
 createdAt   | timestamp(3) with time zone |           | not null | CURRENT_TIMESTAMP(3)
 updatedAt   | timestamp(3) with time zone |           | not null | CURRENT_TIMESTAMP(3)
Indexes:
    "PK_35c9b140caaf6da09cfabb0d675" PRIMARY KEY, btree (slug)
    "IDX_UniqueRoleDisplayName" UNIQUE, btree ("displayName")
Referenced by:
    TABLE "role_mapping_rule" ... FOREIGN KEY (role) REFERENCES role(slug) ON UPDATE CASCADE ON DELETE CASCADE
    TABLE "project_relation" ... FOREIGN KEY (role) REFERENCES role(slug)
    TABLE "user" CONSTRAINT "FK_eaea92ee7bfb9c1b6cd01505d56" FOREIGN KEY ("roleSlug") REFERENCES role(slug)
    TABLE "role_scope" ... FOREIGN KEY ("roleSlug") REFERENCES role(slug) ON UPDATE CASCADE ON DELETE CASCADE
```

### Pre-seeded `role` rows

```
              slug               |            displayName            |         roleType          | systemRole
---------------------------------+-----------------------------------+---------------------------+------------
 credential:owner                | Credential Owner                  | credential                | t
 credential:user                 | Credential User                   | credential                | t
 global:admin                    | Admin                             | global                    | t
 global:chatUser                 | Chat User                         | global                    | t
 global:member                   | Member                            | global                    | t
 global:owner                    | Owner                             | global                    | t
 project:admin                   | Project Admin                     | project                   | t
 project:chatUser                | Project Chat User                 | project                   | t
 project:editor                  | Project Editor                    | project                   | t
 project:personalOwner           | Project Owner                     | project                   | t
 project:viewer                  | Project Viewer                    | project                   | t
 secretsProviderConnection:owner | Secrets Provider Connection Owner | secretsProviderConnection | t
 secretsProviderConnection:user  | Secrets Provider Connection User  | secretsProviderConnection | t
 workflow:editor                 | Workflow Editor                   | workflow                  | t
 workflow:owner                  | Workflow Owner                    | workflow                  | t
(15 rows)
```

**Key finding:** `global:owner` (and every other role we'd want to reference)
is seeded by migration. Bootstrap does not need to insert into `role`.

## Related scaffolding: `project` + `project_relation`

n8n also auto-creates a personal project for the placeholder owner and a
matching `project_relation`:

```
        id        |      name       |   type   | creatorId
------------------+-----------------+----------+--------------------------------------
 BQlCPqF1zMOGqUwr | Unnamed Project | personal | 348adf29-f4f0-462a-8b5e-e24e7cc13d01
```

```
    projectId     |                userId                |         role
------------------+--------------------------------------+-----------------------
 BQlCPqF1zMOGqUwr | 348adf29-f4f0-462a-8b5e-e24e7cc13d01 | project:personalOwner
```

Because these reference the placeholder user's `id`, the simplest bootstrap
strategy (UPDATE the placeholder row in-place) keeps them intact for free.
Inserting a new user row and deleting the placeholder would risk orphaning or
cascade-deleting this project relation.

## Implications for `bootstrap.sh`

Based on the observations above, the Task 2 owner-seed SQL should be:

1. **UPDATE the pre-seeded placeholder user, don't INSERT a new one.**
   On first boot n8n creates exactly one `user` row with `roleSlug='global:owner'`
   and NULL email/password. The setup wizard populates the same row. Mirror that:

   ```sql
   UPDATE "user"
   SET    email       = $OWNER_EMAIL,
          "firstName" = $OWNER_FIRST_NAME,
          "lastName"  = $OWNER_LAST_NAME,
          password    = $BCRYPT_HASH,
          "updatedAt" = CURRENT_TIMESTAMP(3)
   WHERE  "roleSlug" = 'global:owner'
     AND  email IS NULL;  -- idempotency guard: only seed if not yet configured
   ```

   This also keeps the auto-created `project` / `project_relation` rows
   consistent with the owner's UUID without any extra work.

2. **Password must be a bcrypt hash.** The column is `character varying(255)`
   and n8n uses bcrypt (`$2a$`/`$2b$`) hashes. Generate in the bootstrap
   script with `node -e 'require("bcryptjs").hashSync(process.env.OWNER_PASSWORD, 10)'`
   (n8n bundles `bcryptjs`, so we can shell into the n8n container to do this
   if we don't want to pull a hashing dep into the bootstrap image).

3. **Flip `settings.userManagement.isInstanceOwnerSetUp` to the string `"true"`,
   not JSON `true`.** The row already exists (pre-seeded), so UPDATE not INSERT,
   and the value column is plain text:

   ```sql
   UPDATE settings
   SET    value = 'true'
   WHERE  key   = 'userManagement.isInstanceOwnerSetUp';
   ```

4. **No role table seeding needed.** `global:owner` (and `project:personalOwner`
   used by `project_relation`) are inserted by n8n's migrations.

5. **Idempotency is natural.** Both statements above are safe to run on every
   container start: the `UPDATE ... WHERE email IS NULL` on `user` no-ops after
   first run, and the settings UPDATE is idempotent by construction. Bootstrap
   should still guard with a "already configured?" check (e.g. `SELECT 1 FROM
   "user" WHERE "roleSlug"='global:owner' AND email IS NOT NULL`) to skip
   re-running the password hash and keep logs quiet.

6. **Wait for migrations before running the seed.** The settings/user rows only
   exist after all TypeORM migrations have applied, which is after n8n's HTTP
   server comes up. Bootstrap should poll `http://n8n:5678/healthz` (returns
   `{"status":"ok"}`) before doing anything with psql, exactly like this
   discovery script did.

7. **Quote `"user"` — it is a reserved word in Postgres.** n8n's TypeORM schema
   uses it unquoted in code but Postgres requires the double quotes in raw SQL.
   Same for camelCase columns (`"firstName"`, `"lastName"`, `"roleSlug"`,
   `"updatedAt"`).

### Deviations from what the plan assumes

- The plan's sketch talked about an INSERT into `user` with a `role` column.
  In reality the column is named `roleSlug` and n8n pre-seeds the row, so the
  operation is an UPDATE of an existing row, not an INSERT of a new one.
- There is a separate `role` table, but we do **not** need to touch it — it's
  fully populated by n8n's migrations with `global:owner` and friends.
- The `settings.userManagement.isInstanceOwnerSetUp` row already exists after
  migrations, so Task 2 needs an UPDATE, not an INSERT ... ON CONFLICT.
- There is no `2-stable` tag on Docker Hub. Compose should pin to a concrete
  2.x version like `n8nio/n8n:2.17.3` (or track `latest`/`stable` if the team
  accepts the churn).

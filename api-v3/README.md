# MapRoulette API v3

This directory is a Rust rewrite of the MapRoulette backend, which serves the new `/api/v3/` API. For now it runs alongside the Scala backend in the repository root, which serves `/api/v2/`, and the two share one Postgres database. A reverse proxy routes incoming requests to the appropriate backend. The service also serves an OpenAPI description of its API at `/api/v3/openapi.json`.

## Database

You need Postgres 17 with PostGIS. Here's how to start a suitable database using Docker:

```bash
docker run -d --name mr-db -p 5432:5432 -e POSTGRES_USER=osm -e POSTGRES_PASSWORD=osm \
  postgis/postgis:17-3.5
```

The Scala backend's Play evolutions (`conf/evolutions/default/`) are still the source of truth on the database schema. The `schema.sql` file in this directory is a dump of the schema they produce, and is what this crate uses for building and testing. To create a database with that schema:

```bash
psql postgres://osm:osm@localhost:5432/postgres -c 'CREATE DATABASE maproulette'
psql postgres://osm:osm@localhost:5432/maproulette < schema.sql
```

When `schema.sql` changes, drop the database and create it again.

After adding or changing an evolution, regenerate `schema.sql` by:
1. running the Scala backend against an empty database so that Play applies every evolution
2. then dumping it with `pg_dump --schema-only --no-owner --no-privileges > schema.sql`

## Building

The sqlx query macros check every query against a database at compile time. If `DATABASE_URL` isn't set, they use the query metadata committed in `.sqlx/` instead, so `cargo build` works without a database.

If you're changing queries, point `DATABASE_URL` at the database from the previous section. You can put it in `.env` (which git ignores), and cargo will read it from there:

```bash
echo DATABASE_URL=postgres://osm:osm@localhost:5432/maproulette >.env
```

After changing a query or `schema.sql`, regenerate `.sqlx/` and commit it:

```bash
cargo sqlx prepare -- --all-targets
```

## Running

```bash
DATABASE_URL=postgres://osm:osm@localhost:5432/maproulette APPLICATION_SECRET=... \
  ALLOWED_ORIGINS=http://localhost:3000 cargo run
```

It reads its configuration from these environment variables:

- `DATABASE_URL` (required)
- `DATABASE_MAX_CONNECTIONS` (default 10). Remember when setting this that the database is shared with the Scala backend's connection pools.
- `APPLICATION_SECRET` (required): for authenticating session cookies; in production this must match v2's value
- `ALLOWED_ORIGINS` (required): a comma-separated list of frontend origins, such as `https://maproulette.org,https://beta.maproulette.org`. Only these origins can authenticate using session cookies, to protect agains cross site request forgery.
- `LISTEN_ADDR` (default `127.0.0.1:9001`).
- `RUST_LOG` (default `info`): the log level; logs are written to stdout as JSON.

## Testing

To run the tests:

```bash
TEST_DATABASE_URL=postgres://osm:osm@localhost:5432/postgres cargo test
```

The tests build a template database from `schema.sql`, and rebuild it whenever the file changes. Each test that needs a database gets its own copy of the template. Test databases aren't dropped when the tests finish, so you can inspect why something failed if you need to (the subsequent test run will clean them up).

# Tile System

Mapbox Vector Tiles for the explore-page map. Serves the global "available
tasks" view at any zoom in sub-second time, with optional filters applied on
demand.

The design aggregates tasks onto a **hierarchical grid** and materializes the
result into `tile_cells`. Because the grid is hierarchical, a parent cell is
the exact union of its four children — so roll-up is plain summation, and
incremental recomputes are provably identical to a full rebuild. There is no
clustering algorithm (no DBSCAN/KMeans) on the pre-computed path, which is what
makes it exact, deterministic, and consistent between filtered and unfiltered
views.

---

## 1. Endpoint

```
GET /api/v2/taskTilesMvt/:z/:x/:y
    ?global=Boolean              (default false)
    &difficulty=Int              (1=Easy, 2=Normal, 3=Expert)
    &keywords=String             (comma-separated)
    &location_id=Long            (Nominatim place_id)
```

- Route: `conf/v2_route/task.api` → `TaskController.getTaskTilesMvt`.
- Returns `application/vnd.mapbox-vector-tile`. An **empty body** is a valid
  200 and means "no data here" to MapLibre.
- `z` is clamped to `0..22`; `difficulty` is kept only if in `1..3`.
- The MVT layer is `default`; features carry `group_type`, `task_count`, and —
  for z=12 singles/overlaps — `id`, `status`, `priority`, `challenge_id`,
  `task_ids_str`.

---

## 2. Zoom strategy

| Zoom | Path | What is served |
|---|---|---|
| 0–11 | Pre-computed grid cells (`tile_cells`) | One feature per non-empty grid cell (`group_type=2`) |
| 12 | **Live** query against `tasks` | One feature per distinct ground location (`group_type=0` single, `1` overlap) |
| 13–22 | Frontend overzoom | Server returns empty bytes; MapLibre overzooms the z=12 tile client-side |

z=12 is **not** stored — it is queried live (indexed bbox scan + overlap
dedup). This removes a table that used to hold roughly one row per task, and
means filtered and unfiltered z=12 requests run the exact same query.

### The grid

A cell at display zoom `z` is exactly a slippy tile at zoom `z + CELL_BITS`,
with `CELL_BITS = 4` → a 16×16 grid of cells per display tile, each ~256 MVT
pixels. The leaf level is display z=11, i.e. cells on the slippy-zoom-15 grid.

Because a cell at zoom `z` is precisely the union of its four children at
`z+1`, roll-up is exact summation — no cross-tile-seam drift, no
hierarchical-clustering approximation. Per cell we store only **additive**
quantities (`task_count`, `sum_lat`, `sum_lng`, `counts_by_filter`); the
emitted centroid is `sum_lat/task_count, sum_lng/task_count`, itself additive.

---

## 3. Eligibility

A task is "available work" — and contributes to a cell — when **all** of:

- `location` is non-null, non-empty, within valid Web Mercator range;
- `status IN (0, 3, 6)` — Created, Skipped, TooHard;
- `archived = false` (per-task archive flag);
- parent challenge: `deleted=false AND enabled=true AND is_archived=false`;
- parent project: `deleted=false AND enabled=true`.

This filter is the single source of truth for the system. It is mirrored
verbatim in four places — keep them in sync:
`rebuild_leaf_cell` and `rebuild_all_tile_cells` (evolution 107), and the two
live MVT queries in `TileAggregateRepository`.

---

## 4. Storage

Defined in `conf/evolutions/default/107.sql`.

### `tile_cells`

One row per non-empty grid cell, display zoom 0–11.

| Column | Notes |
|---|---|
| `z` | Display zoom 0–11 |
| `cx, cy` | Cell coordinate — slippy-tile coords at zoom `z + 4`. PK is `(z,cx,cy)` |
| `task_count` | Eligible tasks in the cell |
| `sum_lat / sum_lng` | Σ of task latitudes / longitudes; centroid = sum / count |
| `counts_by_filter` | JSONB `{d1_gf,d1_gt,d2_gf,d2_gt,d3_gf,d3_gt,d0_gf,d0_gt}` — counts bucketed by difficulty × global, so difficulty/global filters need no `tasks` access |
| `last_updated` | Timestamp |

### `tile_dirty_cells`

Queue of **leaf cells** (the z=11 grid, slippy zoom 15) awaiting recompute.
PK `(cx, cy)` collapses duplicate marks; `marked_at` drives drain order.

---

## 5. Request routing — `TileAggregateService.getMvtTile`

- **z < 0 or z > 12** → empty bytes.
- **z == 12** → `getMvtTasksLive`: live `tasks` query, overlap-deduped markers.
  Used for every z=12 request, filtered or not.
- **z 0–11, no keyword/location filter** → `getMvtCellsPrecomputed`: one
  `ST_AsMVT` over `tile_cells`. Difficulty/global are applied by summing
  `counts_by_filter` keys.
- **z 0–11, keyword and/or location filter** → `getMvtCellsLive`: on-the-fly
  query that bins eligible tasks onto the **same** cell grid. A filtered map
  therefore clusters identically to an unfiltered one.

**Location fail-safe.** When `location_id` is supplied, the service resolves it
to a polygon via `NominatimService`. If the polygon cannot be resolved, the
service returns **empty bytes** rather than falling back to an unfiltered query
— that would silently leak tasks from outside the requested region.

---

## 6. Recompute functions (evolution 107)

| Function | Purpose |
|---|---|
| `lng_to_tile_x` / `lat_to_tile_y` | lng/lat → slippy tile coordinate |
| `tile_envelope_4326(tz,tx,ty)` | EPSG:4326 envelope of a tile — drives GiST-indexed bbox prefilters |
| `mark_dirty_leaf_cell(geom)` | Enqueue the leaf cell covering a point |
| `rebuild_leaf_cell(cx,cy)` | Recompute one z=11 cell from the base tables |
| `rollup_cell(z,cx,cy)` | Recompute one z<11 cell by summing its four z+1 children |
| `rebuild_dirty_cells(limit,newest_first)` | Drain the queue — see §7 |
| `rebuild_all_tile_cells()` | Full rebuild of the whole pyramid |

`rebuild_leaf_cell` uses `t.location && tile_envelope_4326(15,cx,cy)` so the
GiST index on `tasks.location` does the work; the exact cell assignment is a
cheap refilter on that small candidate set. Every recompute reads the **base
tables** and is therefore authoritative — immune to the races a pure
delta-propagation scheme suffers.

---

## 7. How tiles get updated

Four mechanisms; the first two only *enqueue*, the rest *drain*.

### 7a. Task-change trigger (enqueue)

`mark_dirty_on_task_change_trigger` — `AFTER INSERT OR UPDATE OR DELETE ON
tasks`. No-op updates (nothing among status, location, parent_id, archived
changed) are skipped. Otherwise it marks the leaf cell of the old **and** new
location and emits `NOTIFY tile_dirty`. No neighbour buffering is needed: with
grid binning a task belongs to exactly one cell.

### 7b. Challenge-change trigger (enqueue)

`mark_dirty_on_challenge_change_trigger` — `AFTER UPDATE OF deleted, enabled,
is_archived, is_global, difficulty ON challenges`. Those columns flip
eligibility or re-bucket `counts_by_filter` for every task in the challenge
without touching task rows, so the per-task trigger never fires. It bulk-marks
every leaf cell holding one of the challenge's tasks and emits `NOTIFY`.

### 7c. LISTEN/NOTIFY listener (drain)

`TileDirtyListener` (`app/org/maproulette/jobs/`) holds one dedicated
connection, `LISTEN tile_dirty`, and drains the queue the moment a notification
arrives — tiles refresh within ~1s of a mutation, with no fixed-interval
polling. The blocking `getNotifications` call also returns on a 30s timeout,
which doubles as a **safety sweep** (covers a missed NOTIFY). The loop catches
every error and reconnects, so the worker cannot die. Disable via
`osm.tile.listener.enabled = false`.

### 7d. Synchronous post-commit drain (drain)

`TaskDAL.setTaskStatus`, after the mutation commits and before the WebSocket
notification, calls `rebuildRecentDirtyCells(limit=128)` — a newest-first drain
— so the originating user's own tile refetch hits fresh bytes instead of racing
the listener. Wrapped in try/catch; a failure is logged, never blocks the
mutation.

### The drain itself

`rebuild_dirty_cells(limit, newest_first)`:

1. Takes one **global advisory lock** — only one drainer mutates the pyramid at
   a time, so concurrent roll-ups (listener vs. post-commit drain) never race.
   The lock is transaction-scoped; callers wait at most one batch.
2. Pops up to `limit` leaf cells (oldest-first, or newest-first for 7d).
3. Recomputes each leaf cell with `rebuild_leaf_cell`.
4. Rolls up z=10..0: each level's dirty set is the distinct parents of the
   level below, recomputed with `rollup_cell`.

| # | Mechanism | Trigger | Latency |
|---|---|---|---|
| 7a | Task-change trigger | task INSERT/UPDATE/DELETE | immediate (enqueue) |
| 7b | Challenge-change trigger | challenge UPDATE of 5 cols | immediate (enqueue) |
| 7c | LISTEN/NOTIFY listener | `NOTIFY tile_dirty` / 30s sweep | ~1 s |
| 7d | Synchronous post-commit drain | `setTaskStatus` commit | < 1 s, before the WS event |

The filtered request path (keywords/location) is **not** an update path — it
queries `tasks` live and never reads or writes the pre-computed tables.

---

## 8. HTTP caching

`Cache-Control` set by `TaskController.getTaskTilesMvt`:

| Response | `Cache-Control` |
|---|---|
| Empty bytes | `no-store` (may gain data on the next rebuild) |
| Any non-empty tile | `public, max-age=10, must-revalidate` |

A tile is a pure function of `(z,x,y)` and the filter params — **nothing in it
depends on the requesting user** — so every non-empty tile, filtered or not, is
publicly cacheable / CDN-shareable.

---

## 9. Configuration

| Key | Default | Controls |
|---|---|---|
| `osm.tile.listener.enabled` | `true` | Whether `TileDirtyListener` runs (`MR_OSM_TILE_LISTENER_ENABLED`) |

There is no scheduled tile job — the listener replaces the old fixed-interval
refresh loops.

---

## 10. Operational notes

- **Initial population:** `SELECT rebuild_all_tile_cells();` once after applying
  evolution 107 on a populated database (also available as
  `serviceManager.tileAggregate.rebuildAll()`).
- **Crash recovery / trigger-bypassing imports:** rerun `rebuild_all_tile_cells()`.
- **Health:** `serviceManager.tileAggregate.getStats()` returns
  `{ totalCells, dirtyCells, dirtyQueueLagS }`. `dirtyQueueLagS` — the age of
  the oldest queued cell — climbing means the drain is falling behind.

---

## 11. Frontend consumption

The MVT feature schema is unchanged from the previous design, so existing
MapLibre code keeps working:

- `group_type=2` → cluster bubble (a grid cell), `task_count` tasks.
- `group_type=0` → individual task marker (z=12), with `id/status/priority`.
- `group_type=1` → overlap stack (z=12); expand via `task_ids_str`.

Optional enhancement: feed the z<12 cell points into the frontend's
SuperCluster index (as it already does for `group_type` 0/1) so low-zoom
clusters render organically rather than grid-snapped.

---

## 12. Files

| Layer | Path |
|---|---|
| Schema + functions + triggers | `conf/evolutions/default/107.sql` |
| Service (routing, fail-safe, stats) | `app/org/maproulette/framework/service/TileAggregateService.scala` |
| Repository (precomputed + live SQL) | `app/org/maproulette/framework/repository/TileAggregateRepository.scala` |
| Location polygon lookup | `app/org/maproulette/framework/service/NominatimService.scala` |
| Controller (HTTP, caching) | `app/org/maproulette/framework/controller/TaskController.scala` |
| LISTEN/NOTIFY listener | `app/org/maproulette/jobs/TileDirtyListener.scala` (wired in `JobModule`) |
| Synchronous post-commit drain | `app/org/maproulette/models/dal/TaskDAL.scala` (`setTaskStatus`) |
| Config | `app/org/maproulette/Config.scala`, `conf/application.conf` |

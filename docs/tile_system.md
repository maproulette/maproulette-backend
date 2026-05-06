# Tile System

Pre-computed Mapbox Vector Tiles for the explore-page map. Serves the global
"available tasks" view at any zoom in sub-second time, with filters applied
on demand.

## Endpoint

```
GET /api/v2/taskTilesMvt/:z/:x/:y
    ?global=Boolean
    &difficulty=Int (1=Easy, 2=Normal, 3=Expert)
    &keywords=String (comma-separated)
    &location_id=Long
```

Returns `application/vnd.mapbox-vector-tile`. Empty 200 means "no data here"
to MapLibre.

## Zoom strategy

| Zoom | Path | What's stored |
|---|---|---|
| 0–11 | Pre-computed, clustered | Multiple cluster rows per tile (SuperCluster-style) |
| 12 | Pre-computed, unclustered | One row per ground location (overlap-aware) |
| 13+ | Frontend overzoom | Server returns nothing; MapLibre overzooms the z=12 native tile client-side |

The 200-MVT-pixel cluster radius (`200 × tile_pixel_size_meters(z)`) matches
SuperCluster's 25-pixel default exactly (25/512 == 200/4096). Backend and
browser cluster identically.

## Eligibility

A task contributes to a tile when all of:

- `location` is non-null, non-empty, in valid lat/lng range
- `status IN (0, 3, 6)` (Created, Skipped, TooHard — i.e. available work)
- Challenge: `deleted=false, enabled=true, is_archived=false`
- Project: `deleted=false, enabled=true`

## Storage

### `tile_task_groups`

One row per (z, x, y, cluster).

| Column | Notes |
|---|---|
| `z, x, y` | Tile coordinate |
| `group_type` | 0=single task, 1=overlap (multiple tasks at one location), 2=cluster |
| `centroid_lat/lng` | Position emitted to the MVT |
| `task_ids` | Populated for group_type 0/1; empty array for clusters |
| `task_count` | Tasks represented |
| `counts_by_filter` | JSONB `{d1_gf, d1_gt, d2_gf, d2_gt, d3_gf, d3_gt, d0_gf, d0_gt}` — counts bucketed by difficulty × global, lets the unfiltered path apply difficulty/global filters without touching `tasks` |

### `tile_dirty_marks`

Queue of (z, x, y) tiles waiting for rebuild. PK collapses duplicate marks.
`marked_at` drives FIFO drain order.

## Routing (TileAggregateService)

```
hasFilters = keywords nonempty OR location_id present
usePrecomputed = !hasFilters && z <= 12
```

- **z > 12**: empty bytes; frontend overzooms z=12 client-side.
- **Precomputed path** (`!hasFilters`, z ≤ 12): one SQL statement against
  `tile_task_groups` with `ST_AsMVT`. Difficulty/global apply via
  `counts_by_filter` summing.
- **Filtered path** (filters present, z ≤ 12): three-CTE on-the-fly query
  that filters tasks, runs `ST_ClusterDBSCAN` with the same eps formula,
  emits group_type 0 (single) or 1 (overlap).

## Rebuild functions

| Function | Purpose |
|---|---|
| `rebuild_zoom_level(z)` | Full rebuild of one zoom from scratch. Greedy "claim neighbors" at z=0..11, partition-DBSCAN at z=12. |
| `rebuild_all_tile_aggregates()` | Loops `rebuild_zoom_level` for z=0..12. |
| `rebuild_dirty_tiles(limit, min_z, max_z)` | Drains queue FIFO. Reclusters only the tasks currently in each popped tile. |
| `rebuild_recent_dirty_tiles(limit, min_z, max_z)` | Same as `rebuild_dirty_tiles` but `ORDER BY marked_at DESC` — used for the synchronous post-mutation drain. |

Per-tile incremental rebuilds are an approximation: they recluster only that
tile's tasks. Cross-tile-edge cluster drift accumulates slowly and is
corrected by a periodic full rebuild (run nightly off-peak; not currently
auto-scheduled).

## Update propagation

Three trigger points feed `tile_dirty_marks`:

### Task changes

`mark_tiles_dirty_on_task_change_trigger` on `tasks` —
`AFTER INSERT OR UPDATE OF status, location, parent_id OR DELETE`.

Calls `mark_tile_dirty_for_point(lng, lat)` for the old and new positions.
That helper:

- Marks the tile at every zoom 0..12.
- **Neighbor buffer at z=0..11:** if the point is within
  `200 × tile_pixel_size_meters(z)` of a tile edge, marks the adjacent
  tile dirty too (cardinal + diagonal at corners). Necessary because a
  point near an edge can be merged into a cluster whose centroid lands in
  the neighboring tile.
- Skipped at z=12 (microscopic eps; no cross-tile overlap possible).

Skips no-op UPDATEs (status, parent_id, location all unchanged).

### Challenge changes

`mark_tiles_dirty_on_challenge_change_trigger` on `challenges` —
`AFTER UPDATE OF deleted, enabled, is_archived, is_global, difficulty`.

Bulk single-statement insert of every (z, x, y) containing one of the
challenge's tasks, for z=0..12. Skips neighbor buffering for performance —
the cross-tile-edge case is handled by the next periodic full rebuild.

Reason it exists: changing any of those columns flips eligibility (or
re-buckets `counts_by_filter`) for every task in the challenge without
touching the task rows themselves, so the per-task trigger never fires.

### Synchronous post-commit drain

`TaskDAL` calls `rebuildRecentDirtyTiles(limit=20, minZoom=13)` after a task
mutation commits, before the WebSocket notification. The user's own next tile
fetch — triggered by the WebSocket event bumping `?v=N` on the tile URL — hits
fresh bytes instead of racing the FIFO scheduler.

## Scheduler

Two jobs, configured in `Scheduler.scala`:

| Job | Interval | Range | Limit | Reason |
|---|---|---|---|---|
| `refreshTileAggregates` | 5s | z=13..22 | 500 | High-zoom user-visible tiles refresh in seconds. |
| `refreshTileAggregatesLowZoom` | 30s | z=0..12 | 200 | Clustered low-zoom rebuilds are 10–100× more expensive; less-noticeable staleness. |

The split prevents bulk imports from starving high-zoom user-visible refreshes.

## Caching

`Cache-Control` on the response:

- **Empty bytes** → `no-store` (an empty tile may have data on the next rebuild).
- **Unfiltered** → `public, max-age=10, must-revalidate` (CDN-shareable; aligned with rebuild cadence).
- **Filtered** → `private, no-store` (request-specific; never share).

Frontend bumps a `?v=N` query param on every WebSocket task-update event so
stale cached tiles are URL-busted out of the browser cache after a mutation.

## Frontend consumption

MapLibre fetches the MVT, then `querySourceFeatures` extracts visible features:

- `group_type=2` → render as server-side cluster bubble with abbreviated
  count.
- `group_type=0` → individual task marker (status filtered to 0 client-side).
- `group_type=1` → overlap group; click expands via `task_ids_str` (a
  comma-joined string of task ids).

`group_type 0` and `1` features are also fed into a client-side SuperCluster
index (radius=25) so the user gets smooth aggregation across tile boundaries
when the cluster toggle is on.

## Operational notes

- **Initial population:** `SELECT rebuild_all_tile_aggregates();` after
  applying the migration on a populated database.
- **Periodic correction:** schedule `rebuild_all_tile_aggregates()` nightly
  off-peak to clean up incremental-rebuild drift.
- **After bulk admin actions** (e.g. mass archive/unarchive): the challenge
  trigger queues every affected tile, but with no neighbor buffering. Run
  `rebuild_all_tile_aggregates()` once afterward to refresh
  cross-tile-edge clusters.
- **Inspection:** `SELECT COUNT(*) FROM tile_dirty_marks;` shows queue depth.
  `serviceManager.tileAggregate.getStats()` returns it via the service.

## Files

| Layer | Path |
|---|---|
| Schema + functions + triggers | `conf/evolutions/default/107.sql` |
| Service (routing, eligibility) | `app/org/maproulette/framework/service/TileAggregateService.scala` |
| Repository (SQL) | `app/org/maproulette/framework/repository/TileAggregateRepository.scala` |
| Controller (HTTP, caching) | `app/org/maproulette/framework/controller/TaskController.scala` (`getTaskTilesMvt`) |
| Route | `conf/v2_route/task.api` |
| Scheduler | `app/org/maproulette/jobs/Scheduler.scala` + `SchedulerActor.scala` (`refreshTileAggregates`) |
| Synchronous post-commit drain | `app/org/maproulette/models/dal/TaskDAL.scala` (post-update block) |

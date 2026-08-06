# osuRenderer

osuRenderer is the isolated Danser video worker for oStella. It has no osu! API
credentials and does not download beatmaps or replays. For each job, oStella sends:

- a beatmapset archive (`.osz`);
- one or more replay files (`.osr`);
- the already constructed Danser JSON configuration;
- render options such as start/end timestamps or showcase beatmap ID.
- an optional short-lived QQ access token and message target for direct video upload.

Before submission, oStella performs one batch cache lookup. It uploads only the
beatmapsets and replays that osuRenderer does not already have. Cached `.osz`
files are keyed by beatmapset ID and cached `.osr` files by score ID. Cache writes
use temporary files and atomic replacement so concurrent submissions never expose
partial files.

osuRenderer assembles a per-job Danser workspace from the persistent cache, runs
Danser in a bounded worker pool, exposes progress and the MP4 result, and removes
only the temporary workspace after the process exits. Video results expire
according to `resultTtlMinutes`; render inputs remain under `renderer.cachePath`.

When `qqUpload` is supplied with a render request, the job enters an `uploading`
state after Danser finishes. osuRenderer uploads the local MP4 through QQ's
multipart upload API and exposes the resulting `file_uuid`, `file_info`, and
`ttl` as `qqFile` in the completed job status. If no credentials are supplied,
rendering behaves exactly as before. If QQ upload fails, the job still completes
with the MP4 available and includes an error so SeiraCore can use its previous
download-and-upload fallback.

## Run

Build with `mvn package`, then run the `jar-with-dependencies` artifact. On the
first run, the service writes `config.yml` and exits. Configure `danserPath` and
set `renderer.apiKey` to the same secret used by oStella.

The default port is `8722`. When the services run on different machines, allow
oStella to reach this port and keep it private or place it behind TLS. SeiraCore
does not connect to osuRenderer directly.

Because render submissions can contain a short-lived QQ token, use TLS for both
SeiraCore-to-oStella and oStella-to-osuRenderer traffic whenever either hop
crosses a trusted private network boundary. Tokens are held only for the lifetime
of a render request and are never returned by status endpoints or written to the cache.

## Internal API

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/health` | Public liveness check |
| POST | `/cache/status` | Batch lookup for cached beatmapset and replay IDs |
| GET | `/renders/status` | Queue and active worker counts |
| POST | `/renders` | Multipart render submission |
| GET | `/renders/{jobId}/status` | Job progress |
| GET | `/renders/{jobId}/video` | MP4 result |
| DELETE | `/renders/{jobId}` | Remove result and metadata |

All endpoints except `/health` require `Authorization: Bearer <apiKey>` when an
API key is configured.

`POST /renders` always receives `beatmapsetId` and the ordered JSON `replayIds`
list. The `beatmapset` file, `replays` files, and parallel `replayUploadIds` list
contain only cache misses. A request referencing a still-missing cache item is
rejected with HTTP 409 instead of starting a broken render.
The optional `qqUpload` multipart field is a JSON object with `accessToken`,
`targetType` (`groups` or `users`), and `targetId`. Completed status responses
may contain `qqFile`; they never echo `qqUpload`.
Cache lookups and render references are limited to 1000 IDs per asset type and request.
For rolling upgrades, the renderer still accepts the previous upload-all multipart
format, but those legacy submissions cannot populate the ID-based cache. Upgrade
osuRenderer before upgrading oStella.

## Cache lifecycle

The cache is persistent and has no automatic expiry because beatmapset and score
IDs identify immutable render inputs. To clear it, stop osuRenderer and remove
the configured cache directory. Active job workspaces and rendered videos are
stored separately and are unaffected by cache lookup semantics.

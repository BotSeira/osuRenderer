# osuRenderer

osuRenderer is the isolated Danser video worker for oStella. It has no osu! API
credentials and does not download beatmaps or replays. For each job, oStella sends:

- a beatmapset archive (`.osz`);
- one or more replay files (`.osr`);
- the already constructed Danser JSON configuration;
- render options such as start/end timestamps or showcase beatmap ID.

osuRenderer stores those inputs in a per-job temporary directory, runs Danser in
a bounded worker pool, exposes progress and the MP4 result, and removes uploaded
inputs after the process exits. Results expire according to `resultTtlMinutes`.

## Run

Build with `mvn package`, then run the `jar-with-dependencies` artifact. On the
first run, the service writes `config.yml` and exits. Configure `danserPath` and
set `renderer.apiKey` to the same secret used by oStella.

The default port is `8722`. When the services run on different machines, allow
oStella to reach this port and keep it private or place it behind TLS. SeiraCore
does not connect to osuRenderer directly.

## Internal API

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/health` | Public liveness check |
| GET | `/renders/status` | Queue and active worker counts |
| POST | `/renders` | Multipart render submission |
| GET | `/renders/{jobId}/status` | Job progress |
| GET | `/renders/{jobId}/video` | MP4 result |
| DELETE | `/renders/{jobId}` | Remove result and metadata |

All `/renders` endpoints require `Authorization: Bearer <apiKey>` when an API key
is configured.

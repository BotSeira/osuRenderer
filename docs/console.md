# osuRenderer administration console

osuRenderer uses Log4J2 for service and Danser output and JLine for interactive input, history, completion, and prompt-safe log redraw. Console text is English to avoid terminal encoding problems.

| Command | Purpose |
| --- | --- |
| `status` | Web, HTTP, render-pool, job, cache, and uptime health |
| `queue` | Active workers, queue utilization, and completed tasks |
| `jobs [status]` | List tracked jobs, optionally filtered by state |
| `job show <uuid>` | Show one render job |
| `job delete <uuid> confirm` | Delete job metadata and its result |
| `cache status` | Show persistent beatmapset/replay cache usage |
| `cache <query/delete/get/fetch> <score/beatmap/beatmapset/replay> <id>` | Use unified cache semantics locally; direct fetch reports `N/A` because workers have no upstream credentials |
| `cache has <type> <id>` | Check one cached asset |
| `cache remove <type> <id> confirm` | Remove one cached asset |
| `cache clear <type|all> confirm` | Clear selected persistent cache files |
| `cleanup now` | Run result-TTL cleanup immediately |
| `config show` | Show effective configuration with secrets and environment values redacted |
| `config check` | Validate `config.yml` without applying it |
| `log show` | Show the current Log4J2 root level |
| `log level <level>` | Set `trace`, `debug`, `info`, `warn`, or `error` until restart |
| `system` | Show version, JVM, OS, threads, memory, and uptime |
| `stop confirm` | Gracefully stop the console and every owned service |

Use `help [command]` and Tab completion in the running console. Bulk cache clearing, legacy removal, job deletion, and service shutdown require the literal `confirm` argument; unified single-ID cache deletion uses the four-part command directly.

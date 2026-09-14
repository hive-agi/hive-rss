# hive-rss

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-rss.svg)](https://clojars.org/io.github.hive-agi/hive-rss)

RSS and Atom feeds as a hive addon (`hive.rss`). It subscribes to feeds, polls
them on a schedule it runs itself, and puts every new item into hive memory,
once.

```text
config :rss/feeds ─▶ scheduler (daemon thread, in the IAddon)
                          │  every tick: feeds whose last poll is a period old
                          ▼
             conditional GET ─▶ parse (RSS 2.0 / RSS 1.0 / Atom) ─▶ unseen items
                                                                       │
                   ┌──────────── :rss/sink ────────────────────────────┤
                   ▼                                                   ▼
   hive-ingestor: memory ingest source rss            memory store: one note per item
   (chunks, document entry, KG edges)                 (hash-deduplicated, scoped)
```

## Install

```clojure
io.github.hive-agi/hive-rss {:mvn/version "RELEASE"}
```

The jar carries `META-INF/hive-addons/hive-rss.edn`, so hive discovers the
addon on the classpath. Pin the version from the Clojars badge.

## Configure

Out of the box it subscribes to the hive store's release feed,
`https://store.hive-mcp.com/api/feed`, once a day. Override in
`~/.config/hive-mcp/config.edn`:

```clojure
{:addons
 {"hive.rss"
  {:rss/fetches-per-day 4                       ; 1..96, default 1
   :rss/feeds ["https://store.hive-mcp.com/api/feed"
               {:feed/url "https://planet.clojure.in/atom.xml"
                :feed/id "planet-clojure"
                :feed/tags ["clojure"]}]
   :rss/sink "auto"                             ; "auto" | "ingestor" | "memory"
   :rss/project-id "hive-rss"}}}                ; memory scope for items
```

| key | default | meaning |
|-----|---------|---------|
| `:rss/feeds` | hive-store releases | URLs, or maps with `:feed/url`, `:feed/id`, `:feed/tags`, `:feed/auth` |
| `:rss/fetches-per-day` | `1` | polls per feed per day, 1 to 96 |
| `:rss/sink` | `"auto"` | where new items go, see below |
| `:rss/project-id` | `"hive-rss"` | memory scope |
| `:rss/memory-type` | `"note"` | entry type for the memory sink |
| `:rss/duration` | `"medium"` | entry duration for the memory sink |
| `:rss/max-items-per-poll` | `50` | the rest arrive on later polls |
| `:rss/timeout-ms` | `20000` | per request |
| `:rss/max-bytes` | 5 MiB | a larger feed is refused |
| `:rss/initial-delay-ms` | `30000` | before the first tick |
| `:rss/state-file` | `$XDG_STATE_HOME/hive-rss/state.edn` | seen items and poll times |

A config that fails validation fails `initialize!` with the reasons; nothing
starts.

### Feeds that need a key

The hive store feed lists open packages to anybody and adds the private ones
for a hive key. Give a feed `:feed/auth`:

```clojure
:rss/feeds [{:feed/url "https://store.hive-mcp.com/api/feed"
             :feed/auth {:secret-env "HIVE_STORE_KEY"}}]      ; HTTP Basic, key from the environment
```

| `:feed/auth` key | meaning |
|------------------|---------|
| `:secret-env` | environment variable holding the key (preferred) |
| `:secret` | the key inline |
| `:scheme` | `"basic"` (default) or `"bearer"` |
| `:username` | Basic user, default `hive-rss`; the store ignores it |

A URL with `user:key@` in it works too. Either way the key is taken out of the
URL, so feed ids, the state file, memory entries, `rss status` and error
messages never contain it; a key prints as `#secret[redacted]`. It is sent only
to the feed's own origin: a redirect elsewhere is followed without it. An unset
`:secret-env` fails `initialize!` naming the variable.

A refused key is a poll error with the server's reason, for example
`rss/http-status: 402 with credential: deny/subscription-inactive`, not a
quietly shorter feed. The `rss` ingestion source uses the same credential when
its `rss-url` is a configured feed.

To check a store from this side, anonymously and with a key:

```bash
HIVE_STORE_KEY=hv_live_... clojure -M:dev -m hive-rss.store-feed-check https://store.hive-mcp.com/api/feed
```

## Schedule

The scheduler is part of the addon. `initialize!` starts one daemon thread and
`shutdown!` stops it. It wakes every quarter hour (or every period, when that
is shorter) and polls only the feeds that are due. A feed is due when its last
successful poll is `86400 / fetches-per-day` seconds old.

Poll times are persisted, so a restart neither re-polls early nor skips a day.
A failed poll is retried after a tenth of the period, between 5 minutes and an
hour, instead of waiting a full period. Requests are conditional (ETag,
Last-Modified), so an unchanged feed costs a 304.

## Sinks

- **`ingestor`**: each poll's new items go through hive-ingestor as
  `memory ingest source source=rss`, called through the host's `:tools/invoke`
  runtime port. hive-rss has no compile dependency on hive-ingestor. The ingest
  reads the feed the poll already fetched, narrowed to the new items' keys.
- **`memory`**: one note per item through the hive-spi memory store port.
  Content is hashed and deduplicated in the project, and entries carry the
  `scope:project:` tag, `rss`, `rss-feed:<id>`, the feed's tags and up to five
  item categories.
- **`auto`**: `ingestor` when the host offers `:tools/invoke`. A batch the
  ingestor cannot take is filed as notes instead.

An item is marked seen only once it is delivered. An item the store or the
ingestor refused is offered again on the next poll.

## The `rss` ingestion source

Registered through `hive-spi.ingest.registry` while the addon is active, so any
feed can be ingested on demand, one document per item:

```text
memory ingest source source=rss rss-url=https://planet.clojure.in/atom.xml
```

`rss-keys` (a list or a comma-separated string) narrows the run to those items.

## The `rss` tool

- `rss command=status`: rate, sink, and per feed its last poll, status, error,
  seen count and seconds until the next poll.
- `rss command=poll`: run a pass now. Add `force=true` to poll feeds that are
  not due, and `feed_id=<id>` to narrow the pass to one feed.

## Parsing

The JDK DOM parser handles RSS 2.0, RSS 1.0 (RDF) and Atom 1.0, with DOCTYPEs
refused, so no entity can be declared or expanded. An item's identity is its
guid or id, else its link, else its title and date. HTML in descriptions is
kept as the summary and reduced to text for the entry.

## Develop

```sh
clojure -M:test                      # unit + loopback end to end (no network)
clojure -M:integration               # the live hive-store feed
clojure -M:dev -m hive-rss.demo URL  # poll real feeds, print the entries
```

`test/resources/fixtures/hive-store-feed.xml` was rendered by hive-store's own
`hive-store.promote.feed/render`.

## Releases

A push to `main` that changes `src/`, `resources/`, `test/` or `deps.edn` runs
the suite, bumps the patch version, regenerates `CHANGELOG.md`, tags `vX.Y.Z`
and deploys to Clojars through
[hive-build](https://github.com/hive-agi/hive-build).

## License

MIT

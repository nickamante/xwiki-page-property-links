# Page Property Links

An XWiki extension that makes typed `Page`-property references behave like real links — tracked as
**backlinks** on their targets and **kept valid when a target page is moved or renamed**.

XWiki builds its link graph only from wiki-syntax links in document content, so references stored in
object properties of type `Page` are invisible to backlinks and to rename refactoring. This
extension closes that gap. It is generic platform code: it keys off the `Page` property type only
and has no knowledge of any specific application.

## Features

- **Backlinks** — a `SolrEntityMetadataExtractor` adds each document's `Page`-property references to
  the Solr `links` / `links_extended` fields, so targets list the referring pages as native
  backlinks.
- **Rename integrity** — a `DocumentRenamedEvent` listener rewrites every `Page`-property value
  across the wiki when a target page is renamed, honouring the rename dialog's "Update links" option.
- Handles single-value (`StringProperty`) and relational multi-value (`DBStringListProperty`) `Page`
  properties out of the box; non-relational multi-value (`StringListProperty`) behind a flag.

## Compatibility

- **XWiki 18.x** (built and tested against the 18.0.0 floor; runs on 18.0.0 and newer), **Java 21**.

> The Solr backlink extractor binds to one XWiki `internal` class (`SolrLinkSerializer`), which
> carries no cross-version compatibility guarantee — so the supported range is the 18.x line.

## Installation

Build the JAR (see [Building](#building)) and install it as a core extension: copy
`page-property-links-<version>.jar` into your XWiki webapp's `WEB-INF/lib/` and restart, or install
it through the Extension Manager.

## Configuration

Set in `xwiki.properties` (a restart is required for changes to take effect):

| Property | Default | Meaning |
|---|---|---|
| `pagePropertyLinks.supportNonRelationalStorage` | `false` | When `true`, also handles non-relational multi-value `Page` properties (`StringListProperty`). Off by default because discovery requires an unindexed `LIKE` scan. |
| `pagePropertyLinks.referenceTypes` | `Page` | The property class types treated as references. |

## Building

The build runs in a container, so no host JDK/Maven is required:

```bash
bash dev/build.sh clean package   # -> target/page-property-links-<version>.jar
bash dev/build.sh test            # run the unit tests
```

A docker-compose dev stack and a manual in-wiki verification runbook are in [`dev/`](dev/).

## License

[LGPL 2.1](LICENSE).

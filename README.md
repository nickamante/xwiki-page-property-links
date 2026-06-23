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

The extension is a single self-contained JAR — every `xwiki-platform-*` dependency is `provided` by
the running XWiki, so nothing else needs to be deployed alongside it.

**1. Get the JAR** — download a built artifact from the
[Releases](https://github.com/nickamante/xwiki-page-property-links/releases) page, or build it
yourself (see [Building](#building)):

```bash
curl -L -O https://github.com/nickamante/xwiki-page-property-links/releases/download/v1.0.0/page-property-links-1.0.0.jar
```

**2. Install it as a core extension** — copy the JAR into the XWiki webapp's library directory and
restart the servlet container. A component JAR is loaded at startup, so a **restart is required** —
there is no hot reload:

```bash
cp page-property-links-<version>.jar <tomcat>/webapps/ROOT/WEB-INF/lib/
```

> **Extension Manager alternative:** installing by ID through *Admin → Extensions* instead requires
> the artifact to be in a Maven repository the instance can resolve (Maven Central, a private Nexus,
> or the XWiki extensions repository once published to xwiki-contrib). The file drop above needs no
> Maven repository.

**3. Reindex (first install only)** — the backlink extractor runs during indexing, so **existing**
pages need a one-time reindex to gain their `Page`-property backlinks: *Admin → Search → Solr* → run
the reindex. New and edited pages are indexed incrementally and need no action.

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

## Versioning

Releases follow [semantic versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`) and target the
**XWiki 18.x** line (see [Compatibility](#compatibility)). `main` carries a `-SNAPSHOT` version for
ongoing development and is not intended for deployment — install a released version instead.

## Releasing

Releases are automated: pushing a `v*` tag runs the
[release workflow](.github/workflows/release.yml), which builds, runs the unit tests, and publishes a
GitHub Release with the JAR attached. To cut a release:

1. In `pom.xml`, set `<version>` to the release version (drop the `-SNAPSHOT` suffix) and commit.
2. Tag and push — this triggers the workflow:
   ```bash
   git tag -a v1.2.3 -m "Page Property Links 1.2.3"
   git push origin v1.2.3
   ```
3. Bump `pom.xml` to the next `-SNAPSHOT` on `main` and commit.

The published JAR appears on the [Releases](https://github.com/nickamante/xwiki-page-property-links/releases)
page (see [Installation](#installation)).

## License

[LGPL 2.1](LICENSE).

package io.github.zoot.englishreader.data.importer.spike

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * SPIKE-ONLY projection from Readium types to [ReadiumPublicationSnapshot].
 *
 * Lives in `src/debug` so both `src/testDebug` (JVM) and `src/androidTest` (device) assert against
 * the same projection instead of duplicating it. Never referenced from `src/main`.
 *
 * The projection is deliberately lossy in one direction only: it records what Readium reports
 * without normalizing it, so the spike can compare Readium's href/fragment handling against the
 * production [io.github.zoot.englishreader.data.importer.EpubTextExtractor] contract.
 */
internal object ReadiumSnapshotProjection {

    fun snapshot(publication: Publication): ReadiumPublicationSnapshot {
        val readingOrder = publication.readingOrder
        val readingOrderPaths = readingOrder.mapNotNull { it.decodedPath() }.toSet()
        return ReadiumPublicationSnapshot(
            identifier = publication.metadata.identifier,
            title = publication.metadata.title,
            authors = publication.metadata.authors.map { it.name },
            languages = publication.metadata.languages,
            readingOrder = readingOrder.map { it.toSnapshot(readingOrderPaths) },
            tableOfContents = publication.tableOfContents.map { it.toSnapshot(readingOrderPaths) },
        )
    }

    /** Depth-first flattening of the recursively projected TOC. */
    fun flattenToc(publication: Publication): List<ReadiumLinkSnapshot> =
        snapshot(publication).tableOfContents.flatMap { it.flattenDepthFirst() }

    private fun ReadiumLinkSnapshot.flattenDepthFirst(): List<ReadiumLinkSnapshot> =
        listOf(this) + children.flatMap { it.flattenDepthFirst() }

    private fun Link.toSnapshot(readingOrderPaths: Set<String>): ReadiumLinkSnapshot {
        val raw = href.toString()
        val url = Url(raw)
        val path = url?.path
        return ReadiumLinkSnapshot(
            rawHref = raw,
            path = path,
            fragment = url?.fragment,
            mediaType = mediaType?.toString(),
            title = title,
            children = children.map { it.toSnapshot(readingOrderPaths) },
            targetsReadingOrder = path != null && path in readingOrderPaths,
        )
    }

    private fun Link.decodedPath(): String? = Url(href.toString())?.path
}

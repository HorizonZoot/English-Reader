package io.github.zoot.englishreader.data.importer.spike

/**
 * SPIKE-ONLY neutral projection of a Readium publication.
 *
 * Keeps spike assertions independent of Readium types so the observed structure can be compared
 * against the production [io.github.zoot.englishreader.data.importer.EpubTextExtractor] contract.
 */
internal data class ReadiumPublicationSnapshot(
    val identifier: String?,
    val title: String?,
    val authors: List<String>,
    val languages: List<String>,
    val readingOrder: List<ReadiumLinkSnapshot>,
    val tableOfContents: List<ReadiumLinkSnapshot>,
)

/**
 * One link (reading-order item or TOC entry).
 *
 * @param rawHref href exactly as Readium reports it, before any normalization
 * @param path percent-decoded path without the fragment, or `null` when Readium cannot parse it
 * @param fragment fragment identifier, or `null` when absent
 * @param targetsReadingOrder whether [path] matches a reading-order item's path
 */
internal data class ReadiumLinkSnapshot(
    val rawHref: String,
    val path: String?,
    val fragment: String?,
    val mediaType: String?,
    val title: String?,
    val children: List<ReadiumLinkSnapshot>,
    val targetsReadingOrder: Boolean,
) {
    val childCount: Int
        get() = children.size
}

package io.github.zoot.englishreader.data.importer.spike

import android.content.Context
import java.io.File
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * SPIKE-ONLY compile-time proof of the Readium 3.0.3 API surface the spike depends on.
 *
 * Lives in `src/debug` so both `src/testDebug` (JVM/Robolectric) and `src/androidTest`
 * (instrumentation) exercise the same implementation instead of variant-specific copies.
 */
internal class ReadiumApiCompileProbe(context: Context) : AutoCloseable {
    private val httpClient = DefaultHttpClient()

    // private so no caller can reach assetRetriever.retrieve(...) directly and skip the
    // archive-budget preflight enforced by retrieve() below.
    private val assetRetriever = AssetRetriever(
        contentResolver = context.contentResolver,
        httpClient = httpClient,
    )

    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    suspend fun retrieve(file: File): Try<Asset, AssetRetriever.RetrieveError> {
        ReadiumArchivePreflight.inspect(file)
        return assetRetriever.retrieve(file)
    }

    suspend fun open(asset: Asset): Try<Publication, PublicationOpener.OpenError> =
        publicationOpener.open(
            asset = asset,
            allowUserInteraction = false,
        )

    fun readingOrder(publication: Publication): List<Link> = publication.readingOrder

    fun tableOfContents(publication: Publication): List<Link> = publication.tableOfContents

    fun resource(publication: Publication, link: Link): Resource? = publication.get(link)

    suspend fun read(resource: Resource): Try<ByteArray, ReadError> = resource.read()

    override fun close() = Unit
}

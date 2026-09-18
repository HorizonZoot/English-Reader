package io.github.zoot.englishreader.data.update

class AppVersion private constructor(
    val numbers: List<String>,
    val preRelease: List<String>
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val result = compareNumbers(numbers.getOrElse(index) { "0" }, other.numbers.getOrElse(index) { "0" })
            if (result != 0) return result
        }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[index]
            val b = other.preRelease[index]
            val aNumeric = a.all { it in '0'..'9' }
            val bNumeric = b.all { it in '0'..'9' }
            val result = when {
                aNumeric && bNumeric -> compareNumbers(a, b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val numericIdentifier = Regex("0|[1-9][0-9]*")
        private val identifier = Regex("[0-9A-Za-z-]+")

        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim().orEmpty().let {
                if (it.startsWith('v') || it.startsWith('V')) it.drop(1) else it
            }
            val buildIndex = text.indexOf('+')
            if (buildIndex >= 0 && text.substring(buildIndex + 1).split('.').any { !identifier.matches(it) }) {
                return null
            }
            val version = text.substringBefore('+')
            val numbers = version.substringBefore('-').split('.')
            if (numbers.any { !numericIdentifier.matches(it) }) return null
            val preRelease = if ('-' in version) version.substringAfter('-').split('.') else emptyList()
            if (preRelease.any {
                    !identifier.matches(it) || (it.all { char -> char in '0'..'9' } && !numericIdentifier.matches(it))
                }) return null
            return AppVersion(numbers, preRelease)
        }

        fun isNewer(remote: String?, local: String?): Boolean {
            val remoteVersion = parse(remote) ?: return false
            val localVersion = parse(local) ?: return false
            return remoteVersion > localVersion
        }

        // Validated numeric identifiers have no leading zeroes; length avoids integer overflow.
        private fun compareNumbers(a: String, b: String): Int =
            a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

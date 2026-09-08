package com.petitpoint.vision.model

/**
 * Geometria goblenului real al utilizatorului.
 *
 * Diagrama completă are 200 coloane x 250 rânduri, împărțită în 16 pagini:
 * 4 coloane de pagină x 4 rânduri de pagină. Primele trei benzi au câte 70 de
 * rânduri, iar ultima are 40. Fiecare pagină are 50 de coloane.
 */
object BuiltInGoblenProject {
    const val TOTAL_COLS = 200
    const val TOTAL_ROWS = 250
    const val PAGE_COUNT = 16

    data class Page(
        val number: Int,
        val startRow: Int,
        val startCol: Int,
        val rowCount: Int,
        val colCount: Int = 50
    ) {
        val endRowInclusive: Int get() = startRow + rowCount - 1
        val endColInclusive: Int get() = startCol + colCount - 1
        val region: GridRegion get() = GridRegion(startRow, startCol, rowCount, colCount)

        val label: String
            get() = "P$number · R${startRow + 1}-${endRowInclusive + 1} · C${startCol + 1}-${endColInclusive + 1}"
    }

    val pages: List<Page> = (1..PAGE_COUNT).map { number ->
        val pageRow = (number - 1) / 4
        val pageCol = (number - 1) % 4
        val startRow = when (pageRow) {
            0 -> 0
            1 -> 70
            2 -> 140
            else -> 210
        }
        val rowCount = if (pageRow < 3) 70 else 40
        Page(
            number = number,
            startRow = startRow,
            startCol = pageCol * 50,
            rowCount = rowCount
        )
    }

    fun page(number: Int): Page = pages[(number - 1).coerceIn(0, PAGE_COUNT - 1)]
}

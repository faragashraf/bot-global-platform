package com.botglobal.lamma.app.ui

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class XoBoardPresentationTests {
    @Test
    fun authoritative_cells_keep_identical_visual_coordinates_in_arabic_and_english() {
        val board = listOf(
            "x", "o", "x",
            "", "o", "",
            "x", "", "o",
        )

        val english = xoBoardPresentation(board, 3, 3, LayoutDirection.Ltr)
        val arabic = xoBoardPresentation(board, 3, 3, LayoutDirection.Rtl)

        assertEquals(LayoutDirection.Ltr, english.layoutDirection)
        assertEquals(LayoutDirection.Ltr, arabic.layoutDirection)
        assertEquals(english.cells, arabic.cells)

        assertCell(arabic, index = 0, row = 0, column = 0, mark = "x")
        assertCell(arabic, index = 1, row = 0, column = 1, mark = "o")
        assertCell(arabic, index = 2, row = 0, column = 2, mark = "x")
        assertCell(arabic, index = 4, row = 1, column = 1, mark = "o")
        assertCell(arabic, index = 6, row = 2, column = 0, mark = "x")
        assertCell(arabic, index = 8, row = 2, column = 2, mark = "o")
    }

    @Test
    fun completed_winning_line_has_identical_geometry_in_arabic_and_english() {
        val completedBoard = listOf(
            "x", "x", "x",
            "o", "o", "",
            "", "", "",
        )

        val english = xoBoardPresentation(completedBoard, 3, 3, LayoutDirection.Ltr)
        val arabic = xoBoardPresentation(completedBoard, 3, 3, LayoutDirection.Rtl)

        assertEquals(setOf(0, 1, 2), english.winningCellIndexes)
        assertEquals(english.winningCellIndexes, arabic.winningCellIndexes)
        assertEquals(
            listOf(0 to 0, 0 to 1, 0 to 2),
            arabic.cells.filter { it.index in arabic.winningCellIndexes }.map { it.visualRow to it.visualColumn },
        )
    }

    private fun assertCell(
        presentation: XoBoardPresentation,
        index: Int,
        row: Int,
        column: Int,
        mark: String,
    ) {
        val cell = presentation.cells.single { it.index == index }
        assertEquals(row, cell.row)
        assertEquals(column, cell.column)
        assertEquals(row, cell.visualRow)
        assertEquals(column, cell.visualColumn)
        assertEquals(mark, cell.mark)
    }
}

package com.botglobal.lamma.app.ui

import androidx.compose.ui.unit.LayoutDirection

internal data class XoBoardCellPresentation(
    val index: Int,
    val row: Int,
    val column: Int,
    val visualRow: Int,
    val visualColumn: Int,
    val mark: String,
)

internal data class XoBoardPresentation(
    val layoutDirection: LayoutDirection,
    val cells: List<XoBoardCellPresentation>,
    val winningCellIndexes: Set<Int>,
)

/**
 * Builds locale-invariant board geometry from the server's row-major board.
 *
 * Surrounding UI may be RTL, but game coordinates always flow left-to-right:
 * index = row * boardSize + column. The returned layout direction must be applied
 * only around the board container so Arabic text and navigation remain RTL.
 */
internal fun xoBoardPresentation(
    board: List<String>,
    boardSize: Int,
    winLength: Int,
    @Suppress("UNUSED_PARAMETER") surroundingLayoutDirection: LayoutDirection,
): XoBoardPresentation {
    require(boardSize > 0) { "Board size must be positive." }
    require(board.size == boardSize * boardSize) { "Board cell count does not match its size." }
    require(winLength in 1..boardSize) { "Win length must fit the board." }

    val boardLayoutDirection = LayoutDirection.Ltr
    val cells = board.mapIndexed { index, mark ->
        val row = index / boardSize
        val column = index % boardSize
        XoBoardCellPresentation(
            index = index,
            row = row,
            column = column,
            visualRow = row,
            visualColumn = column,
            mark = mark,
        )
    }

    return XoBoardPresentation(
        layoutDirection = boardLayoutDirection,
        cells = cells,
        winningCellIndexes = winningCellIndexes(board, boardSize, winLength),
    )
}

private fun winningCellIndexes(board: List<String>, boardSize: Int, winLength: Int): Set<Int> {
    val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
    for (row in 0 until boardSize) for (column in 0 until boardSize) {
        val mark = board[row * boardSize + column]
        if (mark.isBlank()) continue
        for ((rowStep, columnStep) in directions) {
            val cells = (0 until winLength).map { distance ->
                val nextRow = row + rowStep * distance
                val nextColumn = column + columnStep * distance
                if (nextRow in 0 until boardSize && nextColumn in 0 until boardSize) {
                    nextRow * boardSize + nextColumn
                } else {
                    -1
                }
            }
            if (cells.all { it >= 0 && board[it] == mark }) return cells.toSet()
        }
    }
    return emptySet()
}

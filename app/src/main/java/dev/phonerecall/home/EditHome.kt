package dev.phonerecall.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.HomeMode
import dev.phonerecall.HomeState
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule

/** Edit Home: add cards, hide or restore them, and let moved cards go back to adapting. */
@Composable
fun EditHome(state: HomeState, gutter: Dp, vm: AthenaViewModel) {
    val c = Athena.colors
    val anyMoved = state.board.onHome.any { it.moved }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Edit Home", gutter) {
            TextAction("Add card", { vm.openAdd() })
            PrimaryAction("Done", { vm.setMode(HomeMode.BOARD) })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxl)) {
            Line(
                "To move a card, hold it on Home and drag. Swipe a card sideways when you're done with it. To hide a kind of card, use its menu.",
                Type.small, c.ink2, Modifier.widthIn(max = 560.dp).padding(bottom = Space.l),
            )
            Kicker("On Home", Modifier.padding(bottom = Space.s))
            for (row in state.board.onHome) key(row.id) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                        Line(row.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                        Line(row.about, Type.small, c.ink3, maxLines = 1)
                    }
                    if (row.pinned) { StatusWord("Pinned", Tone.ACTIVE); Spacer(Modifier.size(Space.s)) }
                    else if (row.moved) { StatusWord("Moved", Tone.QUIET); Spacer(Modifier.size(Space.s)) }
                    if (row.neverHide) StatusWord("Stays", Tone.QUIET)
                    else TextAction("Hide", { vm.hide(row.id, null) })
                }
            }
            if (anyMoved) {
                Spacer(Modifier.height(Space.s))
                TextAction("Let moved cards adapt again", { vm.resetPositions() }, Modifier.offset(x = -Space.s))
            }

            if (state.board.hidden.isNotEmpty()) {
                Spacer(Modifier.height(Space.xl))
                Kicker("Hidden", Modifier.padding(bottom = Space.s))
                for (h in state.board.hidden) key("hidden-" + h.id) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule), verticalAlignment = Alignment.CenterVertically) {
                        Line(h.title, Type.body, c.ink2, Modifier.weight(1f), maxLines = 1)
                        TextAction("Restore", { vm.show(h.id) }, emphasis = true)
                    }
                }
            }
        }
    }
}

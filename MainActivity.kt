package com.example.intradayscreener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SortKey { NAME, PCT, VOL, LOW, HIGH }

data class UiState(
    val rows: List<ScreenRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val updated: String = "",
    val sort: SortKey = SortKey.PCT,
    val desc: Boolean = true,
)

class ScreenerViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var raw: List<ScreenRow> = emptyList()

    init {
        viewModelScope.launch {
            while (true) { refresh(); delay(SourceConfig.REFRESH_MS) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                raw = Screener.run(Repository.source.load())
                val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                _state.value = _state.value.copy(loading = false, updated = t).sorted()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    fun sortBy(k: SortKey) {
        val s = _state.value
        _state.value = (if (s.sort == k) s.copy(desc = !s.desc) else s.copy(sort = k, desc = true)).sorted()
    }

    private fun UiState.sorted(): UiState {
        val cmp = compareBy<ScreenRow> {
            when (sort) {
                SortKey.NAME -> it.name.lowercase()
                SortKey.PCT -> it.pctChange
                SortKey.VOL -> it.volChange
                SortKey.LOW -> it.upFromLow
                SortKey.HIGH -> it.downFromHigh
            } as Comparable<Any>
        }
        return copy(rows = raw.sortedWith(if (desc) cmp.reversed() else cmp))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ScreenerScreen() } }
    }
}

private val Green = Color(0xFF0E9F6E)
private val Red = Color(0xFFD93025)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenerScreen(vm: ScreenerViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Intraday Screener") },
                actions = { TextButton(onClick = vm::refresh) { Text("Refresh") } },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            FilterSummary(s)
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text("Error: $it", color = Red, modifier = Modifier.padding(12.dp)) }
            HeaderRow(s, vm::sortBy)
            HorizontalDivider()
            if (!s.loading && s.error == null && s.rows.isEmpty())
                Text("No stocks match the filters right now.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.rows, key = { it.symbol }) { StockRow(it); HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun FilterSummary(s: UiState) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF3F4F6)).padding(12.dp)) {
        Text(
            "Mcap ₹1,000 Cr – ₹1,50,00,000 Cr  •  v% > 50  •  l% > 15  •  h% > 5",
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
        val src = if (Repository.isSample) "SAMPLE DATA (set SourceConfig.ENDPOINT)" else "Live"
        Text("${s.rows.size} results • $src" + if (s.updated.isNotEmpty()) " • ${s.updated}" else "",
            fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun HeaderRow(s: UiState, onSort: (SortKey) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("Stock", SortKey.NAME, s, onSort, Modifier.weight(2.2f), TextAlign.Start)
        HeaderCell("%c", SortKey.PCT, s, onSort, Modifier.weight(1f))
        HeaderCell("v%", SortKey.VOL, s, onSort, Modifier.weight(1f))
        HeaderCell("l%", SortKey.LOW, s, onSort, Modifier.weight(1f))
        HeaderCell("h%", SortKey.HIGH, s, onSort, Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(label: String, key: SortKey, s: UiState, onSort: (SortKey) -> Unit,
                       modifier: Modifier, align: TextAlign = TextAlign.End) {
    val arrow = if (s.sort == key) (if (s.desc) " ▼" else " ▲") else ""
    Text(label + arrow, modifier.clickable { onSort(key) }, textAlign = align,
        fontWeight = FontWeight.Bold, fontSize = 13.sp)
}

@Composable
private fun StockRow(r: ScreenRow) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(2.2f)) {
            Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("₹%.2f • %s Cr".format(r.ltp, "%,.0f".format(r.marketCapCr)), fontSize = 11.sp, color = Color.Gray)
        }
        Cell(r.pctChange, Modifier.weight(1f), signColor = true)
        Cell(r.volChange, Modifier.weight(1f))
        Cell(r.upFromLow, Modifier.weight(1f))
        Cell(r.downFromHigh, Modifier.weight(1f))
    }
}

@Composable
private fun Cell(v: Double, m: Modifier, signColor: Boolean = false) {
    Text("%.2f".format(v), m, textAlign = TextAlign.End, fontSize = 13.sp,
        color = if (signColor) (if (v >= 0) Green else Red) else Color.Unspecified)
}

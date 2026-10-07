package com.geqian6.qingjizhang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geqian6.qingjizhang.data.AppDatabase
import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).transactionDao()

    /** 当前查看的月份锚点（该月 1 号 0 点） */
    private val _monthAnchor = MutableStateFlow(Dates.startOfMonth(Dates.currentTs()))
    val monthAnchor: StateFlow<Long> = _monthAnchor.asStateFlow()

    /** 当月流水 */
    val monthRecords: StateFlow<List<TransactionRecord>> = _monthAnchor
        .flatMapLatest { start ->
            dao.observeBetween(start, Dates.addMonths(start, 1))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** 最近流水，跨月统计用。上限 2000 条足够本机个人账本 */
    val allRecords: StateFlow<List<TransactionRecord>> = dao.observeRecent(2000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** 待确认流水 */
    val pendingRecords: StateFlow<List<TransactionRecord>> = dao.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun shiftMonth(delta: Int) {
        _monthAnchor.value = Dates.addMonths(_monthAnchor.value, delta)
    }

    fun resetMonth() {
        _monthAnchor.value = Dates.startOfMonth(Dates.currentTs())
    }

    fun add(record: TransactionRecord) = viewModelScope.launch {
        dao.insert(record)
    }

    fun update(record: TransactionRecord) = viewModelScope.launch {
        dao.update(record)
    }

    fun remove(record: TransactionRecord) = viewModelScope.launch {
        dao.delete(record)
    }

    fun confirmPending(record: TransactionRecord) = viewModelScope.launch {
        dao.update(record.copy(status = TransactionRecord.STATUS_CONFIRMED))
    }

    fun confirmAllPending() = viewModelScope.launch {
        val pending = dao.observePending().first()
        pending.forEach { dao.update(it.copy(status = TransactionRecord.STATUS_CONFIRMED)) }
    }
}

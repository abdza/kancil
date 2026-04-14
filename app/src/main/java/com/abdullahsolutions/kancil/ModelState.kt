package com.abdullahsolutions.kancil

import kotlinx.coroutines.flow.MutableStateFlow

object ModelState {

    sealed class Status {
        object Idle : Status()
        data class Downloading(val progress: Int) : Status()
        object Loading : Status()
        object Ready : Status()
        data class Error(val msg: String) : Status()
    }

    val status = MutableStateFlow<Status>(Status.Idle)
}

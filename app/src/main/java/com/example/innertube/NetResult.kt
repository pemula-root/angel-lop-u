package com.example.innertube

sealed interface NetResult<out T> {
    data class Success<out T>(val data: T) : NetResult<T>
    data class Empty(val reason: String = "No data available") : NetResult<Nothing>
    data class Error(
        val message: String,
        val httpCode: Int? = null,
        val bodySnippet: String? = null,
        val throwable: Throwable? = null
    ) : NetResult<Nothing>
}

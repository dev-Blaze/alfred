package com.yshah.aide.network

sealed interface ConnectionTestResult {
    data class Success(val httpStatusCode: Int) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

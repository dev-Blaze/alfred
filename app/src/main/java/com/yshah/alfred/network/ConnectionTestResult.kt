package com.yshah.alfred.network

sealed interface ConnectionTestResult {
    data class Success(val httpStatusCode: Int) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}
